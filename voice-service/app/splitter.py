"""
HESTA Voice Service — Compound Command Splitter (Mục tách lệnh kép)
Phân tích và tách câu lệnh phức / kép tiếng Việt thành các lệnh đơn lẻ.

Hỗ trợ các dạng câu lệnh:
1. Độc lập đầy đủ:
   "tắt quạt phòng khách và bật đèn phòng ngủ"
   → ["tắt quạt phòng khách", "bật đèn phòng ngủ"]

2. Tỉnh lược động từ (Shared Action Verb / Ellipsis):
   "bật quạt phòng khách và đèn phòng ngủ"
   → ["bật quạt phòng khách", "bật đèn phòng ngủ"]

3. Chung thiết bị khác phòng (Shared Device, Multiple Rooms):
   "tắt đèn phòng khách và phòng ngủ"
   → ["tắt đèn phòng khách", "tắt đèn phòng ngủ"]

4. Chung phòng khác thiết bị (Shared Room, Multiple Devices):
   "bật đèn và quạt phòng khách"
   → ["bật đèn phòng khách", "bật quạt phòng khách"]

5. Kế thừa ngữ cảnh thiết bị cho lệnh tham số (đổi màu, chỉnh nhiệt độ...):
   "bật đèn phòng khách và đổi màu sang đỏ"
   → ["bật đèn phòng khách", "đổi màu sang đỏ phòng khách"]

6. Chaining nhiều mệnh đề:
   "tắt quạt phòng khách, bật đèn phòng ngủ và đóng cửa chính"
   → ["tắt quạt phòng khách", "bật đèn phòng ngủ", "đóng cửa chính"]

Tránh tách sai (False Positives):
- Nối màu sắc / chế độ bằng 'và': "màu xanh và đỏ", "chế độ thư giãn và đọc sách"
- Cụm từ kỹ thuật: "đèn led và rgb"
"""

from __future__ import annotations

import logging
import re
from typing import List, Optional

from app.intent import ROOM_PATTERNS, generate_room_aliases
from app.text_normalize import strip_vietnamese_tones

logger = logging.getLogger(__name__)

# Danh sách liên từ câu lệnh kép theo thứ tự ưu tiên (dài trước, ngắn sau)
CLAUSE_CONJUNCTIONS = [
    r"\s+và\s+rồi\s+",
    r"\s+sau\s+đó\s+",
    r"\s+đồng\s+thời\s+",
    r"\s+cùng\s+lúc\s+",
    r"\s+rồi\s+",
    r"\s+và\s+",
    r",\s+",
    r";\s+",
]

# Các cụm động từ hành động phổ biến trong Smart Home
ACTION_PHRASES = [
    "bật tất cả", "bật hết", "bật toàn bộ",
    "tắt tất cả", "tắt hết", "tắt toàn bộ",
    "bật", "tắt", "mở", "đóng",
    "chỉnh nhiệt độ", "đặt nhiệt độ", "tăng nhiệt độ", "giảm nhiệt độ",
    "chỉnh độ sáng", "đặt độ sáng", "tăng độ sáng", "giảm độ sáng",
    "chỉnh", "đặt", "tăng", "giảm",
    "chuyển chế độ", "đổi chế độ", "chuyển sang", "đổi sang",
    "đổi màu", "chuyển màu", "đổi", "chuyển",
    "khóa", "mở khóa",
    "kéo", "hạ",
]

# Từ khóa thiết bị (sắp xếp dài trước ngắn sau)
DEVICE_KEYWORDS = [
    "bình nóng lạnh", "bình nước nóng", "máy nước nóng",
    "quạt thông gió", "quạt treo tường", "quạt trần", "quạt cây", "quạt",
    "đèn led rgb", "đèn led", "đèn rgb", "đèn ngủ", "đèn trần", "đèn bàn", "đèn chùm", "đèn",
    "máy lạnh", "điều hòa", "máy bơm nước", "máy bơm",
    "rèm cửa", "màn cửa", "rèm",
    "cửa cuốn", "cửa chính", "cửa sổ", "cửa", "cổng",
    "ổ cắm", "tivi", "ti vi", "tv", "fan", "led", "rgb",
    "thiết bị",
]

# Mapping các hành động tham số đặc thù suy ra loại thiết bị
PARAM_ACTION_DEVICES = {
    "đổi màu": "đèn",
    "chuyển màu": "đèn",
    "đổi sang": "đèn",
    "chuyển sang": "đèn",
    "chỉnh nhiệt độ": "điều hòa",
    "đặt nhiệt độ": "điều hòa",
    "tăng nhiệt độ": "điều hòa",
    "giảm nhiệt độ": "điều hòa",
    "tăng độ sáng": "đèn",
    "giảm độ sáng": "đèn",
    "chỉnh độ sáng": "đèn",
    "đặt độ sáng": "đèn",
    "tăng sáng": "đèn",
    "giảm sáng": "đèn",
}

