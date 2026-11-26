import re
import unicodedata

# Chỉ 5 dấu THANH điệu (không phải dấu cấu trúc nguyên âm như ơ/ư/ô/â/ê/ă)
_TONE_MARKS = {
    "\u0301",  # sắc
    "\u0300",  # huyền
    "\u0309",  # hỏi
    "\u0303",  # ngã
    "\u0323",  # nặng
}

# Các quy tắc tự động sửa lỗi nhận diện âm học STT tiếng Việt (Web Speech API & Whisper)
# Dùng để chuẩn hóa đẹp câu hiển thị trên giao diện (transcript) và lịch sử lệnh.
_TRANSCRIPT_CORRECTIONS: list[tuple[re.Pattern, str]] = [
    # "bật kết bạn ..." / "bật dùm cái kết bạn ..." / "bật kịch bảng ..." -> "... kịch bản ..."
    (
        re.compile(
            r"\b(bật|mở|chạy|kích\s+hoạt|thực\s+thi|áp\s+dụng|chuyển\s+sang|đổi\s+sang|tắt|dừng|hủy|ngưng|thoát|đóng)"
            r"((?:\s+(?:dùm|giùm|hộ|giúp|cho|tôi|mình|anh|em|cái))*)\s+"
            r"(?:kết\s+bạn|kích\s+bản|kịch\s+bảng|kết\s+bảng|kích\s+bảng|kịch\s+bạn|kịp\s+bản)\b",
            re.IGNORECASE,
        ),
        r"\1\2 kịch bản",
    ),
    # "kịch bảng" / "kích bản" đứng độc lập
    (re.compile(r"\b(?:kịch\s+bảng|kích\s+bản|kết\s+bảng|kích\s+bảng|kịp\s+bản)\b", re.IGNORECASE), "kịch bản"),
    # "tắc đèn" / "bắt đèn" / "ngắt quạt" -> "tắt ..."
    (
        re.compile(
            r"\b(?:tắc|bắt|đắt|bắc)\s+(đèn|quạt|điều\s+hòa|máy\s+lạnh|tivi|ti\s+vi|tv|rèm|cổng|máy\s+bơm|kịch\s+bản|tất\s+cả|toàn\s+bộ|cả\s+nhà)\b",
            re.IGNORECASE,
        ),
        r"tắt \1",
    ),
    # "phòng ngủ mắt tơ / mát tơ / mác tơ" -> "phòng ngủ master"
    (
        re.compile(r"\b(phòng\s+ngủ|phòng|đèn\s+ngủ|đèn)\s+(?:mắt\s+tơ|mát\s+tơ|mác\s+tơ|ma\s+tơ|mất\s+tơ|mết\s+tơ|mắc\s+tơ|bác\s+tơ|mặt\s+tơ)\b", re.IGNORECASE),
        r"\1 master",
    ),
    # "cửa cuống" -> "cửa cuốn"
    (re.compile(r"\bcửa\s+cuống\b", re.IGNORECASE), "cửa cuốn"),
    # "đèn lét / lết / lép" -> "đèn LED"
    (re.compile(r"\bđèn\s+(?:lét|lết|lép)\b", re.IGNORECASE), "đèn LED"),
    # "chế đồ" -> "chế độ"
    (re.compile(r"\bchế\s+đồ\b", re.IGNORECASE), "chế độ"),
]


def polish_transcript(text: str) -> str:
    """Chuẩn hóa câu transcript hiển thị cho người dùng (khắc phục lỗi STT nghe nhầm như 'bật kết bạn' -> 'bật kịch bản')."""
    if not text:
        return text
    cleaned = re.sub(r"\s+", " ", text).strip()
    for pat, repl in _TRANSCRIPT_CORRECTIONS:
        cleaned = pat.sub(repl, cleaned)
    return cleaned


def strip_vietnamese_tones(text: str) -> str:
    """Loại bỏ hoàn toàn dấu thanh và dấu nguyên âm (đưa về ASCII cơ bản).
    Dùng để so khớp fuzzy cực kỳ khoan dung với lỗi STT (khi STT đoán sai cả nguyên âm gốc).
    Ví dụ: "khắp" -> "khap", "khách" -> "khach" (so khớp rất tốt so với "tắm" -> "tam").
    """
    if not text:
        return text
    # Normalize to NFD to separate base characters and diacritics
    decomposed = unicodedata.normalize("NFD", text.lower())
    # Remove all diacritics (Nonspacing Marks)
    stripped = "".join(c for c in decomposed if unicodedata.category(c) != 'Mn')
    # Replace special 'đ'
    return stripped.replace("đ", "d").replace("Đ", "d")
