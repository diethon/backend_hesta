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

    # 3. Xử lý từ đồng nghĩa / tiếng Anh / phiên âm giải trí & phòng ngủ đặc thù
    clean_norm = strip_vietnamese_tones(clean_lower)
    if "gaming" in clean_lower:
        aliases.append((clean_lower.replace("gaming", "game"), clean))
    elif "game" in clean_lower:
        aliases.append((clean_lower.replace("game", "gaming"), clean))
    if "karaoke" in clean_lower:
        aliases.append((clean_lower.replace("karaoke", "hát"), clean))
    if "chiếu phim" in clean_lower or "xem phim" in clean_lower:
        aliases.append(("phòng cinema", clean))

    # Phòng ngủ master — bao phủ các biến thể âm học Web Speech API & Whisper tiếng Việt
    if "master" in clean_norm:
        for variant in [
            "phòng ngủ master",
            "phòng master",
            "phòng ngủ mát tơ",
            "phòng ngủ mắt tơ",
            "phòng ngủ mác tơ",
            "phòng ngủ ma tơ",
            "phòng ngủ mất tơ",
            "phòng ngủ mết tơ",
            "phòng ngủ mắc tơ",
            "phòng ngủ bác tơ",
            "phòng ngủ mặt tơ",
            "phòng ngủ mát",
            "phòng ngủ lớn",
            "phòng ngủ chính",
            "phòng mắt tơ",
            "phòng mát tơ",
            "phòng ngủ masters",
            "ngủ master",
            "ngủ mát tơ",
            "ngủ mắt tơ",
            "master",
            "masters",
            "mát tơ",
            "mắt tơ",
            "mác tơ",
        ]:
            aliases.append((variant, clean))

    # Phòng ngủ của con / Phòng ngủ 2 giường — nhận diện đầy đủ cả hai cách gọi
    if any(k in clean_norm for k in ["2 giuong", "hai giuong", "cua con", "ngu con", "tre em", "cua be"]):
        for variant in [
            "phòng ngủ của con",
            "phòng của con",
            "phòng ngủ con",
            "phòng con",
            "ngủ của con",
            "của con",
            "phòng ngủ 2 giường",
            "phòng ngủ hai giường",
            "phòng 2 giường",
            "phòng hai giường",
            "2 giường",
            "hai giường",
            "phòng ngủ trẻ em",
            "phòng trẻ em",
            "phòng ngủ nhỏ",
            "phòng ngủ bé",
            "phòng của bé",
        ]:
            aliases.append((variant, clean))

    # Phòng bếp — hỗ trợ gọi ngắn gọn 'bếp', 'nhà bếp' (tránh mất room trong lệnh kép như 'tắt đèn bếp rồi mở rèm...')
    if clean_norm in {"phong bep", "bep", "nha bep"}:
        for variant in ["phòng bếp", "nhà bếp", "bếp"]:
            aliases.append((variant, clean))

    # Garden / Sân vườn — hỗ trợ song ngữ Việt-Anh
    if clean_norm in {"garden", "san vuon", "vuon", "ngoai vuon"}:
        for variant in ["sân vườn", "ngoài vườn", "vườn", "garden", "san vuon"]:
            aliases.append((variant, clean))

    # Garage / Gara / Nhà xe — hỗ trợ song ngữ Việt-Anh & phiên âm Whisper
    if clean_norm in {"garage", "gara", "nha xe"}:
        for variant in ["garage", "gara", "ga ra", "ga-ra", "nhà xe"]:
            aliases.append((variant, clean))

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
    # Chuyển số ghép tiếng Việt (11-99, VD: "hai mươi sáu" -> "26", "hai lăm" -> "25", "mười tám" -> "18")
    # TRƯỚC khi đổi hàng chục đơn lẻ ("hai mươi" -> "20") để tránh mất chữ số hàng đơn vị!
    _units = {
        "một": 1, "mốt": 1, "hai": 2, "ba": 3, "bốn": 4, "tư": 4,
        "năm": 5, "lăm": 5, "sáu": 6, "bảy": 7, "bẩy": 7, "tám": 8, "chín": 9,
    }
    _tens = {
        "mười": 10, "hai mươi": 20, "ba mươi": 30, "bốn mươi": 40,
        "năm mươi": 50, "sáu mươi": 60, "bảy mươi": 70, "tám mươi": 80, "chín mươi": 90,
    }
    _unit_pat = "|".join(_units.keys())
    _ten_pat = "|".join(_tens.keys())
    res = re.sub(
        rf"\b({_ten_pat})\s+({_unit_pat})\b",
        lambda m: str(_tens[m.group(1)] + _units[m.group(2)]),
        res,
    )
    # Dạng nói tắt phổ biến cho nhiệt độ điều hòa: "hai lăm độ" -> "25 độ", "hai sáu độ" -> "26 độ"
    res = re.sub(
        rf"\bhai\s+({_unit_pat})(?=\s*độ\b)",
        lambda m: str(20 + _units[m.group(1)]),
        res,
    )
    for pat, rep in VI_SPOKEN_NUMBERS:
        res = re.sub(pat, rep, res)
    return res


@dataclass
class IntentResult:
    """Kết quả mapping intent."""
    action: str                    # VD: "TURN_ON"
    device_type: str | None = None # VD: "light" hoặc "led_rgb" hoặc None nếu lệnh ngữ cảnh
    room: str | None = None        # VD: "phòng khách"
    parameters: dict[str, Any] = field(default_factory=dict)  # VD: {"temperature": 25}
    confidence: float = 0.0        # Điểm fuzzy matching (0-100)
    matched_pattern: str = ""      # Pattern đã khớp
    raw_text: str = ""             # Câu gốc
    device_name_hint: str = ""     # VD: "ngủ" (đèn ngủ), "trần" (đèn trần), "bàn" (đèn bàn)
    scene_query: str = ""          # VD: "1", "chào buổi sáng", "nghỉ trưa" (khi device_type == "scene")


# Danh sách số từ 1-10 và thứ tự dạng chữ tiếng Việt cho tên/số thứ tự kịch bản
_SCENE_WORD_TO_NUM = {
    "một": "1", "mot": "1", "thứ nhất": "1", "thu nhat": "1", "đầu tiên": "1", "dau tien": "1", "số một": "1", "so mot": "1",
    "hai": "2", "thứ hai": "2", "thu hai": "2", "số hai": "2", "so hai": "2",
    "ba": "3", "thứ ba": "3", "thu ba": "3", "số ba": "3", "so ba": "3",
    "bốn": "4", "bon": "4", "tư": "4", "tu": "4", "thứ tư": "4", "thu tu": "4", "thứ bốn": "4", "thu bon": "4",
    "năm": "5", "nam": "5", "thứ năm": "5", "thu nam": "5",
    "sáu": "6", "sau": "6", "thứ sáu": "6", "thu sau": "6",
    "bảy": "7", "bay": "7", "bẩy": "7", "thứ bảy": "7", "thu bay": "7",
    "tám": "8", "tam": "8", "thứ tám": "8", "thu tam": "8",
    "chín": "9", "chin": "9", "thứ chín": "9", "thu chin": "9",
    "mười": "10", "muoi": "10", "thứ mười": "10", "thu muoi": "10",
}