# Các từ khóa chỉ phạm vi toàn nhà (không kế thừa phòng đơn lẻ)
WHOLE_HOUSE_KEYWORDS = {
    "tất cả", "tat ca", "toàn bộ", "toan bo", "cả nhà", "ca nha",
    "hết", "het", "toàn thể"
}


def find_action_verb(text: str) -> Optional[str]:
    """Tìm cụm từ hành động ở đầu câu."""
    t_clean = text.strip().lower()
    for act in ACTION_PHRASES:
        pattern = r"^" + re.escape(act) + r"\b"
        if re.search(pattern, t_clean):
            return act
    return None


def contains_device(text: str) -> bool:
    """Kiểm tra câu có chứa từ khóa thiết bị không."""
    t_clean = text.strip().lower()
    for dev in DEVICE_KEYWORDS:
        pattern = r"\b" + re.escape(dev) + r"\b"
        if re.search(pattern, t_clean):
            return True
    return False


def extract_device_phrase(text: str) -> Optional[str]:
    """Trích xuất từ khóa thiết bị xuất hiện trong câu."""
    t_clean = text.strip().lower()
    for dev in DEVICE_KEYWORDS:
        pattern = r"\b" + re.escape(dev) + r"\b"
        if re.search(pattern, t_clean):
            return dev
    return None


def extract_room_name(text: str, custom_rooms: Optional[List[str]] = None) -> Optional[str]:
    """Kiểm tra và trích xuất tên phòng có trong câu."""
    t_norm = strip_vietnamese_tones(text.lower())

    candidates = list(ROOM_PATTERNS)
    if custom_rooms:
        for cr in custom_rooms:
            candidates.append(cr)
            for alias, _ in generate_room_aliases(cr):
                candidates.append(alias)

    # Sắp xếp dài trước ngắn sau để ưu tiên khớp dài nhất
    candidates.sort(key=lambda x: len(x), reverse=True)
    for c in candidates:
        c_norm = strip_vietnamese_tones(c.lower())
        if re.search(r"\b" + re.escape(c_norm) + r"\b", t_norm):
            return c
    return None


def is_whole_house_clause(text: str) -> bool:
    """Kiểm tra mệnh đề có chứa từ khóa điều khiển cả nhà không."""
    t_clean = text.lower()
    return any(w in t_clean for w in WHOLE_HOUSE_KEYWORDS)


