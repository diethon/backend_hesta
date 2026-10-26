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
    # Ghép tắt đèn ngủ / đèn phòng ngủ
    "den ngu": "phòng ngủ",
    "den led ngu": "phòng ngủ",
    "den rgb ngu": "phòng ngủ",
    "led ngu": "phòng ngủ",
    "rgb ngu": "phòng ngủ",
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


def generate_room_aliases(room_name: str) -> list[tuple[str, str]]:
    """Tạo danh sách các biến thể gọi tên phòng (alias, canonical_name).

    Hỗ trợ:
      - 'Phòng của X' <=> 'Phòng X' (ví dụ: 'phòng của quân' <-> 'phòng quân')
      - Phòng chức năng: 'phòng gaming' <-> 'phòng game', 'phòng karaoke' <-> 'phòng hát'
      - Lặp từ âm học Whisper cho tên riêng Phong: 'phòng phòng' -> 'Phòng của Phong'
    """
    clean = room_name.strip()
    if not clean:
        return []
    clean_lower = clean.lower()
    aliases: list[tuple[str, str]] = [(clean_lower, clean)]

    # 1. Tách phần tên sau 'phòng' hoặc 'phòng của'
    rest = re.sub(r"^(phòng\s+(của\s+)?)", "", clean_lower).strip()

    if clean_lower.startswith("phòng của "):
        short = "phòng " + rest
        aliases.append((short, clean))
    elif clean_lower.startswith("phòng "):
        standard_functional = {
            "khách", "ngủ", "bếp", "tắm", "ăn", "làm việc", "học", "trẻ em",
            "thờ", "sinh hoạt", "chờ", "vệ sinh", "gaming", "game", "karaoke",
            "gym", "cinema"
        }
        if rest not in standard_functional:
            aliases.append(("phòng của " + rest, clean))

    # 2. Xử lý tên riêng Phong bị lặp âm học trong Whisper (phòng phòng / phong phong)
    if "phong" in strip_vietnamese_tones(rest).split():
        aliases.append(("phòng phòng", clean))
        aliases.append(("phong phong", clean))
        aliases.append(("phòng của phòng", clean))

    # 3. Xử lý từ đồng nghĩa / tiếng Anh / phiên âm giải trí
    if "gaming" in clean_lower:
        aliases.append((clean_lower.replace("gaming", "game"), clean))
    elif "game" in clean_lower:
        aliases.append((clean_lower.replace("game", "gaming"), clean))
    if "karaoke" in clean_lower:
        aliases.append((clean_lower.replace("karaoke", "hát"), clean))
    if "chiếu phim" in clean_lower or "xem phim" in clean_lower:
        aliases.append(("phòng cinema", clean))

    return list(dict.fromkeys(aliases))


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
    đồng thời sửa các lỗi âm học phổ biến (như 'bắt' nhầm từ 'tắt', 'r g b' -> 'rgb', 'lét' -> 'led').
    """
    res = text.lower()
    # Sửa lỗi âm học Whisper: 'tắt' bị nhận nhầm thành 'bắt', 'đắt', 'tắc', 'bắc', 'ngắt', 'cắt'
    # Điều này cực kỳ quan trọng vì nếu để 'bắt đèn' bị bỏ dấu thành 'bat den', nó sẽ khớp nhầm với 'bật đèn'!
    res = re.sub(r'\b(bắt|đắt|tắc|bắc|ngắt|cắt)\b(?!\s+đầu\b)', 'tắt', res)
    # Sửa lỗi âm học Whisper: 'là mười' / 'mức mười' bị nghe nhầm thành 'lăm mươi'
    res = re.sub(r'\blăm\s+mươi\b', '10', res)
    # Sửa lỗi âm học Whisper cho LED và RGB
    res = re.sub(r'\b(r\s*g\s*b|r-g-b|r\.g\.b|éc\s*gờ\s*bê|e\s*rờ\s*gờ\s*bê|rờ\s*gờ\s*bê|a\s*pi\s*di)\b', 'rgb', res)
    res = re.sub(r'\b(l\s*e\s*d|l-e-d|l\.e\.d|lét|lết|lép)\b', 'led', res)
    for pat, rep in VI_SPOKEN_NUMBERS:
        res = re.sub(pat, rep, res)
    return res


@dataclass
class IntentResult:
    """Kết quả mapping intent."""
    action: str                    # VD: "TURN_ON"
    device_type: str               # VD: "light" hoặc "led_rgb"
    room: str | None = None        # VD: "phòng khách"
    parameters: dict[str, Any] = field(default_factory=dict)  # VD: {"temperature": 25}
    confidence: float = 0.0        # Điểm fuzzy matching (0-100)
    matched_pattern: str = ""      # Pattern đã khớp
    raw_text: str = ""             # Câu gốc
    device_name_hint: str = ""     # VD: "ngủ" (đèn ngủ), "trần" (đèn trần), "bàn" (đèn bàn)


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
        self._custom_rooms: list[str] = []

    def set_custom_rooms(self, rooms: list[str] | set[str]) -> None:
        """Đăng ký danh sách phòng tùy chỉnh từ Database của ngôi nhà."""
        self._custom_rooms = list(dict.fromkeys(rooms))

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


    def extract_room(self, text: str, custom_rooms: list[str] | None = None) -> tuple[str | None, str]:
        """Tách tên phòng ra khỏi câu nói sử dụng mô hình nhận diện kết hợp 2 tầng (Hybrid Layered).

        Tầng 1: ROOM_PATTERNS mặc định (kiến thức nền tảng: phòng khách, phòng ngủ...).
        Tầng 2: custom_rooms nạp động từ DB (phòng của đức, phòng gaming, phòng của việt...).
        """
        text_lower = text.lower().strip()
        text_norm = strip_vietnamese_tones(text_lower)

        text_words_orig = text_lower.split()
        text_words_norm = text_norm.split()

        # candidate_rooms: norm_alias -> (canonical_room_name, is_dynamic)
        candidate_rooms: dict[str, tuple[str, bool]] = {}

        # 1. Nạp Tầng 1: Các phòng tiêu chuẩn mặc định
        for r in ROOM_PATTERNS:
            candidate_rooms[strip_vietnamese_tones(r.lower())] = (r, False)

        # 2. Nạp Tầng 2: Các phòng động thực tế từ Database của người dùng + sinh aliases
        rooms_to_use = custom_rooms if custom_rooms is not None else self._custom_rooms
        if rooms_to_use:
            for cr in rooms_to_use:
                for alias, canon in generate_room_aliases(cr):
                    norm_alias = strip_vietnamese_tones(alias)
                    # Phòng động từ DB sẽ ghi đè ưu tiên nếu trùng normalized alias
                    candidate_rooms[norm_alias] = (canon, True)

        best_overall_score = 0.0
        best_overall_room = None
        best_overall_start = -1
        best_overall_len = 0
        best_is_dynamic = False

        for norm_room, (original_room, is_dynamic) in candidate_rooms.items():
            room_words = norm_room.split()
            room_len = len(room_words)

            if room_len > len(text_words_norm):
                continue

            for start in range(len(text_words_norm) - room_len + 1):
                window_words = text_words_norm[start : start + room_len]
                window_norm = " ".join(window_words)

                matched_room = original_room
                if window_norm in ROOM_PHONETIC_VARIANTS and not is_dynamic:
                    score = 100.0
                    matched_room = ROOM_PHONETIC_VARIANTS[window_norm]
                elif room_len == 1:
                    score = fuzz.ratio(window_norm, norm_room)
                    if score < 88.0:
                        continue
                else:
                    # Phân xử va chạm âm học: 'phòng làm việc' bắt buộc phải có từ 'lam'
                    # để tránh nhận nhầm khi người dùng nói 'phòng của việt' / 'phòng việt'
                    if norm_room == "phong lam viec" and "lam" not in window_words:
                        continue

                    # Multi-word rooms: mỗi từ phải có độ tương đồng tối thiểu (>= 65%)
                    # và trung bình cộng phải >= 80% để tránh false positive (như 'mở cổng' khớp 'ban công')
                    word_scores = [fuzz.ratio(w, r) for w, r in zip(window_words, room_words)]
                    if any(ws < 65 for ws in word_scores) or (sum(word_scores) / len(word_scores)) < 80.0:
                        continue
                    score = sum(word_scores) / len(word_scores)

                # Tiêu chí chọn match tốt nhất:
                # 1. Điểm số cao hơn
                # 2. Điểm bằng nhau: Ưu tiên phòng thực tế của ngôi nhà (Dynamic Room)
                # 3. Cùng loại: Ưu tiên chuỗi khớp dài hơn (VD: 'phòng làm việc' > 'phòng')
                is_better = False
                if score > best_overall_score:
                    is_better = True
                elif score == best_overall_score:
                    if is_dynamic and not best_is_dynamic:
                        is_better = True
                    elif is_dynamic == best_is_dynamic and room_len > best_overall_len:
                        is_better = True

                if is_better:
                    best_overall_score = score
                    best_overall_room = matched_room
                    best_overall_start = start
                    best_overall_len = room_len
                    best_is_dynamic = is_dynamic

        if best_overall_score >= 60.0 and best_overall_room:
            # Special case: nếu match qua 'den ngu', 'den led ngu'..., giữ lại từ khóa thiết bị để intent mapper xử lý tiếp
            matched_slice = text_words_norm[best_overall_start : best_overall_start + best_overall_len]
            if matched_slice in [["den", "ngu"], ["dem", "ngu"]]:
                remaining_words = (
                    text_words_orig[:best_overall_start + 1]
                    + text_words_orig[best_overall_start + best_overall_len:]
                )
            elif matched_slice in [["den", "phong", "ngu"], ["dem", "phong", "ngu"]]:
                remaining_words = (
                    text_words_orig[:best_overall_start + 1]
                    + text_words_orig[best_overall_start + best_overall_len:]
                )
            elif matched_slice in [["den", "led", "ngu"], ["den", "rgb", "ngu"]]:
                remaining_words = (
                    text_words_orig[:best_overall_start + 2]
                    + text_words_orig[best_overall_start + best_overall_len:]
                )
            elif matched_slice in [["led", "ngu"], ["rgb", "ngu"]]:
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

    def extract_parameters(self, text: str, intent_def: dict, mode_extracted: str | None = None) -> tuple[str, dict[str, Any]]:
        """Trích xuất tham số từ câu nói (VD: nhiệt độ, độ sáng, màu sắc, chế độ).

        Logic phân biệt tuyệt đối vs tương đối:
        - Nhiệt độ 16-30: luôn là tuyệt đối (SET_TEMPERATURE) vì không ai "tăng thêm 26 độ"
        - Nhiệt độ 1-5: tương đối (tăng/giảm thêm X độ)
        - Độ sáng 10-100: tuyệt đối (SET_BRIGHTNESS)
        - Độ sáng 1-9: tương đối (tăng/giảm thêm X mức)
        - Từ khóa "lên/xuống/là/về/thành/đặt/chỉnh/đến/mức" + số: luôn tuyệt đối

        Args:
            text: câu nói gốc
            intent_def: definition của intent
            mode_extracted: tên chế độ / màu đã bóc tách từ trước (nếu có)

        Returns:
            (action đã chốt, Dict tham số)
        """
        params: dict[str, Any] = {}
        action = intent_def["action"]
        dev_type = intent_def["device_type"]
        text_num = normalize_spoken_numbers(text.lower())

        if mode_extracted:
            params["mode"] = mode_extracted.strip()

        # 1. Trích xuất màu sắc cho led_rgb hoặc SET_COLOR
        if dev_type == "led_rgb" or action == "SET_COLOR" or (dev_type == "light" and mode_extracted):
            color_map = {
                "đỏ": (255, 0, 0),
                "do": (255, 0, 0),
                "xanh lá": (0, 255, 0),
                "xanh la": (0, 255, 0),
                "xanh lục": (0, 255, 0),
                "xanh luc": (0, 255, 0),
                "xanh dương": (0, 100, 255),
                "xanh duong": (0, 100, 255),
                "xanh lam": (0, 100, 255),
                "xanh da trời": (0, 191, 255),
                "xanh da troi": (0, 191, 255),
                "xanh biển": (0, 100, 255),
                "xanh bien": (0, 100, 255),
                "xanh ngọc": (0, 255, 255),
                "xanh ngoc": (0, 255, 255),
                "xanh": (0, 100, 255),
                "vàng": (255, 255, 0),
                "vang": (255, 255, 0),
                "cam": (255, 128, 0),
                "tím": (128, 0, 128),
                "tim": (128, 0, 128),
                "hồng": (255, 105, 180),
                "hong": (255, 105, 180),
                "trắng": (255, 255, 255),
                "trang": (255, 255, 255),
            }
            sorted_colors = sorted(color_map.keys(), key=lambda c: len(c), reverse=True)
            text_lower = text.lower()
            for c_name in sorted_colors:
                pattern_c = r'\b' + re.escape(c_name) + r'\b'
                if re.search(pattern_c, text_lower):
                    r, g, b = color_map[c_name]
                    action = "SET_COLOR"
                    params["r"] = r
                    params["g"] = g
                    params["b"] = b
                    params["color"] = c_name
                    logger.info("Param: màu LED → SET_COLOR r=%d, g=%d, b=%d (%s)", r, g, b, c_name)
                    break

        # 2. Tìm bất kỳ con số nào trong câu (nhiệt độ, độ sáng)
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

            elif dev_type in {"light", "led_rgb"}:
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

    def map(self, text: str, custom_rooms: list[str] | None = None) -> IntentResult | None:
        """Map transcript text thành intent.

        Args:
            text: transcript từ STT
            custom_rooms: danh sách phòng tùy chỉnh từ DB (nếu có)

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

        # Bước 1: Tách room (kết hợp danh mục tiêu chuẩn và custom_rooms)
        room, remaining = self.extract_room(text_norm_nums, custom_rooms=custom_rooms)

        if not remaining:
            return None

        # Bước 1.5: Trích xuất chế độ ánh sáng / màu tùy chỉnh (nếu có)
        mode_extracted = None
        # 1. Tìm cụm từ "chế độ / chế đồ <tên>" hoặc "(sang/thành/về) chế độ <tên>"
        mode_match = re.search(
            r"(?:(?:sang|thành|về)\s+)?(?:chế\s+(?:độ|đồ|đọ|do))\s+([a-zA-Z0-9_À-ỹ\s]+?)(?:\s+(?:ở|tại|trong|cho|phòng)\b|$)",
            remaining,
            re.IGNORECASE,
        )
        if not mode_match:
            # 2. Tìm cụm từ chuyển đích: "(sang|thành|về) [màu] <tên>"
            mode_match = re.search(
                r"(?:sang|thành|về)\s+(?:màu\s+)?([a-zA-Z0-9_À-ỹ\s]+?)(?:\s+(?:ở|tại|trong|cho|phòng)\b|$)",
                remaining,
                re.IGNORECASE,
            )
        if not mode_match:
            # 3. Tìm cụm "màu <tên>" không đứng ngay sau động từ đổi/chuyển/chỉnh/đặt
            m_color = re.search(
                r"\bmàu\s+([a-zA-Z0-9_À-ỹ\s]+?)(?:\s+(?:ở|tại|trong|cho|phòng)\b|$)",
                remaining,
                re.IGNORECASE,
            )
            if m_color:
                pre = remaining[:m_color.start()].rstrip().split()
                last_word = pre[-1].lower() if pre else ""
                if last_word not in {"đổi", "doi", "chuyển", "chuyen", "chỉnh", "chinh", "đặt", "dat"}:
                    mode_match = m_color

        remaining_for_match = remaining
        if mode_match:
            candidate_mode = mode_match.group(1).strip()
            if candidate_mode:
                mode_extracted = candidate_mode
                start, end = mode_match.span()
                remaining_for_match = (remaining[:start] + " " + remaining[end:]).strip()
                remaining_for_match = re.sub(r"\s+", " ", remaining_for_match).strip()
                if not remaining_for_match:
                    remaining_for_match = "bật đèn"

        # Bước 2: Fuzzy match phần còn lại với tất cả patterns
        remaining_norm = strip_vietnamese_tones(remaining_for_match)
        
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
        action, params = self.extract_parameters(text, intent_def, mode_extracted=mode_extracted)

        # Bước 4: Trích xuất gợi ý tên thiết bị cụ thể (device_name_hint)
        # Giúp phân biệt chính xác giữa 'đèn ngủ' và 'đèn trần' hoặc 'đèn led'
        device_name_hint = ""
        text_lower = text.lower()
        if intent_def["device_type"] == "light":
            if "đèn ngủ" in text_lower or (re.search(r'\bngủ\b', text_lower) and "phòng ngủ" not in text_lower):
                device_name_hint = "ngủ"
            elif "đèn trần" in text_lower or (re.search(r'\btrần\b', text_lower) and "tầng trệt" not in text_lower):
                device_name_hint = "trần"
            elif "đèn bàn" in text_lower or re.search(r'\bbàn\b', text_lower):
                device_name_hint = "bàn"
            elif "đèn chùm" in text_lower or re.search(r'\bchùm\b', text_lower):
                device_name_hint = "chùm"

        intent_result = IntentResult(
            action=action,
            device_type=intent_def["device_type"],
            room=room,
            parameters=params,
            confidence=score,
            matched_pattern=matched_original_pattern,
            raw_text=text,
            device_name_hint=device_name_hint,
        )

        logger.info(
            "Intent mapped: '%s' → %s %s room=%s hint='%s' params=%s (conf=%.0f%%)",
            text, intent_result.action, intent_result.device_type,
            intent_result.room, intent_result.device_name_hint,
            intent_result.parameters, intent_result.confidence,
        )

        return intent_result