# Biến thể chuẩn (được phép có hoặc không có động từ trước)
_SCENE_STRICT_NOUN = r"(?:kịch\s+bản|kich\s+ban|ngữ\s+cảnh|ngu\s+canh)\b"
# Biến thể nghe nhầm từ STT (BẮT BUỘC phải có động từ điều khiển đi trước để không nhầm câu "kết bạn với tôi đi")
_SCENE_ASR_NOUN = (
    r"(?:kịch\s+bảng|kich\s+bang|kết\s+bảng|ket\s+bang|kích\s+bảng|"
    r"kết\s+bạn|ket\s+ban|kích\s+bản|kịch\s+bạn|kịp\s+bản|kip\s+ban)\b"
)
_SCENE_ANY_NOUN = rf"(?:{_SCENE_STRICT_NOUN}|{_SCENE_ASR_NOUN})"
_SCENE_ON_VERBS = r"(?:bật|bat|mở|mo|chạy|chay|kích\s+hoạt|kich\s+hoat|thực\s+thi|thuc\s+thi|dùng|dung|áp\s+dụng|ap\s+dung|chuyển\s+sang|chuyen\s+sang|đổi\s+sang|doi\s+sang)"
_SCENE_OFF_VERBS = r"(?:tắt|tat|dừng|dung|hủy|huy|ngưng|ngung|thoát|thoat|đóng|dong|ngắt|ngat)"
# Từ đệm lịch sự người Việt hay chèn sau động từ: "bật dùm cái", "mở cho tôi cái", "chạy giúp mình", "tắt hộ anh cái"
_POLITE_FILLERS = r"(?:\s+(?:dùm|dum|giùm|gium|hộ|ho|giúp|giup|cho|tôi|toi|mình|minh|anh|em|chị|chi|cái|cai|phần|phan))*"
_SCENE_TAIL = r"(?:\s+(?:đi|di|lên|len|lại|lai|nhé|nhe|nha|giùm|gium|dùm|dum|hộ|ho|giúp|giup|ngay|luôn|luon|nào|nao|với|voi))*$"

# Các mẫu câu kích hoạt / tắt kịch bản (Scene Commands)
_SCENE_ACTIVATE_RE = re.compile(
    rf"^(?:(?:hãy|hay|xin|làm\s+ơn|lam\s+on|ơi|oi)\s+)?(?:(?:{_SCENE_ON_VERBS}){_POLITE_FILLERS}\s+{_SCENE_ANY_NOUN}|{_SCENE_STRICT_NOUN})\s*(?:số|so|tên|ten|là|la)?\s*(.+?){_SCENE_TAIL}",
    re.IGNORECASE,
)
_SCENE_DEACTIVATE_RE = re.compile(
    rf"^(?:(?:hãy|hay|xin|làm\s+ơn|lam\s+on|ơi|oi)\s+)?(?:{_SCENE_OFF_VERBS}){_POLITE_FILLERS}\s+{_SCENE_ANY_NOUN}\s*(?:số|so|tên|ten|là|la)?\s*(.+?){_SCENE_TAIL}",
    re.IGNORECASE,
)

# Quy tắc lọc từ đệm lịch sự sau động từ cho TẤT CẢ câu lệnh Smart Home (VD: "bật dùm tôi cái đèn" -> "bật đèn")
_STRIP_MID_FILLERS_RE = re.compile(
    r"\b(bật|mở|tắt|đóng|dừng|chạy|kích\s+hoạt|hủy|chỉnh|đặt|tăng|giảm|đổi|chuyển)"
    r"(?:\s+(?:dùm|giùm|hộ|giúp|cho\s+(?:tôi|mình|anh|em|chị)|tôi|mình|anh|em|chị|cái|cây|chiếc|bóng))+\s+",
    re.IGNORECASE,
)

# Truy vấn hệ thống linh hoạt (liệt kê/đếm kịch bản, danh sách phòng, danh sách thiết bị, hủy lệnh)
_QUERY_SIGNAL_RE = re.compile(
    r"\b(?:mấy|may|bao\s+nhiêu|bao\s+nhieu|nào|nao|gì|gi|những|nhung|danh\s+sách|danh\s+sach|"
    r"liệt\s+kê|liet\s+ke|kể\s+tên|ke\s+ten|xem|cho\s+biết|cho\s+biet|hiện\s+có|hien\s+co|hiện\s+tại\s+có|đang\s+có)\b",
    re.IGNORECASE,
)
_CONTROL_VERB_RE = re.compile(
    r"\b(?:bật|bat|tắt|tat|mở|mo|đóng|dong|chạy|chay|kích\s+hoạt|kich\s+hoat|dừng|dung|hủy|huy|ngưng|ngung|chỉnh|chinh|đặt|dat|tăng|tang|giảm|giam|đổi|doi|chuyển|chuyen)\b",
    re.IGNORECASE,
)
_CANCEL_CONTEXT_RE = re.compile(
    r"^(?:hủy\s+lệnh|hủy\s+bỏ|thôi\s+(?:bỏ\s+qua|không\s+cần(?:\s+nữa)?|đừng\s+làm(?:\s+gì)?)|bỏ\s+qua(?:\s+đi)?|đừng\s+làm\s+gì(?:\s+cả)?|không\s+cần\s+nữa)$",
    re.IGNORECASE,
)

# Chặn câu cấm đoán / phủ định trước động từ điều khiển (VD: "đừng bật đèn", "đừng có mở cổng", "không được tắt quạt")
_NEGATION_PROHIBITION_RE = re.compile(
    r"^(?:(?:thôi|này|ơi|hesta\s+ơi)\s+)?(?:đừng(?:\s+có)?|không(?:\s+(?:được|nên|cần|muốn|phải))?|chớ|khoan(?:\s+hãy)?)\s+"
    r"(?:bật|tắt|mở|đóng|chạy|kích\s+hoạt|thực\s+thi|tăng|giảm|đổi|chuyển|đặt|chỉnh|kéo|hạ|nâng|khóa)\b",
    re.IGNORECASE,
)

# Gọt vế dẫn chuyện tự nhiên ở đầu câu (VD: "Trời sáng rồi bật cho tôi kịch bản buổi sáng đi", "Hesta ơi bật đèn phòng khách")
_LEAD_IN_COMMAND_RE = re.compile(
    r"^(.+?)\b((?:bật|tắt|mở|đóng|chạy|kích\s+hoạt|thực\s+thi|dừng|hủy|chỉnh|đặt|tăng|giảm|đổi|chuyển)\s+.+)$",
    re.IGNORECASE,
)

# Các câu nói tự nhiên đời thường → ánh xạ thông minh sang Kịch bản tương ứng (nếu nhà có setup)
_NATURAL_SCENE_TRIGGERS: list[tuple[re.Pattern, str, list[str]]] = [
    (
        re.compile(
            r"^(?:(?:tôi|mình|anh|em|con|tao)\s+)?(?:muốn\s+đi\s+ngủ|đi\s+ngủ(?:\s+(?:thôi|nhé|đây|nha))?|chúc\s+ngủ\s+ngon(?:\s+nhé)?|ngủ\s+ngon(?:\s+nhé)?|tới\s+giờ\s+ngủ(?:\s+rồi)?|đến\s+giờ\s+ngủ(?:\s+rồi)?|buồn\s+ngủ\s+quá)$",
            re.IGNORECASE,
        ),
        "Đi ngủ",
        ["đi ngủ", "chúc ngủ ngon", "ngủ ngon", "ngủ"],
    ),
    (
        re.compile(
            r"^(?:chào\s+buổi\s+sáng(?:\s+nhé)?|(?:tôi|mình|anh|em)\s+(?:dậy\s+rồi|thức\s+dậy\s+rồi)|thức\s+dậy\s+thôi|sáng\s+rồi)$",
            re.IGNORECASE,
        ),
        "Chào buổi sáng",
        ["chào buổi sáng", "buổi sáng", "thức dậy"],
    ),
    (
        re.compile(
            r"^(?:(?:tôi|mình|anh|em)\s+)?(?:muốn\s+)?(?:nghỉ\s+trưa|ngủ\s+trưa)(?:\s+(?:thôi|nhé|đây))?$",
            re.IGNORECASE,
        ),
        "Nghỉ trưa",
        ["nghỉ trưa", "ngủ trưa"],
    ),
    (
        re.compile(
            r"^(?:(?:tôi|mình|anh|em)\s+)?(?:muốn\s+xem\s+phim|xem\s+phim\s+thôi|tối\s+nay\s+xem\s+phim|buổi\s+tối\s+xem\s+phim)$",
            re.IGNORECASE,
        ),
        "Xem phim",
        ["buổi tối xem phim", "xem phim", "chiếu phim", "rạp phim"],
    ),
    (
        re.compile(
            r"^(?:(?:tôi|mình|anh|em|con)\s+)?(?:về\s+nhà\s+rồi|về\s+đến\s+nhà(?:\s+rồi)?|đã\s+về\s+nhà|về\s+rồi)$",
            re.IGNORECASE,
        ),
        "Về nhà",
        ["về nhà", "về đến nhà", "chào mừng về nhà"],
    ),
    (
        re.compile(
            r"^(?:(?:tôi|mình|anh|em|con)\s+)?(?:đi\s+ra\s+ngoài(?:\s+(?:đây|nhé))?|ra\s+khỏi\s+nhà|đi\s+làm(?:\s+(?:đây|nhé))?|ra\s+ngoài\s+đây)$",
            re.IGNORECASE,
        ),
        "Ra ngoài",
        ["ra ngoài", "đi ra ngoài", "rời nhà", "đi làm"],
    ),
]

