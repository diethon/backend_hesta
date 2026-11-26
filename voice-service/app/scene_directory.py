"""
HESTA Voice Service — Scene Directory
Cache danh sách kịch bản (Scenes) từ Spring Boot (/internal/scenes) để voice AI
tìm kiếm nhanh kịch bản theo tên hoặc số thứ tự (VD: "kịch bản 1", "chào buổi sáng", "nghỉ trưa").
"""

from __future__ import annotations

import logging
import os
import re
import threading
import time
from dataclasses import dataclass, field
from typing import Any

import httpx
from rapidfuzz import fuzz

from app.text_normalize import strip_vietnamese_tones

logger = logging.getLogger(__name__)


@dataclass
class SceneDirectoryConfig:
    """Cấu hình kết nối lấy danh sách kịch bản từ Backend."""
    base_url: str = "http://localhost:8080"
    scenes_endpoint: str = "/internal/scenes"
    api_key_env: str = "INTERNAL_API_KEY"
    refresh_interval_seconds: int = 60
    request_timeout_seconds: float = 5.0


@dataclass
class SceneInfo:
    """Thông tin 1 kịch bản trong cache."""
    scene_id: str
    name: str
    home_id: str
    enabled: bool = True
    index: int = 0
    description: str = ""
    actions: list[dict[str, Any]] = field(default_factory=list)


