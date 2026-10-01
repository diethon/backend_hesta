"""
HESTA Voice Service — Intent Mapping (Mục 2.1 bước 4)
Rule-based intent mapping: ánh xạ transcript → (action, device_type, params).

Sử dụng:
- Regex để tách tên phòng ra khỏi câu nói
- Fuzzy matching (rapidfuzz) để so khớp action patterns
- Device directory để resolve (room, type) → device_id

Lưu ý: Module này KHÔNG dùng model ML, chỉ regex + fuzzy matching.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from rapidfuzz import fuzz, process

logger = logging.getLogger(__name__)

# Danh sách tên phòng phổ biến trong nhà Việt Nam
# Dùng để tách tên phòng ra khỏi câu nói
ROOM_PATTERNS = [
    "phòng khách",
    "phòng ngủ",
    "phòng bếp",
    "phòng tắm",
    "phòng ăn",
    "phòng làm việc",
    "phòng học",
    "phòng trẻ em",
    "ban công",
    "sân thượng",
    "tầng trệt",
    "tầng lầu",
    "nhà kho",
    "garage",
    "sảnh",
    "hành lang",
]


@dataclass
class IntentResult:
    """Kết quả mapping intent."""
    action: str                    # VD: "TURN_ON"
    device_type: str               # VD: "light"
    room: str | None = None        # VD: "phòng khách"
    parameters: dict[str, Any] = field(default_factory=dict)  # VD: {"temperature": 25}
    confidence: float = 0.0        # Điểm fuzzy matching (0-100)
    matched_pattern: str = ""      # Pattern đã khớp
    raw_text: str = ""             # Câu gốc


@dataclass
class IntentConfig:
    """Cấu hình intent mapping."""
    mapping_file: str = "intents_vi.json"
    min_confidence: float = 60.0    # Ngưỡng tối thiểu để chấp nhận match
    context_ttl_seconds: int = 45


class IntentMapper:
    """
    Rule-based intent mapping cho tiếng Việt.

    Quy trình:
    1. Tách tên phòng ra khỏi câu nói
    2. Fuzzy match phần còn lại với patterns trong intents_vi.json
    3. Trích xuất tham số (nếu có, VD: nhiệt độ)
    4. Trả về IntentResult

    Sử dụng:
        mapper = IntentMapper(config)
        mapper.load_intents("config/intents_vi.json")
        result = mapper.map("bật đèn phòng khách")
    """

    def __init__(self, config: IntentConfig | None = None):
        self.config = config or IntentConfig()
        self._intents: list[dict] = []
        self._pattern_index: list[tuple[str, dict]] = []  # (pattern, intent_def)

    def load_intents(self, filepath: str | Path) -> None:
        """Load intent definitions từ JSON file."""
        filepath = Path(filepath)
        with open(filepath, "r", encoding="utf-8") as f:
            data = json.load(f)

        self._intents = data.get("intents", [])
        self._build_pattern_index()
        logger.info("Loaded %d intents, %d patterns", len(self._intents), len(self._pattern_index))

    def _build_pattern_index(self) -> None:
        """Xây dựng index (pattern → intent) để tìm kiếm nhanh."""
        self._pattern_index = []
        for intent_def in self._intents:
            for pattern in intent_def.get("patterns", []):
                self._pattern_index.append((pattern.lower(), intent_def))

    def extract_room(self, text: str) -> tuple[str | None, str]:
        """Tách tên phòng ra khỏi câu nói.

        Args:
            text: câu nói gốc (đã lowercase)

        Returns:
            (room_name hoặc None, phần còn lại của câu)
        """
        text_lower = text.lower().strip()

        # Sắp xếp theo độ dài giảm dần để match tên phòng dài nhất trước
        # (VD: "phòng làm việc" trước "phòng")
        sorted_rooms = sorted(ROOM_PATTERNS, key=len, reverse=True)

        for room in sorted_rooms:
            if room in text_lower:
                # Loại bỏ tên phòng khỏi câu
                remaining = text_lower.replace(room, "").strip()
                # Dọn khoảng trắng thừa
                remaining = re.sub(r"\s+", " ", remaining).strip()
                # Dọn các từ nối thừa ở đầu/cuối
                remaining = re.sub(r"^(ở|tại|trong|của)\s+", "", remaining).strip()
                remaining = re.sub(r"\s+(ở|tại|trong|của)$", "", remaining).strip()
                return room, remaining

        return None, text_lower

    def extract_parameters(self, text: str, intent_def: dict) -> dict[str, Any]:
        """Trích xuất tham số từ câu nói (VD: nhiệt độ).

        Args:
            text: phần câu nói còn lại sau khi tách room + action
            intent_def: definition của intent (có trường has_parameter)

        Returns:
            Dict tham số đã trích xuất
        """
        params: dict[str, Any] = {}
        has_param = intent_def.get("has_parameter")

        if has_param == "temperature":
            # Tìm số trong câu: "25 độ", "nhiệt độ 25", etc.
            match = re.search(r"(\d{1,2})\s*(?:độ|°)?", text)
            if match:
                temp = int(match.group(1))
                # Validate range hợp lý cho điều hòa
                if 16 <= temp <= 32:
                    params["temperature"] = temp
                else:
                    logger.warning("Nhiệt độ %d ngoài phạm vi hợp lý (16-32)", temp)

        return params

    def map(self, text: str) -> IntentResult | None:
        """Map transcript text thành intent.

        Args:
            text: transcript từ STT

        Returns:
            IntentResult nếu match, None nếu không match hoặc confidence quá thấp.
        """
        if not text or not text.strip():
            return None

        if not self._pattern_index:
            logger.warning("Chưa load intents! Gọi load_intents() trước.")
            return None

        # Bước 1: Tách room
        room, remaining = self.extract_room(text)

        # Bước 2: Fuzzy match phần còn lại với tất cả patterns
        all_patterns = [p for p, _ in self._pattern_index]

        if not remaining:
            return None

        # Tìm match tốt nhất
        result = process.extractOne(
            remaining,
            all_patterns,
            scorer=fuzz.token_sort_ratio,
        )

        if result is None:
            return None

        matched_pattern, score, idx = result

        if score < self.config.min_confidence:
            logger.info(
                "Confidence quá thấp: '%s' → '%s' (%.0f%% < %.0f%%)",
                remaining, matched_pattern, score, self.config.min_confidence,
            )
            return None

        # Lấy intent definition tương ứng
        _, intent_def = self._pattern_index[idx]

        # Bước 3: Trích xuất tham số
        params = self.extract_parameters(text, intent_def)

        intent_result = IntentResult(
            action=intent_def["action"],
            device_type=intent_def["device_type"],
            room=room,
            parameters=params,
            confidence=score,
            matched_pattern=matched_pattern,
            raw_text=text,
        )

        logger.info(
            "Intent mapped: '%s' → %s %s room=%s (conf=%.0f%%)",
            text, intent_result.action, intent_result.device_type,
            intent_result.room, intent_result.confidence,
        )

        return intent_result
