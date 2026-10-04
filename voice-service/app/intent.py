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

from app.text_normalize import strip_vietnamese_tones

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
        self._pattern_index: list[tuple[str, str, dict]] = []  # (original_pattern, normalized_pattern, intent_def)

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
                pat_lower = pattern.lower()
                pat_norm = strip_vietnamese_tones(pat_lower)
                self._pattern_index.append((pat_lower, pat_norm, intent_def))

    def extract_room(self, text: str) -> tuple[str | None, str]:
        """Tách tên phòng ra khỏi câu nói.

        Args:
            text: câu nói gốc (đã lowercase)

        Returns:
            (room_name hoặc None, phần còn lại của câu)
        """
        text_lower = text.lower().strip()
        text_norm = strip_vietnamese_tones(text_lower)

        norm_rooms = {strip_vietnamese_tones(r): r for r in ROOM_PATTERNS}
        
        # Dùng partial_ratio để tìm tên phòng kể cả khi STT bị sai một vài chữ (khách -> khảm)
        result = process.extractOne(
            text_norm,
            list(norm_rooms.keys()),
            scorer=fuzz.partial_ratio,
        )

        if result:
            matched_norm, score, _ = result
            if score >= 75:  # Khớp > 75% là chấp nhận (ví dụ 'phong kham' vs 'phong khach')
                original_room = norm_rooms[matched_norm]
                
                # Cố gắng xóa tên phòng khỏi text gốc nếu nó nằm nguyên vẹn
                remaining = text_lower
                if matched_norm in text_norm:
                    start_idx = text_norm.find(matched_norm)
                    end_idx = start_idx + len(matched_norm)
                    remaining = text_lower[:start_idx] + " " + text_lower[end_idx:]
                
                remaining = re.sub(r"\s+", " ", remaining).strip()
                remaining = re.sub(r"^(ở|tại|trong|của)\s+", "", remaining).strip()
                remaining = re.sub(r"\s+(ở|tại|trong|của)$", "", remaining).strip()
                
                return original_room, remaining

        return None, text_lower

    def extract_parameters(self, text: str, intent_def: dict) -> tuple[str, dict[str, Any]]:
        """Trích xuất tham số từ câu nói (VD: nhiệt độ, độ sáng).

        Args:
            text: câu nói gốc
            intent_def: definition của intent

        Returns:
            (action đã chốt, Dict tham số)
        """
        params: dict[str, Any] = {}
        action = intent_def["action"]
        dev_type = intent_def["device_type"]

        # Tìm bất kỳ con số nào trong câu
        match = re.search(r"(\d+)", text)
        if match:
            value = int(match.group(1))

            if dev_type == "air_conditioner":
                # Nếu có chữ "lên" (ví dụ tăng lên 28) hoặc "đặt/chỉnh" -> absolute
                if re.search(r"(lên|thành|đặt|chỉnh)\s*\d+", text.lower()):
                    action = "SET_TEMPERATURE"
                    params["temperature"] = value
                else:
                    # Tăng/giảm tương đối (ví dụ thêm 2 độ)
                    params["amount"] = value

            elif dev_type == "light":
                # "lên 80", "thành 80" -> absolute
                if re.search(r"(lên|thành|đặt|chỉnh)\s*\d+", text.lower()):
                    action = "SET_BRIGHTNESS"
                    params["brightness"] = value
                else:
                    params["amount"] = value

        return action, params

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
        if not remaining:
            return None
            
        remaining_norm = strip_vietnamese_tones(remaining)
        
        # Tạo danh sách các pattern đã được chuẩn hóa
        all_norm_patterns = [p_norm for _, p_norm, _ in self._pattern_index]

        # Tìm match tốt nhất trên bản chuẩn hóa (Sử dụng token_set_ratio để bỏ qua các từ thừa như tham số, tên phòng)
        result = process.extractOne(
            remaining_norm,
            all_norm_patterns,
            scorer=fuzz.token_set_ratio,
        )

        if result is None:
            return None

        matched_norm_pattern, score, idx = result

        # Lấy lại pattern gốc và intent_def dựa trên index
        matched_original_pattern, _, intent_def = self._pattern_index[idx]

        if score < self.config.min_confidence:
            logger.info(
                "Confidence quá thấp: '%s' (norm: '%s') → '%s' (%.0f%% < %.0f%%)",
                remaining, remaining_norm, matched_original_pattern, score, self.config.min_confidence,
            )
            return None

        # Bước 3: Trích xuất tham số
        action, params = self.extract_parameters(text, intent_def)

        intent_result = IntentResult(
            action=action,
            device_type=intent_def["device_type"],
            room=room,
            parameters=params,
            confidence=score,
            matched_pattern=matched_original_pattern,
            raw_text=text,
        )

        logger.info(
            "Intent mapped: '%s' → %s %s room=%s params=%s (conf=%.0f%%)",
            text, intent_result.action, intent_result.device_type,
            intent_result.room, intent_result.parameters, intent_result.confidence,
        )

        return intent_result