# Danh từ thiết bị / chức năng KHÔNG được hỗ trợ trong hệ thống Smart Home HESTA
# Giúp chặn ngay từ đầu thay vì để fuzzy matching đoán bừa sang điều hòa / đèn!
_UNSUPPORTED_DOMAIN_RE = re.compile(
    r"\b(?:tu\s+lanh|may\s+giat|may\s+say|lo\s+vi\s+song|noi\s+com|bep\s+tu|bep\s+ga|"
    r"may\s+rua\s+bat|may\s+rua\s+chen|may\s+hut\s+bui|robot\s+hut\s+bui|may\s+loc\s+khong\s+khi|"
    r"may\s+loc\s+nuoc|ban\s+la|ban\s+ui|may\s+in|may\s+tinh|dien\s+thoai|"
    r"mo\s+nhac|bat\s+nhac|phat\s+nhac|nghe\s+nhac|youtube|facebook|zalo|tiktok|google|"
    r"thoi\s+tiet|may\s+gio|bao\s+thuc|nhac\s+nho|goi\s+dien|nhan\s+tin|an\s+gi|uong\s+gi)\b",
    re.IGNORECASE,
)

# Tối thiểu 1 từ khóa thuộc hành động Smart Home hoặc thiết bị Smart Home
# LƯU Ý: KHÔNG đưa 'toi' (trùng 'tôi') hay 'lanh' đơn lẻ vào đây để tránh 'tôi đi ra ngoài' / 'lạnh quá' bị match nhầm!
_SMART_HOME_SIGNAL_RE = re.compile(
    r"\b(?:bat|mo|tat|dong|dung|ngung|chinh|dat|tang|giam|doi|chuyen|keo|ha|nang|khoa|"
    r"den|bong\s+den|anh\s+sang|quat|dieu\s+hoa|may\s+lanh|rem|man|cong|cua|bom|"
    r"tivi|ti\s+vi|tv|led|rgb|o\s+cam|binh\s+nong\s+lanh|nuoc\s+nong|thiet\s+bi|"
    r"ca\s+nha|toan\s+bo|tat\s+ca|nhiet\s+do|do\s+sang|mau|"
    r"kich\s+ban|ngu\s+canh|che\s+do)\b",
    re.IGNORECASE,
)

# Bảng đối chiếu danh từ thiết bị tường minh -> tập device_type hợp lệ (chống lệch loại thiết bị!)
_EXPLICIT_DEVICE_GROUPS: list[tuple[re.Pattern, set[str]]] = [
    (re.compile(r"\b(?:led|rgb)\b"), {"led_rgb", "light"}),
    (re.compile(r"\b(?:den|bong\s+den|anh\s+sang)\b"), {"light", "led_rgb"}),
    (re.compile(r"\b(?:dieu\s+hoa|may\s+lanh|nhiet\s+do)\b"), {"air_conditioner"}),
    (re.compile(r"\bquat\b"), {"fan"}),
    (re.compile(r"\b(?:rem|man\s+cua)\b"), {"curtain"}),
    (re.compile(r"\b(?:cong|cua\s+cuon|cua\s+cuong|cua\s+gara|cua\s+garage)\b"), {"gate"}),
    (re.compile(r"\b(?:khoa\s+cua|cua\s+chinh|cua\s+so)\b"), {"door_lock", "gate"}),
    (re.compile(r"\b(?:may\s+bom|bom\s+nuoc|bom)\b"), {"water_pump"}),
    (re.compile(r"\b(?:binh\s+nong\s+lanh|may\s+nuoc\s+nong|binh\s+nuoc\s+nong)\b"), {"water_heater"}),
    (re.compile(r"\bo\s+cam\b"), {"socket"}),
    (re.compile(r"\b(?:tivi|ti\s+vi|tv)\b"), {"tv", "light"}),
]