def split_compound_command(text: str, custom_rooms: Optional[List[str]] = None) -> List[str]:
    """Tách câu lệnh phức/kép tiếng Việt thành danh sách các câu lệnh đơn lẻ.

    Args:
        text: Chuỗi câu lệnh gốc từ người dùng hoặc STT.
        custom_rooms: Danh sách các phòng động từ Database của ngôi nhà.

    Returns:
        Danh sách các sub-command độc lập đã được phục hồi đầy đủ ngữ cảnh.
        Nếu không phải câu lệnh kép, trả về danh sách 1 phần tử [text].
    """
    clean_text = re.sub(r"\s+", " ", text).strip()
    if not clean_text:
        return []

    # 1. Chuẩn hóa các cụm từ kỹ thuật hay bị nói chèn chữ 'và'
    # VD: 'đèn led và rgb' -> 'đèn led rgb'
    clean_text = re.sub(r"\b(đèn\s+)?led\s+và\s+rgb\b", r"\1led rgb", clean_text, flags=re.IGNORECASE)

    # 2. Ghép regex tìm các liên từ (non-capturing group để chỉ lấy các mệnh đề)
    split_pattern = r"(?:" + "|".join(CLAUSE_CONJUNCTIONS) + r")"
    tokens = re.split(split_pattern, clean_text, flags=re.IGNORECASE)

    raw_clauses = [t.strip() for t in tokens if t.strip()]

    # Nếu chỉ có 1 phần thực tế thì không phải câu lệnh kép
    if len(raw_clauses) <= 1:
        return [clean_text]

    # Bắt đầu phân tích từ mệnh đề đầu tiên
    first_clause = raw_clauses[0]
    first_action = find_action_verb(first_clause)
    first_device = extract_device_phrase(first_clause)
    first_room = extract_room_name(first_clause, custom_rooms)

    # Nếu mệnh đề đầu tiên hoàn toàn không có action lẫn device -> không phải câu lệnh kép
    if not first_action and not first_device:
        return [clean_text]

    resolved_clauses = [first_clause]
    curr_action = first_action
    curr_device = first_device
    curr_room = first_room

    is_valid_compound = True

    for clause in raw_clauses[1:]:
        c_action = find_action_verb(clause)
        c_device = extract_device_phrase(clause)
        c_room = extract_room_name(clause, custom_rooms)

        # 1. Mệnh đề độc lập đầy đủ (có action + device hoặc room)
        if c_action and (c_device or c_room):
            resolved_clauses.append(clause)
            curr_action = c_action
            if c_device:
                curr_device = c_device
            if c_room:
                curr_room = c_room
            continue

        # 1.5 Hành động tham số đặc thù (đổi màu, chỉnh nhiệt độ...) nhưng chưa có room
        # Kế thừa room từ ngữ cảnh trước đó
        if c_action and c_action in PARAM_ACTION_DEVICES:
            clause_with_ctx = clause
            if not c_room and curr_room:
                clause_with_ctx = f"{clause_with_ctx} {curr_room}"
            resolved_clauses.append(clause_with_ctx)
            curr_action = c_action
            if c_device:
                curr_device = c_device
            continue

        # 2. Tỉnh lược action verb (có device, có thể có room, nhưng thiếu action verb)
        # VD: 'và quạt phòng ngủ' -> action = curr_action ('bật')
        if not c_action and c_device:
            if curr_action:
                new_clause = f"{curr_action} {clause}"
                resolved_clauses.append(new_clause)
                curr_device = c_device
                if c_room:
                    curr_room = c_room
                continue
            else:
                is_valid_compound = False
                break

        # 3. Chung action + device, chỉ khác room (vế sau chỉ có room)
        # VD: 'tắt đèn phòng khách và phòng ngủ' -> 'phòng ngủ' kế thừa 'tắt đèn'
        if not c_action and not c_device and c_room:
            if curr_action and curr_device:
                new_clause = f"{curr_action} {curr_device} {clause}"
                resolved_clauses.append(new_clause)
                curr_room = c_room
                continue
            else:
                is_valid_compound = False
                break

        # Nếu clause này là một từ không chứa device/room/action hợp lệ
        # (VD: màu sắc 'xanh', 'đỏ', chế độ 'thư giãn', số đếm 'ba mươi')
        # -> chứng tỏ chữ 'và' vừa rồi chỉ là nối tính từ / màu sắc!
        is_valid_compound = False
        break

    if not is_valid_compound or len(resolved_clauses) <= 1:
        return [clean_text]

    # Xử lý hậu kỳ: Nếu mệnh đề đầu tiên chưa có room mà mệnh đề sau có room,
    # và giữa chúng là liên từ 'và' (như 'bật đèn và quạt phòng khách')
    if len(resolved_clauses) == 2:
        c1, c2 = resolved_clauses[0], resolved_clauses[1]
        r1 = extract_room_name(c1, custom_rooms)
        r2 = extract_room_name(c2, custom_rooms)
        if not r1 and r2:
            d1 = extract_device_phrase(c1)
            if d1 and not is_whole_house_clause(c1):
                resolved_clauses[0] = f"{c1} {r2}"

    logger.info("Compound command split: '%s' → %s", text, resolved_clauses)
    return resolved_clauses


def combine_command_messages(messages: list[tuple[bool, str]]) -> str:
    """Gộp các thông báo từ các lệnh con thành một câu tiếng Việt tự nhiên.

    Args:
        messages: Danh sách các tuple (success: bool, message: str)

    Returns:
        Câu thông báo tổng hợp bằng tiếng Việt.
    """
    if not messages:
        return ""
    if len(messages) == 1:
        return messages[0][1]

    success_msgs: list[str] = []
    fail_msgs: list[str] = []

    for success, msg in messages:
        clean_m = msg.strip().rstrip(".")
        if success:
            success_msgs.append(clean_m)
        else:
            fail_msgs.append(clean_m)

    if success_msgs and not fail_msgs:
        # Tất cả lệnh con đều thành công: "Đã tắt quạt phòng khách và bật đèn phòng ngủ."
        formatted = []
        for i, m in enumerate(success_msgs):
            if i > 0 and (m.startswith("Đã ") or m.startswith("đã ")):
                formatted.append(m[3:].strip())
            else:
                formatted.append(m)
        if len(formatted) == 2:
            return f"{formatted[0]} và {formatted[1]}."
        else:
            return f"{', '.join(formatted[:-1])} và {formatted[-1]}."

    elif success_msgs and fail_msgs:
        # Thành công một phần: "Đã tắt quạt phòng khách, nhưng không tìm thấy đèn ở phòng ngủ."
        formatted_success = []
        for i, m in enumerate(success_msgs):
            if i > 0 and (m.startswith("Đã ") or m.startswith("đã ")):
                formatted_success.append(m[3:].strip())
            else:
                formatted_success.append(m)

        s_part = ", ".join(formatted_success)
        f_part = ", ".join(fail_msgs)
        f_part_lower = f_part[0].lower() + f_part[1:] if len(f_part) > 1 else f_part.lower()
        return f"{s_part}, nhưng {f_part_lower}."

    else:
        # Tất cả đều thất bại
        if len(fail_msgs) == 2:
            return f"{fail_msgs[0]} và {fail_msgs[1].lower()}."
        return f"{', '.join(fail_msgs)}."

