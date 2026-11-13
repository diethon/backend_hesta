import unicodedata

# Chỉ 5 dấu THANH điệu (không phải dấu cấu trúc nguyên âm như ơ/ư/ô/â/ê/ă)
_TONE_MARKS = {
    "\u0301",  # sắc
    "\u0300",  # huyền
    "\u0309",  # hỏi
    "\u0303",  # ngã
    "\u0323",  # nặng
}

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
