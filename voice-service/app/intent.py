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
    "sân vườn",
    "garden",
    "tầng trệt",
    "tầng lầu",
    "nhà kho",
    "garage",
    "tiền sảnh",
    "hành lang",
]


ROOM_PHONETIC_VARIANTS = {
    # Biến thể phát âm Whisper cho phòng ngủ
    "phong mua": "phòng ngủ",
    "phang mua": "phòng ngủ",
    "phong mu": "phòng ngủ",
    "phang mu": "phòng ngủ",
    "phong ngu": "phòng ngủ",
    "phang ngu": "phòng ngủ",
    "phong mui": "phòng ngủ",
    "phang mui": "phòng ngủ",
    "phang ngo": "phòng ngủ",
    "phong ngo": "phòng ngủ",
    "phong ngau": "phòng ngủ",
    "phang ngau": "phòng ngủ",
    "phong nhe": "phòng ngủ",
    "phang nhe": "phòng ngủ",
    "phong nu": "phòng ngủ",
    "phang nu": "phòng ngủ",
    "dem phong mua": "phòng ngủ",
    "den phang mua": "phòng ngủ",
    "dem phong ngu": "phòng ngủ",
    "den phong ngu": "phòng ngủ",
    # Ghép tắt đèn ngủ / đèn phòng ngủ
    "den ngu": "phòng ngủ",
    # Biến thể phát âm Whisper cho phòng khách
    "phong khach": "phòng khách",
    "phang khach": "phòng khách",
    # Biến thể phát âm Whisper cho garage
    "ga ra": "garage",
    "ga-ra": "garage",
    "gara": "garage",
    "ga rô": "garage",
    "ga rát": "garage",
    # Biến thể phát âm Whisper cho sân vườn / garden
    "san vuon": "sân vườn",
    "san buon": "sân vườn",
    "garden": "garden",
}


VI_SPOKEN_NUMBERS = [
    (r"\b(một trăm|trăm|1 đám|một đám)\b", "100"),
    (r"\b(chín mươi|chín chục)\b", "90"),
    (r"\b(tám mươi|tám chục|tạm mươi|tạm vư|tạm mười|tám mười)\b", "80"),
    (r"\b(bảy mươi|bảy chục|bảy mười)\b", "70"),
    (r"\b(sáu mươi|sáu chục|sáu mười)\b", "60"),
    (r"\b(năm mươi|năm chục)\b", "50"),
    (r"\b(bốn mươi|bốn chục|buồn bươi|bốn mười)\b", "40"),
    (r"\b(ba mươi|ba chục|ba mười)\b", "30"),
    (r"\b(hai mươi|hai chục|hai mười)\b", "20"),
    (r"\b(mười|mươi|một chục|chục)\b", "10"),
]


