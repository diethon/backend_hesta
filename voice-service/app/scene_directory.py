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

    def resolve_scene(
        self,
        query: str,
        home_id: str = "",
        aliases: list[str] | None = None,
    ) -> SceneInfo | None:
        """Tìm kịch bản theo số thứ tự (1, 2, ...) hoặc tên tiếng Việt (chính xác / mờ / aliases).

        Quy tắc ưu tiên thông minh:
        1. Nếu query là số nguyên dương N (VD: "1", "2"):
           - Ưu tiên tìm scene có tên chứa đúng số N (VD: "Kịch bản 1", "Scene 1").
           - Nếu không có, chọn scene ở vị trí thứ N (1-based index) trong danh sách scene của nhà.
        2. Nếu query là tên hoặc có danh sách aliases (VD: ['đi ngủ', 'chúc ngủ ngon']):
           - Khi có nhiều ứng viên khớp cùng mức, LUÔN ưu tiên kịch bản đang `enabled=True`!
           - Khớp chính xác không dấu -> Khớp chứa chuỗi con -> Fuzzy match chặt (>= 72%).
        """
        q_raw = (query or "").strip()
        if not q_raw and not aliases:
            return None

        scenes = self.list_scenes(home_id)
        if not scenes:
            # Thử refresh on-demand 1 lần nếu cache đang rỗng (VD người dùng vừa tạo kịch bản mới)
            self.refresh(home_id=home_id)
            scenes = self.list_scenes(home_id)
        if not scenes:
            return None

        # Sắp xếp ưu tiên scene đang enabled=True trước khi cùng độ khớp
        prioritized = sorted(scenes, key=lambda s: (not s.enabled, s.index))

        q_norm = strip_vietnamese_tones(q_raw.lower()).strip()
        q_clean = re.sub(r"^(?:kich\s+ban|ket\s+ban|kich\s+bang|ngu\s+canh|che\s+do)\s+", "", q_norm).strip() or q_norm

        # 1. Trường hợp gọi theo số thứ tự ("1", "2", "so 1")
        m_num = re.match(r"^(?:so\s+)?(\d+)$", q_clean)
        if m_num:
            num = int(m_num.group(1))
            # Chỉ khớp theo tên nếu tên kịch bản đích danh là số đó (VD: "Kịch bản 1", "Scene 1", "1"),
            # KHÔNG khớp nhầm sang "Chào buổi sáng 1" khi nhà có kịch bản số 1 khác!
            exact_num_pat = re.compile(rf"^(?:kich\s+ban|scene|so)?\s*{num}$")
            for s in prioritized:
                s_norm = strip_vietnamese_tones(s.name.lower()).strip()
                if exact_num_pat.match(s_norm):
                    return s
            if 1 <= num <= len(scenes):
                return scenes[num - 1]
            return None

        search_queries = [q_clean]
        if aliases:
            for a in aliases:
                a_norm = strip_vietnamese_tones(a.lower()).strip()
                if a_norm and a_norm not in search_queries:
                    search_queries.append(a_norm)

        # 2. Khớp chính xác không dấu (ưu tiên enabled=True trước)
        for sq in search_queries:
            for s in prioritized:
                s_norm = strip_vietnamese_tones(s.name.lower()).strip()
                s_clean = re.sub(r"^(?:kich\s+ban|ngu\s+canh|che\s+do)\s+", "", s_norm).strip() or s_norm
                if sq == s_norm or sq == s_clean:
                    return s

        # 3. Khớp chứa chuỗi con (có ranh giới từ & chống scene tên ngắn < 4 ký tự như "nhà"/"bep" cướp lệnh!)
        contains_matches: list[tuple[bool, int, SceneInfo]] = []
        for sq in search_queries:
            for s in scenes:
                s_norm = strip_vietnamese_tones(s.name.lower()).strip()
                if not s_norm:
                    continue
                sq_in_s = len(sq) >= 4 and bool(re.search(rf"\b{re.escape(sq)}\b", s_norm))
                s_in_sq = (
                    len(s_norm) >= 4
                    and bool(re.search(rf"\b{re.escape(s_norm)}\b", sq))
                    and (len(s_norm) / max(len(sq), 1) >= 0.55)
                )
                if sq_in_s or s_in_sq:
                    contains_matches.append((not s.enabled, abs(len(s_norm) - len(sq)), s))
        if contains_matches:
            contains_matches.sort(key=lambda item: (item[0], item[1]))
            return contains_matches[0][2]

        # 4. Fuzzy match chặt (>= 72%) để không chọn bừa kịch bản không liên quan
        # Bỏ qua các kịch bản tên quá ngắn (< 4 ký tự như "nhà", "bep") khỏi fuzzy match để tránh token_set_ratio cướp lệnh!
        best_scene: SceneInfo | None = None
        best_rank = (-1, 0.0)
        for sq in search_queries:
            for s in scenes:
                s_norm = strip_vietnamese_tones(s.name.lower()).strip()
                s_clean = re.sub(r"^(?:kich\s+ban|ngu\s+canh|che\s+do)\s+", "", s_norm).strip() or s_norm
                if len(s_clean) < 4:
                    continue
                score = max(
                    fuzz.ratio(sq, s_clean),
                    fuzz.token_sort_ratio(sq, s_clean),
                    fuzz.token_set_ratio(sq, s_clean) * 0.92,
                )
                if score >= 72.0:
                    rank = (1 if s.enabled else 0, score)
                    if rank > best_rank:
                        best_rank = rank
                        best_scene = s

        if best_scene is not None:
            logger.info("Scene fuzzy matched: '%s' → '%s' (score=%.1f%%)", query, best_scene.name, best_rank[1])
            return best_scene

        return None