# Các mẫu câu hành động ngữ cảnh ngắn (Ellipsis / Pronoun Commands)
# Dùng cho các câu thiếu chủ ngữ/thiết bị phụ thuộc vào DialogueState (VD: "tắt đi", "bật lên", "mở ra", "đóng lại", "dừng lại")
CONTEXT_ACTION_PATTERNS = [
    (
        "TURN_OFF",
        re.compile(
            r"^(?:hay\s+)?(?:tat)(?:\s+(?:no|di|no\s+di|gium|ho|ngay|luon|thoi|lai|het))*\s*(?:di|thoi|nhe|nao)?$"
        ),
    ),
    (
        "TURN_ON",
        re.compile(
            r"^(?:hay\s+)?(?:bat)(?:\s+(?:no|di|len|no\s+len|gium|ho|ngay|luon|thoi|lai))*\s*(?:di|len|thoi|nhe|nao)?$"
        ),
    ),
    (
        "OPEN",
        re.compile(
            r"^(?:hay\s+)?(?:mo|keo\s+ra)(?:\s+(?:no|ra|di|gium|ho|ngay|luon))*\s*(?:ra|di|nhe|nao)?$"
        ),
    ),
    (
        "CLOSE",
        re.compile(
            r"^(?:hay\s+)?(?:dong|keo\s+vao|ha)(?:\s+(?:no|lai|vao|di|gium|ho|ngay|luon))*\s*(?:lai|vao|di|nhe|nao)?$"
        ),
    ),
    (
        "STOP",
        re.compile(
            r"^(?:hay\s+)?(?:dung|ngung)(?:\s+(?:lai|di|thoi|gium|ho|ngay|luon))*\s*(?:lai|di|thoi|nhe|nao)?$"
        ),
    ),
]


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
                if window_norm in {
                    "anh sang", "he thong",
                    "cua cuon", "cua cuong", "cua cong", "cua chinh", "cua so",
                }:
                    continue
                if any(
                    dev_kw in window_norm
                    for dev_kw in ("cua cuon", "cua cuong", "cua cong", "cua chinh", "cua so")
                ):
                    continue
                if norm_room == "cua con" and window_norm != "cua con":
                    continue
                # Chặn động từ hành động đầu câu bị fuzzy-match nhầm thành chữ "phòng" (VD: "đóng" ~ "phòng" = 66.7%!)
                if room_words[0] == "phong" and window_words[0] in {
                    "bat", "tat", "mo", "dong", "keo", "ha", "nang", "dung",
                    "khoa", "chinh", "dat", "tang", "giam", "doi", "chuyen",
                }:
                    continue

                matched_room = original_room
                if window_norm in ROOM_PHONETIC_VARIANTS and not is_dynamic:
                    # Các cụm ghép thiết bị ('den ngu', 'led ngu'...) chỉ làm fallback điểm thấp hơn
                    # để không bao giờ đè lên tên phòng thật dài hơn như 'Phòng ngủ master' / 'Phòng ngủ của con'
                    if window_norm in {"den ngu", "den led ngu", "den rgb ngu", "led ngu", "rgb ngu"}:
                        score = 85.0
                    else:
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

                # Kiểm tra quan hệ bao trùm giữa 2 cửa sổ từ (VD: 'phòng ngủ master' bao trùm 'phòng ngủ')
                new_covers_best = (
                    best_overall_start >= 0
                    and start <= best_overall_start
                    and (start + room_len) >= (best_overall_start + best_overall_len)
                )
                best_covers_new = (
                    best_overall_start >= 0
                    and best_overall_start <= start
                    and (best_overall_start + best_overall_len) >= (start + room_len)
                )

                # Tiêu chí chọn match tốt nhất:
                # 1. Phòng động dài hơn bao trùm phòng generic ngắn hơn (VD: 'Phòng ngủ master' > 'phòng ngủ')
                # 2. Cấm phòng generic ngắn hơn ghi đè phòng động dài hơn đã bao trùm nó
                # 3. Điểm số cao hơn
                # 4. Điểm bằng nhau: Ưu tiên Dynamic Room, sau đó ưu tiên chuỗi dài hơn
                is_better = False
                if best_is_dynamic and not is_dynamic and best_overall_len > room_len and best_covers_new:
                    is_better = False
                elif is_dynamic and not best_is_dynamic and room_len > best_overall_len and new_covers_best and score >= 78.0:
                    is_better = True
                elif is_dynamic and best_is_dynamic and room_len > best_overall_len and new_covers_best and score >= 82.0:
                    is_better = True
                elif is_dynamic and best_is_dynamic and best_overall_len > room_len and best_covers_new and best_overall_score >= 82.0:
                    is_better = False
                elif score > best_overall_score:
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
                is_relative_temp_cmd = (
                    action in {"INCREASE_TEMPERATURE", "DECREASE_TEMPERATURE"}
                    or bool(re.search(r"\b(?:tăng|giảm)\b", text_num))
                )
                if 16 <= value <= 32:
                    # Số nằm trong khoảng nhiệt độ điều hòa (16-32°C) → chắc chắn tuyệt đối
                    action = "SET_TEMPERATURE"
                    params["temperature"] = value
                    logger.info("Param: nhiệt độ tuyệt đối → SET_TEMPERATURE=%d°C", value)
                elif 1 <= value <= 10 and is_relative_temp_cmd:
                    # VD: "tăng nhiệt độ điều hòa lên 2 độ", "giảm đi 1 độ" → TUYỆT ĐỐI giữ tương đối (amount), không đặt 2°C!
                    params["amount"] = value
                    logger.info("Param: nhiệt độ tương đối → amount=%d", value)
                elif has_absolute_keyword:
                    action = "SET_TEMPERATURE"
                    params["temperature"] = value
                    logger.info("Param: nhiệt độ tuyệt đối → SET_TEMPERATURE=%d°C", value)
                else:
                    # Số nhỏ (1-10) → tương đối (tăng/giảm thêm X độ)
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

    @staticmethod
    def _strip_conversational_lead_in(text: str) -> str:
        """Gọt bỏ lời dẫn chuyện / gọi trợ lý ngắn ở đầu câu nếu theo sau là 1 lệnh điều khiển rõ ràng.

        Ví dụ:
          - 'Trời sáng rồi bật cho tôi kịch bản buổi sáng đi' -> 'bật cho tôi kịch bản buổi sáng đi'
          - 'Hesta ơi bật đèn phòng khách đi'                -> 'bật đèn phòng khách đi'
          - 'À quên tắt giúp mình kịch bản nghỉ trưa'        -> 'tắt giúp mình kịch bản nghỉ trưa'
        KHÔNG cắt nếu phần đầu đã là 1 động từ điều khiển hoặc quá dài (> 6 từ).
        """
        raw = re.sub(r"\s+", " ", text).strip()
        m = _LEAD_IN_COMMAND_RE.match(raw)
        if not m:
            return raw
        prefix = m.group(1).strip(" ,;.")
        cmd_part = m.group(2).strip()
        if not prefix or not cmd_part:
            return raw
        # Không cắt nếu prefix chứa động từ điều khiển khác hoặc câu hỏi/phủ định ('đừng có bật', 'không bật')
        if _CONTROL_VERB_RE.search(prefix):
            return raw
        if re.search(r"\b(?:đừng|không|chớ|khoan|đừng\s+có)\b", prefix, re.IGNORECASE):
            return raw
        # Chỉ gọt lời dẫn ngắn hợp lý (<= 6 từ, VD: 'trời sáng rồi', 'hesta ơi', 'tối rồi', 'à quên', 'mình về rồi')
        if len(prefix.split()) <= 6:
            return cmd_part
        return raw

    def _parse_scene_intent(
        self,
        text: str,
        custom_scenes: list[str] | None = None,
    ) -> IntentResult | None:
        """Nhận diện các câu lệnh kích hoạt / tắt kịch bản (Scene Commands).

        Hỗ trợ:
          - 'bật kịch bản 1', 'chạy kịch bản số 2', 'bật kịch bản một'
          - 'bật kịch bản chào buổi sáng', 'kích hoạt kịch bản xem phim'
          - 'bật dùm cái kịch bản đi ngủ', 'Trời sáng rồi bật cho tôi kịch bản buổi sáng đi'
          - 'tắt kịch bản nghỉ trưa', 'dừng kịch bản 1', 'hủy kịch bản đi ngủ'
          - 'bật chế độ chào buổi sáng', 'bật chế độ cho buổi sáng', 'bật cho tôi cái đi ngủ đi'
        """
        raw = self._strip_conversational_lead_in(text.strip())
        if not raw:
            return None

        # Chuẩn hóa lỗi âm học 'bắt/tắc/ngắt' -> 'tắt' nhưng bảo toàn nguyên văn tên kịch bản
        norm_prefix = re.sub(r"^(?:bắt|đắt|tắc|bắc|ngắt|cắt)\b", "tắt", raw, flags=re.IGNORECASE)

        # 1. Kiểm tra tắt / dừng kịch bản trước
        m_off = _SCENE_DEACTIVATE_RE.match(norm_prefix)
        if m_off:
            query = m_off.group(1).strip(" .?!,")
            if query:
                query = _SCENE_WORD_TO_NUM.get(query.lower(), query)
                return IntentResult(
                    action="DEACTIVATE_SCENE",
                    device_type="scene",
                    room=None,
                    parameters={"scene_query": query},
                    confidence=100.0,
                    matched_pattern=f"tắt kịch bản {query}",
                    raw_text=text,
                    scene_query=query,
                )

        # 2. Kiểm tra bật / chạy / kích hoạt kịch bản
        m_on = _SCENE_ACTIVATE_RE.match(norm_prefix)
        if m_on:
            query = m_on.group(1).strip(" .?!,")
            if query:
                query = _SCENE_WORD_TO_NUM.get(query.lower(), query)
                return IntentResult(
                    action="ACTIVATE_SCENE",
                    device_type="scene",
                    room=None,
                    parameters={"scene_query": query},
                    confidence=100.0,
                    matched_pattern=f"bật kịch bản {query}",
                    raw_text=text,
                    scene_query=query,
                )

        # 3. Kiểm tra khớp tên kịch bản thực tế khi người dùng nói:
        #    - 'bật cho tôi cái đi ngủ đi', 'bật chế độ cho buổi sáng', 'bật dùm cái chào buổi sáng'
        #    - Hoặc chỉ nói ngắn gọn tên kịch bản ('đi ngủ', 'cái đi ngủ', 'chào buổi sáng 1')
        if custom_scenes:
            raw_norm = strip_vietnamese_tones(norm_prefix.lower())
            has_device_or_scope_word = bool(
                re.search(
                    r"\b(?:den|led|rgb|dieu\s+hoa|may\s+lanh|quat|rem|cong|cua|bom|tivi|tv|"
                    r"thiet\s+bi|tat\s+ca|toan\s+bo|het|phong)\b",
                    raw_norm,
                )
                or _UNSUPPORTED_DOMAIN_RE.search(raw_norm)
            )
            if not has_device_or_scope_word:
                m_mode = re.match(
                    r"^(?:(?:hay|xin|lam\s+on)\s+)?(bat|mo|chay|kich\s+hoat|chuyen\s+sang|doi\s+sang|tat|dung|huy|thoat|cho)"
                    r"(?:\s+(?:dum|gium|ho|giup|cho|toi|minh|anh|em|chi|cai|phan|che\s+do))*\s+"
                    r"(.+?)(?:\s+(?:di|len|lai|nhe|nha|gium|dum|ho|giup|voi|ngay|luon))*$",
                    raw_norm,
                )
                if m_mode:
                    verb = m_mode.group(1).strip()
                    cand = m_mode.group(2).strip()
                    # Tìm kịch bản khớp tốt nhất (exact -> chứa chuỗi con có ranh giới từ -> fuzzy chặt)
                    best_scene_name: str | None = None
                    best_score = 0.0
                    for scene_name in custom_scenes:
                        s_norm = strip_vietnamese_tones(scene_name.lower()).strip()
                        if not s_norm or len(s_norm) < 4:
                            continue
                        if cand == s_norm:
                            best_scene_name = scene_name
                            best_score = 100.0
                            break
                        if len(cand) >= 4:
                            cand_in_s = bool(re.search(rf"\b{re.escape(cand)}\b", s_norm))
                            s_in_cand = bool(re.search(rf"\b{re.escape(s_norm)}\b", cand)) and (len(s_norm) / max(len(cand), 1) >= 0.45)
                            if cand_in_s or s_in_cand:
                                # Ưu tiên scene có độ dài sát nhất
                                sub_score = 92.0 - min(10.0, abs(len(s_norm) - len(cand)) * 0.5)
                                if sub_score > best_score:
                                    best_score = sub_score
                                    best_scene_name = scene_name
                                continue
                            fscore = max(fuzz.ratio(cand, s_norm), fuzz.token_set_ratio(cand, s_norm) * 0.92)
                            if fscore >= 78.0 and fscore > best_score:
                                best_score = fscore
                                best_scene_name = scene_name

                    if best_scene_name is not None:
                        action = "DEACTIVATE_SCENE" if verb in {"tat", "dung", "huy", "thoat"} else "ACTIVATE_SCENE"
                        return IntentResult(
                            action=action,
                            device_type="scene",
                            room=None,
                            parameters={"scene_query": best_scene_name},
                            confidence=best_score,
                            matched_pattern=f"{verb} kịch bản {best_scene_name}",
                            raw_text=text,
                            scene_query=best_scene_name,
                        )

                # Trường hợp nói ngắn gọn đích danh tên kịch bản (VD: "cái đi ngủ", "chào buổi sáng 1")
                # Nhường các câu cảm thán tự nhiên ('chúc ngủ ngon', 'chào buổi sáng'...) cho Bước 0B xử lý với aliases ưu tiên enabled=True!
                is_natural_trigger = any(pat.match(raw) for pat, _, _ in _NATURAL_SCENE_TRIGGERS)
                if not is_natural_trigger:
                    short_cand = re.sub(
                        r"^(?:cai|phan|che\s+do)\s+",
                        "",
                        re.sub(r"\s+(?:di|nhe|nha|di\s+nhe)$", "", raw_norm).strip(),
                    ).strip()
                    if short_cand:
                        for scene_name in custom_scenes:
                            s_norm = strip_vietnamese_tones(scene_name.lower()).strip()
                            if s_norm and len(s_norm) >= 4 and short_cand == s_norm:
                                return IntentResult(
                                    action="ACTIVATE_SCENE",
                                    device_type="scene",
                                    room=None,
                                    parameters={"scene_query": scene_name},
                                    confidence=92.0,
                                    matched_pattern=f"bật kịch bản {scene_name}",
                                    raw_text=text,
                                    scene_query=scene_name,
                                )

        return None

    def _allowed_device_types(self, remaining_norm: str) -> set[str] | None:
        """Nếu câu có nêu rõ danh từ thiết bị, trả về tập device_type hợp lệ để cấm đoán bừa sang loại khác."""
        allowed: set[str] = set()
        for pat, dtypes in _EXPLICIT_DEVICE_GROUPS:
            if pat.search(remaining_norm):
                allowed.update(dtypes)
        # Nếu câu có chữ 'den' VÀ chữ 'tv'/'tivi' (VD: 'đèn chiếu tivi', 'đèn tv') → chỉ cho phép đèn
        if re.search(r"\bden\b", remaining_norm) and re.search(r"\b(?:tivi|ti\s+vi|tv)\b", remaining_norm):
            return {"light", "led_rgb"}
        # Nếu câu có 'tivi'/'tv' nhưng KHÔNG có 'den' → chỉ cho phép tv
        if re.search(r"\b(?:tivi|ti\s+vi|tv)\b", remaining_norm) and not re.search(r"\bden\b", remaining_norm):
            allowed.discard("light")
        return allowed if allowed else None

    @staticmethod
    def _is_fuzzy_match_trustworthy(
        remaining_norm: str,
        matched_norm_pattern: str,
        wratio_score: float,
        has_explicit_device: bool = False,
        target_device_type: str = "",
        has_param_signal: bool = False,
    ) -> bool:
        """Chống bẫy partial_ratio của fuzz.WRatio khiến câu vô nghĩa/thừa chữ đạt điểm ảo."""
        # Loại bỏ số đo (25 độ, 80%, mức 3) trước khi kiểm tra độ tương đồng cấu trúc
        q_clean = re.sub(r"\b(?:\d+\s*(?:do|phan\s+tram|%|muc)?|do|phan\s+tram|muc|len|xuong|ve|thanh|sang|mau\s+\w+)\b", " ", remaining_norm)
        q_clean = re.sub(r"\s+", " ", q_clean).strip() or remaining_norm
        p_clean = matched_norm_pattern.strip()

        q_words = q_clean.split()
        p_words = p_clean.split()

        # Exact hoặc gần exact
        if q_clean == p_clean or remaining_norm == p_clean:
            return True

        # Nếu câu KHÔNG có danh từ thiết bị rõ ràng và KHÔNG phải lệnh 'all'/tham số
        # (VD: 'đóng cuốn sách' bị partial_ratio nhầm thành 'đóng cổng' 85.5% dù ratio thật chỉ 52%)
        # → yêu cầu fuzz.ratio toàn chuỗi >= 76% (vẫn cho qua lỗi dấu / gõ lệch 1-2 ký tự như 'tắt đền', 'bật quặt')
        if not has_explicit_device and not has_param_signal and target_device_type not in ("all", ""):
            if fuzz.ratio(q_clean, p_clean) < 76.0 and fuzz.token_sort_ratio(q_clean, p_clean) < 76.0:
                return False

        # Câu dài hơn pattern quá nhiều từ lạ (VD: 'hôm nay ăn gì bật thử xem sao' vs 'bật đèn')
        if len(q_words) >= len(p_words) + 3:
            tsort = fuzz.token_sort_ratio(q_clean, p_clean)
            if tsort < 58.0:
                return False

        # Nếu WRatio không quá cao (< 88%), bắt buộc kiểm tra token_set_ratio & ratio chuẩn
        if wratio_score < 88.0:
            tset = fuzz.token_set_ratio(q_clean, p_clean)
            simple_ratio = fuzz.ratio(q_clean, p_clean)
            if tset < 66.0 and simple_ratio < 64.0:
                return False

        return True

    def _parse_conversational_and_comfort_intent(
        self,
        text: str,
        custom_rooms: list[str] | None = None,
    ) -> IntentResult | None:
        """Nhận diện các câu nói tự nhiên đời thường, truy vấn hệ thống và cảm giác môi trường:
        1. Hỏi danh sách kịch bản / danh sách phòng / hủy lệnh.
        2. Câu tự nhiên kích hoạt kịch bản ('tôi muốn đi ngủ', 'chào buổi sáng', 'tôi về nhà rồi'...).
        3. Phản xạ cảm giác môi trường ('nóng quá', 'lạnh quá', 'tối quá', 'đèn sáng quá', 'quạt mạnh quá').
        """
        raw = re.sub(r"\s+", " ", text).strip(" .?!,")
        if not raw:
            return None

        # 1. Truy vấn hệ thống linh hoạt (hỏi kịch bản / hỏi phòng / hỏi thiết bị / hủy lệnh)
        raw_norm = strip_vietnamese_tones(raw.lower())
        has_ctrl_verb = bool(_CONTROL_VERB_RE.search(raw))
        has_query_sig = bool(_QUERY_SIGNAL_RE.search(raw))

        if not has_ctrl_verb and has_query_sig:
            if re.search(r"\b(?:kich\s+ban|ngu\s+canh)\b", raw_norm):
                return IntentResult(
                    action="LIST_SCENES",
                    device_type="system",
                    confidence=100.0,
                    matched_pattern="danh sách kịch bản",
                    raw_text=text,
                )
            if re.search(r"\bthiet\s+bi\b", raw_norm):
                q_room, _ = self.extract_room(raw, custom_rooms=custom_rooms)
                return IntentResult(
                    action="LIST_DEVICES",
                    device_type="system",
                    room=q_room,
                    confidence=100.0,
                    matched_pattern="danh sách thiết bị",
                    raw_text=text,
                )
            if re.search(r"\bphong\b", raw_norm):
                return IntentResult(
                    action="LIST_ROOMS",
                    device_type="system",
                    confidence=100.0,
                    matched_pattern="danh sách phòng",
                    raw_text=text,
                )
        if _CANCEL_CONTEXT_RE.match(raw):
            return IntentResult(
                action="CANCEL_CONTEXT",
                device_type="system",
                confidence=100.0,
                matched_pattern="hủy lệnh",
                raw_text=text,
            )

        # 2. Ngôn ngữ tự nhiên kích hoạt Kịch bản ('tôi muốn đi ngủ', 'chào buổi sáng', 'tôi đi ra ngoài'...)
        for pat, canonical_name, aliases in _NATURAL_SCENE_TRIGGERS:
            if pat.match(raw):
                return IntentResult(
                    action="ACTIVATE_SCENE",
                    device_type="scene",
                    room=None,
                    parameters={
                        "scene_query": canonical_name,
                        "scene_aliases": aliases,
                        "is_natural_trigger": True,
                    },
                    confidence=95.0,
                    matched_pattern=f"kịch bản {canonical_name}",
                    raw_text=text,
                    scene_query=canonical_name,
                )

        # 3. Phản xạ cảm giác môi trường (có thể kèm tên phòng, VD: 'phòng khách nóng quá', 'phòng ngủ tối quá')
        room, rem = self.extract_room(raw, custom_rooms=custom_rooms)
        rem_lower = rem.strip().lower()

        # Lạnh quá / rét quá -> TĂNG nhiệt độ điều hòa thêm 2 độ (KHÔNG bật máy lạnh!)
        if re.match(r"^(?:(?:ở\s+đây|trong\s+phòng|trời|phòng)?\s*)?(?:lạnh\s+quá|rét\s+quá|hơi\s+lạnh|lạnh\s+lắm|lạnh\s+thế)(?:\s+(?:rồi|nhỉ|trời))?$", rem_lower):
            return IntentResult(
                action="INCREASE_TEMPERATURE",
                device_type="air_conditioner",
                room=room,
                parameters={"amount": 2},
                confidence=92.0,
                matched_pattern="lạnh quá",
                raw_text=text,
            )

        # Nóng quá / oi bức quá -> GIẢM nhiệt độ điều hòa đi 2 độ
        if re.match(r"^(?:(?:ở\s+đây|trong\s+phòng|trời|phòng)?\s*)?(?:nóng\s+quá|nực\s+quá|oi\s+bức\s+quá|hơi\s+nóng|nóng\s+lắm|nóng\s+thế)(?:\s+(?:rồi|nhỉ|trời))?$", rem_lower):
            return IntentResult(
                action="DECREASE_TEMPERATURE",
                device_type="air_conditioner",
                room=room,
                parameters={"amount": 2},
                confidence=92.0,
                matched_pattern="nóng quá",
                raw_text=text,
            )

        # Tối quá / tối om -> BẬT đèn (chỉ khớp đúng 'tối' có dấu, tuyệt đối không nhầm 'tôi'!)
        if re.match(r"^(?:(?:ở\s+đây|trong\s+phòng|phòng)?\s*)?(?:tối\s+quá|tối\s+om|tối\s+thế|tối\s+lắm|hơi\s+tối)(?:\s+(?:rồi|nhỉ))?$", rem_lower):
            return IntentResult(
                action="TURN_ON",
                device_type="light",
                room=room,
                parameters={},
                confidence=92.0,
                matched_pattern="tối quá",
                raw_text=text,
            )

        # Đèn sáng quá / chói quá -> GIẢM độ sáng đèn (KHÔNG bật đèn!)
        if re.match(r"^(?:(?:đèn|ở\s+đây|trong\s+phòng|phòng)?\s*)?(?:sáng\s+quá|chói\s+quá|chói\s+mắt\s+quá|hơi\s+chói|hơi\s+sáng)(?:\s+(?:rồi|nhỉ))?$", rem_lower):
            return IntentResult(
                action="DECREASE_BRIGHTNESS",
                device_type="light",
                room=room,
                parameters={"amount": 20},
                confidence=92.0,
                matched_pattern="đèn sáng quá",
                raw_text=text,
            )

        # Quạt mạnh quá / gió mạnh quá -> TẮT quạt (KHÔNG bật quạt!)
        if re.match(r"^(?:quạt|gió)\s+(?:mạnh\s+quá|to\s+quá|ồn\s+quá|lớn\s+quá)(?:\s+(?:rồi|nhỉ))?$", rem_lower):
            return IntentResult(
                action="TURN_OFF",
                device_type="fan",
                room=room,
                parameters={},
                confidence=92.0,
                matched_pattern="quạt mạnh quá",
                raw_text=text,
            )

        return None

    def map(
        self,
        text: str,
        custom_rooms: list[str] | None = None,
        custom_scenes: list[str] | None = None,
    ) -> IntentResult | None:
        """Map transcript text thành intent.

        Args:
            text: transcript từ STT
            custom_rooms: danh sách phòng tùy chỉnh từ DB (nếu có)
            custom_scenes: danh sách tên kịch bản của nhà từ DB (nếu có)

        Returns:
            IntentResult nếu match, None nếu không match hoặc confidence quá thấp.
        """
        if not text or not text.strip():
            return None

        # Bước 0: Chặn câu cấm đoán / phủ định hành động (VD: "đừng bật đèn", "đừng có mở cổng", "không được tắt quạt")
        raw_stripped = re.sub(r"\s+", " ", text).strip(" .?!,")
        if not _CANCEL_CONTEXT_RE.match(raw_stripped) and _NEGATION_PROHIBITION_RE.match(raw_stripped):
            logger.info("Rejected negated/prohibited command: '%s'", text)
            return None

        # Bước 0A: Ưu tiên nhận diện lệnh Kịch bản (Scene) tường minh TRƯỚC khi tách room/device
        # Tránh việc tên kịch bản như 'Đi ngủ' hay 'Xem phim' bị cắt nhầm vào phòng ngủ / TV!
        scene_intent = self._parse_scene_intent(text, custom_scenes=custom_scenes)
        if scene_intent is not None:
            logger.info(
                "Scene intent mapped: '%s' → %s scene_query='%s'",
                text, scene_intent.action, scene_intent.scene_query,
            )
            return scene_intent

        # Bước 0B: Nhận diện ngôn ngữ đời thường (Natural Scene Triggers, Comfort, List Scenes/Rooms, Cancel)
        conv_intent = self._parse_conversational_and_comfort_intent(text, custom_rooms=custom_rooms)
        if conv_intent is not None:
            logger.info(
                "Conversational intent mapped: '%s' → %s %s room=%s",
                text, conv_intent.action, conv_intent.device_type, conv_intent.room,
            )
            return conv_intent

        if not self._pattern_index:
            logger.warning("Chưa load intents! Gọi load_intents() trước.")
            return None

        # Gọt lời dẫn chuyện ngắn ở đầu câu nếu có (VD: 'Hesta ơi bật đèn phòng khách', 'Tối rồi bật đèn đi')
        stripped_lead = self._strip_conversational_lead_in(text)
        # Chuẩn hóa số đọc bằng chữ + loại bỏ từ đệm lịch sự sau động từ (VD: "bật dùm tôi cái đèn" -> "bật đèn")
        cleaned_fillers = _STRIP_MID_FILLERS_RE.sub(r"\1 ", stripped_lead)
        text_norm_nums = normalize_spoken_numbers(cleaned_fillers)

        # Bước 1: Tách room (kết hợp danh mục tiêu chuẩn và custom_rooms)
        full_text_norm = strip_vietnamese_tones(text_norm_nums.lower())

        # Lớp bảo vệ 1: Chặn ngay thiết bị / chức năng KHÔNG hỗ trợ (tủ lạnh, máy giặt, mở nhạc, hỏi giờ...)
        if _UNSUPPORTED_DOMAIN_RE.search(full_text_norm) and "che do" not in full_text_norm:
            logger.info("Từ chối lệnh ngoài phạm vi hỗ trợ (unsupported domain): '%s'", text)
            return None

        is_rolling_or_garage_door = bool(
            re.search(
                r"\b(?:cua cuon|cua cuong|cua gara|cua garage|cong gara|cong garage)\b",
                full_text_norm,
            )
        )
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
            # CHẶN hiểu nhầm "tôi về nhà" / "đi về nhà" thành đổi chế độ đèn!
            if not re.search(r"\b(?:tôi|mình|anh|em|con|đi|trở)\s+về\b|\bvề\s+(?:đến\s+)?nhà\b", remaining, re.IGNORECASE):
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

        # Trường hợp đặc biệt: cửa cuốn / cửa gara (VD: "mở cửa gara", "kéo cửa gara", "đóng cửa cuống")
        # Khi extract_room tách chữ "gara"/"garage" ra khỏi "mở cửa gara", phần còn lại chỉ còn "mở cửa"
        # rất dễ bị match nhầm sang door_lock (TURN_ON/TURN_OFF). Giữ nguyên full_text_norm và chỉ khớp nhóm gate.
        room_norm_check = strip_vietnamese_tones(room.lower()) if room else ""
        if is_rolling_or_garage_door or (
            room_norm_check == "garage" and re.search(r"\b(?:cua|cong)\b", remaining_norm)
        ):
            gate_entries = [
                (orig, p_norm, idef)
                for orig, p_norm, idef in self._pattern_index
                if idef.get("device_type") == "gate"
            ]
            if gate_entries:
                gate_norm_patterns = [p_norm for _, p_norm, _ in gate_entries]
                gate_query = full_text_norm if is_rolling_or_garage_door else f"{remaining_norm} gara"
                gate_res = process.extractOne(gate_query, gate_norm_patterns, scorer=fuzz.WRatio)
                if gate_res is not None and gate_res[1] >= self.config.min_confidence:
                    _, g_score, g_idx = gate_res
                    g_orig, _, g_def = gate_entries[g_idx]
                    g_action, g_params = self.extract_parameters(text, g_def, mode_extracted=mode_extracted)
                    return IntentResult(
                        action=g_action,
                        device_type="gate",
                        room=room,
                        parameters=g_params,
                        confidence=g_score,
                        matched_pattern=g_orig,
                        raw_text=text,
                        device_name_hint="",
                    )

        # Chống nhầm lẫn "bật/tắt toàn bộ đèn" (hoặc quạt/điều hòa/rèm/cổng/bơm/tivi)
        # sang "bật/tắt toàn bộ thiết bị" (device_type == "all"):
        # Khi người dùng đã nêu đích danh loại thiết bị cụ thể và KHÔNG nói "thiết bị",
        # loại trừ các pattern của nhóm "all" khỏi tập ứng viên.
        has_explicit_device_noun = bool(
            re.search(
                r"\b(?:den|bong den|anh sang|quat|dieu hoa|may lanh|rem|man cua|cong|cua cuon|cua cuong|cua gara|cua garage|may bom|bom nuoc|tivi|ti vi|tv|led|rgb|khoa cua|o cam|binh nong lanh)\b",
                remaining_norm,
            )
        )
        mentions_all_devices_word = bool(re.search(r"\bthiet bi\b", remaining_norm))

        # Nhận diện các câu lệnh thuần túy hành động / ngữ cảnh ngắn (Ellipsis / Pronoun Commands)
        # Ví dụ: "tắt đi", "tắt nó đi", "bật lên", "mở ra", "đóng lại", "dừng lại",
        # hoặc lệnh tiếp nối theo tham số ("đặt 24 độ", "tăng thêm 2 độ", "giảm đi 1 độ", "đổi sang màu xanh dương")
        if not has_explicit_device_noun and not mentions_all_devices_word:
            # 1. Lệnh tiếp nối nhiệt độ (VD: "đặt 24 độ", "xuống 23 độ", "tăng thêm 2 độ", "giảm đi 1 độ")
            m_temp_set = re.match(
                r"^(?:dat|chinh|ha|giam|tang|len|xuong|ve|de)?\s*(?:nhiet\s+do\s+)?(?:ve|xuong|len|thanh|la|muc)?\s*(\d{2})\s*do(?:\s*c)?(?:\s+(?:di|nhe|nha))?$",
                remaining_norm,
            )
            if m_temp_set:
                t_val = int(m_temp_set.group(1))
                if 16 <= t_val <= 32:
                    return IntentResult(
                        action="SET_TEMPERATURE",
                        device_type="air_conditioner",
                        room=room,
                        parameters={"temperature": t_val},
                        confidence=96.0,
                        matched_pattern=f"đặt {t_val} độ",
                        raw_text=text,
                    )
            m_temp_rel = re.match(
                r"^(tang|giam|ha)\s*(?:nhiet\s+do\s+)?(?:them|len|xuong|di|bot)?\s*(\d+)?\s*do(?:\s+(?:di|nhe|nha))?$",
                remaining_norm,
            )
            if m_temp_rel:
                act_rel = "INCREASE_TEMPERATURE" if m_temp_rel.group(1) == "tang" else "DECREASE_TEMPERATURE"
                amt = int(m_temp_rel.group(2)) if m_temp_rel.group(2) else 1
                return IntentResult(
                    action=act_rel,
                    device_type="air_conditioner",
                    room=room,
                    parameters={"amount": amt},
                    confidence=96.0,
                    matched_pattern=remaining_for_match,
                    raw_text=text,
                )

            # 2. Lệnh tiếp nối đổi màu đèn LED RGB (VD: "đổi sang màu xanh dương", "chuyển sang màu đỏ")
            if mode_extracted and remaining_norm in {"doi", "chuyen", "chinh", "dat", "sang", "bat", "bật đèn"}:
                c_action, c_params = self.extract_parameters(text, {"action": "SET_COLOR", "device_type": "led_rgb"}, mode_extracted=mode_extracted)
                if "r" in c_params:
                    # Người dùng nói tên màu trực tiếp ("xanh dương", "đỏ"...) -> bỏ mode để không ghi đè sang preset khác
                    c_params.pop("mode", None)
                    return IntentResult(
                        action="SET_COLOR",
                        device_type="led_rgb",
                        room=room,
                        parameters=c_params,
                        confidence=95.0,
                        matched_pattern=f"đổi màu {c_params.get('color', mode_extracted)}",
                        raw_text=text,
                    )

        if not has_explicit_device_noun and not mentions_all_devices_word and not mode_extracted:
            for ctx_action, ctx_regex in CONTEXT_ACTION_PATTERNS:
                if ctx_regex.match(remaining_norm):
                    logger.info("Khớp lệnh ngữ cảnh ngắn: '%s' -> action=%s, device_type=None", remaining_norm, ctx_action)
                    return IntentResult(
                        action=ctx_action,
                        device_type=None,
                        room=room,
                        parameters={},
                        confidence=100.0,
                        matched_pattern=remaining_for_match,
                        raw_text=text,
                        device_name_hint="",
                    )

        # Nếu người dùng nói "bật chế độ <X>" không nêu tên đèn và không khớp kịch bản nào ở Bước 0 ->
        # chỉ chạy tiếp sang đèn nếu câu có chữ 'đèn'/'led'/'rgb', ngược lại từ chối sạch thay vì báo 'Không xác định được loại thiết bị'
        if mode_extracted and not has_explicit_device_noun and remaining_norm in {"bat", "mo", "tat", "doi", "chuyen", "chinh", "dat"}:
            logger.info("Từ chối lệnh chế độ mơ hồ không khớp kịch bản và không nêu thiết bị: '%s'", text)
            return None

        # Lớp bảo vệ 2: Câu nói hoàn toàn không có tín hiệu hành động hay thiết bị Smart Home (vô nghĩa/xã giao)
        # Kiểm tra bổ sung chữ 'tối' / 'sáng' CÓ DẤU (tuyệt đối không để chữ 'tôi' -> 'toi' lọt qua!)
        has_accented_brightness_signal = bool(re.search(r"\b(?:tối\s+đi|sáng\s+lên|bớt\s+sáng|bật\s+sáng|tắt\s+sáng|nóng\s+lên|lạnh\s+hơn)\b", text.lower()))
        if not _SMART_HOME_SIGNAL_RE.search(remaining_norm) and not _SMART_HOME_SIGNAL_RE.search(full_text_norm) and not has_accented_brightness_signal:
            logger.info("Từ chối câu không chứa tín hiệu điều khiển Smart Home: '%s'", text)
            return None

        # Loại trừ nhóm device_type == 'scene' khỏi fuzzy match thiết bị (scene đã được xử lý riêng ở Bước 0)
        base_entries = [
            (orig, p_norm, idef)
            for orig, p_norm, idef in self._pattern_index
            if idef.get("device_type") != "scene"
        ]

        if has_explicit_device_noun and not mentions_all_devices_word:
            candidate_entries = [
                (orig, p_norm, idef)
                for orig, p_norm, idef in base_entries
                if idef.get("device_type") != "all"
            ]
        else:
            candidate_entries = base_entries

        # Lớp bảo vệ 3: Nếu câu có danh từ thiết bị rõ ràng (VD: 'đèn', 'quạt', 'điều hòa'),
        # BẮT BUỘC chỉ so khớp trong nhóm device_type tương thích → cấm đoán bừa sang thiết bị khác!
        allowed_types = self._allowed_device_types(remaining_norm)
        if allowed_types is not None and not mentions_all_devices_word:
            typed_entries = [
                (orig, p_norm, idef)
                for orig, p_norm, idef in candidate_entries
                if idef.get("device_type") in allowed_types
            ]
            if typed_entries:
                candidate_entries = typed_entries
            else:
                logger.info(
                    "Không có intent nào thuộc nhóm thiết bị %s cho câu '%s' → từ chối đoán bừa.",
                    allowed_types, text,
                )
                return None

        # Tạo danh sách các pattern đã được chuẩn hóa
        all_norm_patterns = [p_norm for _, p_norm, _ in candidate_entries]

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
        matched_original_pattern, _, intent_def = candidate_entries[idx]

        if score < self.config.min_confidence:
            logger.info(
                "Confidence quá thấp: '%s' (norm: '%s') → '%s' (%.0f%% < %.0f%%)",
                remaining, remaining_norm, matched_original_pattern, score, self.config.min_confidence,
            )
            return None

        # Lớp bảo vệ 4: Kiểm tra chất lượng fuzzy match (chống bẫy partial_ratio trên câu vô nghĩa dài/lệch danh từ)
        has_param_signal = bool(
            mode_extracted
            or intent_def.get("has_parameter")
            or re.search(r"\b(?:nhiet\s+do|do\s+sang|mau|sang|toi|nong|lanh|mat|am)\b", remaining_norm)
        )
        if not self._is_fuzzy_match_trustworthy(
            remaining_norm,
            matched_norm_pattern,
            score,
            has_explicit_device=bool(allowed_types),
            target_device_type=intent_def.get("device_type", ""),
            has_param_signal=has_param_signal,
        ):
            logger.info(
                "Từ chối fuzzy match kém chất lượng (anti-hallucination): '%s' → '%s' (WRatio=%.0f%%)",
                remaining, matched_original_pattern, score,
            )
            return None

        # Bước 3: Trích xuất tham số
        action, params = self.extract_parameters(text, intent_def, mode_extracted=mode_extracted)

        # Bước 4: Trích xuất gợi ý tên thiết bị cụ thể (device_name_hint)
        # Giúp phân biệt chính xác giữa 'đèn chiếu tv', 'đèn ngủ', 'đèn trần', 'đèn bàn'
        device_name_hint = ""
        text_lower = text.lower()
        if intent_def["device_type"] == "light":
            if re.search(r'\b(?:chiếu\s+(?:tv|tivi|ti\s*vi)|chieu\s+(?:tv|tivi)|rọi\s+(?:tv|tivi)|roi\s+(?:tv|tivi)|hắt\s+(?:tv|tivi)|sau\s+(?:tv|tivi)|đèn\s+(?:tv|tivi)|den\s+(?:tv|tivi))\b', text_lower):
                device_name_hint = "chiếu tv"
            elif "đèn ngủ" in text_lower or (re.search(r'\bngủ\b', text_lower) and "phòng ngủ" not in text_lower):
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