def normalize_spoken_numbers(text: str) -> str:
    """Chuyển đổi số dạng chữ tiếng Việt hoặc âm đọc lệch của whisper thành chữ số,
    đồng thời sửa các lỗi âm học phổ biến (như 'bắt' nhầm từ 'tắt').
    """
    res = text.lower()
    # Sửa lỗi âm học Whisper: 'tắt' bị nhận nhầm thành 'bắt', 'đắt', 'tắc', 'bắc', 'ngắt', 'cắt'
    # Điều này cực kỳ quan trọng vì nếu để 'bắt đèn' bị bỏ dấu thành 'bat den', nó sẽ khớp nhầm với 'bật đèn'!
    res = re.sub(r'\b(bắt|đắt|tắc|bắc|ngắt|cắt)\b(?!\s+đầu\b)', 'tắt', res)
    # Sửa lỗi âm học Whisper: 'là mười' / 'mức mười' bị nghe nhầm thành 'lăm mươi'
    res = re.sub(r'\blăm\s+mươi\b', '10', res)
    for pat, rep in VI_SPOKEN_NUMBERS:
        res = re.sub(pat, rep, res)
    return res


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
        """Tách tên phòng ra khỏi câu nói sử dụng sliding window fuzzy matching."""
        text_lower = text.lower().strip()
        text_norm = strip_vietnamese_tones(text_lower)
        
        text_words_orig = text_lower.split()
        text_words_norm = text_norm.split()

        norm_rooms = {strip_vietnamese_tones(r): r for r in ROOM_PATTERNS}
        
        best_overall_score = 0.0
        best_overall_room = None
        best_overall_start = -1
        best_overall_len = 0
        
        for norm_room, original_room in norm_rooms.items():
            room_words = norm_room.split()
            room_len = len(room_words)
            
            if room_len > len(text_words_norm):
                continue
                
            for start in range(len(text_words_norm) - room_len + 1):
                window_words = text_words_norm[start : start + room_len]
                window_norm = " ".join(window_words)

                matched_room = original_room
                if window_norm in ROOM_PHONETIC_VARIANTS:
                    score = 100.0
                    matched_room = ROOM_PHONETIC_VARIANTS[window_norm]
                elif room_len == 1:
                    score = fuzz.ratio(window_norm, norm_room)
                    if score < 88.0:
                        continue
                else:
                    # Multi-word rooms: mỗi từ phải có độ tương đồng tối thiểu (>= 65%)
                    # và trung bình cộng phải >= 80% để tránh false positive (như 'mở cổng' khớp 'ban công')
                    word_scores = [fuzz.ratio(w, r) for w, r in zip(window_words, room_words)]
                    if any(ws < 65 for ws in word_scores) or (sum(word_scores) / len(word_scores)) < 80.0:
                        continue
                    score = sum(word_scores) / len(word_scores)

                # Chỉ cập nhật nếu điểm cao hơn, hoặc bằng điểm nhưng phòng dài hơn (VD: 'phòng làm việc' > 'phòng')
                if score > best_overall_score or (score == best_overall_score and room_len > best_overall_len):
                    best_overall_score = score
                    best_overall_room = matched_room
                    best_overall_start = start
                    best_overall_len = room_len

        if best_overall_score >= 60.0 and best_overall_room:
            # Special case: nếu match qua 'den ngu' (ví dụ: 'bật đèn ngủ'), giữ lại chữ 'đèn' để intent mapper xử lý tiếp
            matched_slice = text_words_norm[best_overall_start : best_overall_start + best_overall_len]
            if matched_slice == ["den", "ngu"]:
                remaining_words = (
                    text_words_orig[:best_overall_start + 1]
                    + text_words_orig[best_overall_start + best_overall_len:]
                )
            else:
                remaining_words = (
                    text_words_orig[:best_overall_start]
                    + text_words_orig[best_overall_start + best_overall_len:]
                )
            remaining = " ".join(remaining_words)
            remaining = re.sub(r"\s+", " ", remaining).strip()
            remaining = re.sub(r"^(ở|tại|trong|của)\s+", "", remaining).strip()
            remaining = re.sub(r"\s+(ở|tại|trong|của)$", "", remaining).strip()
            
            return best_overall_room, remaining

        return None, text_lower

    def extract_parameters(self, text: str, intent_def: dict) -> tuple[str, dict[str, Any]]:
        """Trích xuất tham số từ câu nói (VD: nhiệt độ, độ sáng).

        Logic phân biệt tuyệt đối vs tương đối:
        - Nhiệt độ 16-30: luôn là tuyệt đối (SET_TEMPERATURE) vì không ai "tăng thêm 26 độ"
        - Nhiệt độ 1-5: tương đối (tăng/giảm thêm X độ)
        - Độ sáng 10-100: tuyệt đối (SET_BRIGHTNESS)
        - Độ sáng 1-9: tương đối (tăng/giảm thêm X mức)
        - Từ khóa "lên/xuống/là/về/thành/đặt/chỉnh/đến/mức" + số: luôn tuyệt đối

        Args:
            text: câu nói gốc
            intent_def: definition của intent

        Returns:
            (action đã chốt, Dict tham số)
        """
        params: dict[str, Any] = {}
        action = intent_def["action"]
        dev_type = intent_def["device_type"]
        text_num = normalize_spoken_numbers(text.lower())

        # Tìm bất kỳ con số nào trong câu
        match = re.search(r"(\d+)", text_num)
        if match:
            value = int(match.group(1))

            # Kiểm tra từ khóa chỉ mức tuyệt đối (lên 26, xuống 24, là 27, về 25, đến mức, lên mức,...)
            has_absolute_keyword = bool(
                re.search(r"(lên|xuống|là|về|thành|đặt|chỉnh|đến|mức|la|ra)[-–—\s]*\d+", text_num)
            )

            if dev_type == "air_conditioner":
                if has_absolute_keyword or (16 <= value <= 30):
                    # Số nằm trong khoảng nhiệt độ điều hòa (16-30°C) → chắc chắn tuyệt đối
                    action = "SET_TEMPERATURE"
                    params["temperature"] = value
                    logger.info("Param: nhiệt độ tuyệt đối → SET_TEMPERATURE=%d°C", value)
                else:
                    # Số nhỏ (1-5) → tương đối (tăng/giảm thêm X độ)
                    params["amount"] = value
                    logger.info("Param: nhiệt độ tương đối → amount=%d", value)

            elif dev_type == "light":
                if action == "SET_BRIGHTNESS" or has_absolute_keyword or (10 <= value <= 100):
                    # Số trong khoảng độ sáng hợp lý (10-100%) hoặc có từ khóa tuyệt đối hoặc intent là SET_BRIGHTNESS → tuyệt đối
                    action = "SET_BRIGHTNESS"
                    params["brightness"] = value
                    logger.info("Param: độ sáng tuyệt đối → SET_BRIGHTNESS=%d", value)
                else:
                    # Số nhỏ (1-9) → tương đối
                    params["amount"] = value
                    logger.info("Param: độ sáng tương đối → amount=%d", value)

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

        # Chuẩn hóa số đọc bằng chữ trước (VD: tám mươi -> 80)
        text_norm_nums = normalize_spoken_numbers(text)

        # Bước 1: Tách room
        room, remaining = self.extract_room(text_norm_nums)

        # Bước 2: Fuzzy match phần còn lại với tất cả patterns
        if not remaining:
            return None
            
        remaining_norm = strip_vietnamese_tones(remaining)
        
        # Tạo danh sách các pattern đã được chuẩn hóa
        all_norm_patterns = [p_norm for _, p_norm, _ in self._pattern_index]

        # Tìm match tốt nhất trên bản chuẩn hóa (Sử dụng WRatio để vừa hỗ trợ biến thể/tham số vừa phản ánh đúng confidence)
        result = process.extractOne(
            remaining_norm,
            all_norm_patterns,
            scorer=fuzz.WRatio,
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
