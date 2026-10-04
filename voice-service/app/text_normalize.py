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
    """Bỏ dấu thanh điệu, giữ nguyên các nguyên âm/phụ âm cấu trúc (ơ, ư, ô, â, ê, ă, đ).
    Dùng để so khớp fuzzy khoan dung hơn với lỗi STT hay gặp (mất dấu thanh).

    Ví dụ: "đèn" -> "đen", "phòng" -> "phong", nhưng "ư" vẫn là "ư" (không về "u").
    """
    if not text:
        return text
    decomposed = unicodedata.normalize("NFD", text.lower())
    stripped = "".join(c for c in decomposed if c not in _TONE_MARKS)
    return unicodedata.normalize("NFC", stripped)