class SceneDirectory:
    """Cache cục bộ danh sách kịch bản theo homeId, hỗ trợ tra cứu theo số thứ tự & tên mờ."""

    def __init__(
        self,
        config: SceneDirectoryConfig | None = None,
        http_client: httpx.Client | None = None,
    ):
        self.config = config or SceneDirectoryConfig()
        self._client = http_client
        self._owns_client = http_client is None
        self._scenes: list[SceneInfo] = []
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._refresh_thread: threading.Thread | None = None
        self._last_refresh_time: float = 0.0

    def _get_client(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(
                base_url=self.config.base_url,
                timeout=self.config.request_timeout_seconds,
            )
        return self._client

    def _get_api_key(self) -> str:
        return os.environ.get(self.config.api_key_env, "")

    def refresh(self, home_id: str = "") -> bool:
        """Gọi GET /internal/scenes để cập nhật cache kịch bản."""
        try:
            client = self._get_client()
            headers = {"X-Internal-Api-Key": self._get_api_key()}
            params = {"homeId": home_id} if home_id else {}
            resp = client.get(self.config.scenes_endpoint, headers=headers, params=params)
            resp.raise_for_status()
            raw_list = resp.json()
            if not isinstance(raw_list, list):
                return False

            parsed: list[SceneInfo] = []
            for idx, item in enumerate(raw_list, start=1):
                sid = str(item.get("id") or "")
                name = str(item.get("name") or "").strip()
                if not sid or not name:
                    continue
                parsed.append(SceneInfo(
                    scene_id=sid,
                    name=name,
                    home_id=str(item.get("homeId") or ""),
                    enabled=bool(item.get("enabled", True)),
                    index=int(item.get("index") or idx),
                    description=str(item.get("description") or ""),
                    actions=item.get("actions") or [],
                ))

            with self._lock:
                self._scenes = parsed
                self._last_refresh_time = time.time()

            logger.info("Scene cache refreshed: %d kịch bản", len(parsed))
            return True
        except Exception as e:
            logger.debug("Could not refresh scene directory: %s", e)
            return False

    def start(self, home_id: str = "") -> None:
        """Khởi động thread nền tự động làm mới danh sách kịch bản."""
        self.refresh(home_id=home_id)
        if self._refresh_thread is not None and self._refresh_thread.is_alive():
            return
        self._stop_event.clear()

        def _loop():
            while not self._stop_event.wait(self.config.refresh_interval_seconds):
                self.refresh(home_id=home_id)

        self._refresh_thread = threading.Thread(target=_loop, daemon=True, name="scene-dir-refresh")
        self._refresh_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._refresh_thread and self._refresh_thread.is_alive():
            self._refresh_thread.join(timeout=2.0)
        if self._owns_client and self._client:
            self._client.close()
            self._client = None

    def list_scenes(self, home_id: str = "") -> list[SceneInfo]:
        with self._lock:
            if not home_id:
                return list(self._scenes)
            scoped = [s for s in self._scenes if not s.home_id or s.home_id == home_id]
            return scoped if scoped else list(self._scenes)

    def get_scene_names(self, home_id: str = "") -> list[str]:
        return [s.name for s in self.list_scenes(home_id)]

    def resolve_scene(self, query: str, home_id: str = "") -> SceneInfo | None:
        """Tìm kịch bản theo số thứ tự (1, 2, ...) hoặc tên tiếng Việt (chính xác / mờ).

        Quy tắc ưu tiên:
        1. Nếu query là số nguyên dương N (VD: "1", "2"):
           - Ưu tiên tìm scene có tên chứa đúng số N (VD: "Kịch bản 1", "Scene 1").
           - Nếu không có, chọn scene ở vị trí thứ N (1-based index) trong danh sách scene của nhà.
        2. Nếu query là tên:
           - Khớp chính xác không dấu (bỏ tiền tố "kịch bản" / "chế độ").
           - Khớp chuỗi con hoặc fuzzy match chặt (>= 72% bằng token_set_ratio / ratio).
        """
        q_raw = (query or "").strip()
        if not q_raw:
            return None

        scenes = self.list_scenes(home_id)
        if not scenes:
            # Thử refresh on-demand 1 lần nếu cache đang rỗng (VD người dùng vừa tạo kịch bản mới)
            self.refresh(home_id=home_id)
            scenes = self.list_scenes(home_id)
        if not scenes:
            return None

        q_norm = strip_vietnamese_tones(q_raw.lower()).strip()
        q_clean = re.sub(r"^(?:kich\s+ban|ngu\s+canh|che\s+do)\s+", "", q_norm).strip() or q_norm

        # 1. Trường hợp gọi theo số thứ tự ("1", "2", "so 1")
        m_num = re.match(r"^(?:so\s+)?(\d+)$", q_clean)
        if m_num:
            num = int(m_num.group(1))
            # 1a. Ưu tiên scene có tên chứa chính xác số đó (VD: "Kịch bản 1", "Kịch bản số 1")
            num_pat = re.compile(rf"(?:^|\D){num}(?:\D|$)")
            for s in scenes:
                s_norm = strip_vietnamese_tones(s.name.lower())
                if num_pat.search(s_norm):
                    return s
            # 1b. Fallback theo thứ tự 1-based index trong nhà
            if 1 <= num <= len(scenes):
                return scenes[num - 1]
            return None

        # 2. Khớp chính xác không dấu
        for s in scenes:
            s_norm = strip_vietnamese_tones(s.name.lower()).strip()
            s_clean = re.sub(r"^(?:kich\s+ban|ngu\s+canh|che\s+do)\s+", "", s_norm).strip() or s_norm
            if q_clean == s_norm or q_clean == s_clean:
                return s

        # 3. Khớp chứa chuỗi con (VD: "buổi sáng" -> "Chào buổi sáng")
        contains_matches = []
        for s in scenes:
            s_norm = strip_vietnamese_tones(s.name.lower()).strip()
            if q_clean in s_norm or s_norm in q_clean:
                contains_matches.append((len(s_norm), s))
        if contains_matches:
            contains_matches.sort(key=lambda pair: abs(pair[0] - len(q_clean)))
            return contains_matches[0][1]

        # 4. Fuzzy match chặt (>= 72%) để không chọn bừa kịch bản không liên quan
        best_scene: SceneInfo | None = None
        best_score = 0.0
        for s in scenes:
            s_norm = strip_vietnamese_tones(s.name.lower()).strip()
            s_clean = re.sub(r"^(?:kich\s+ban|ngu\s+canh|che\s+do)\s+", "", s_norm).strip() or s_norm
            score = max(
                fuzz.ratio(q_clean, s_clean),
                fuzz.token_sort_ratio(q_clean, s_clean),
                fuzz.token_set_ratio(q_clean, s_clean) * 0.92,
            )
            if score > best_score:
                best_score = score
                best_scene = s

        if best_scene is not None and best_score >= 72.0:
            logger.info("Scene fuzzy matched: '%s' → '%s' (score=%.1f%%)", query, best_scene.name, best_score)
            return best_scene

        return None
