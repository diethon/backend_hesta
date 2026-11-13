"""
HESTA Voice Service — TTS Matrix Generator (Mục cải tiến tốc độ phản hồi).

Tự động sinh ma trận câu phản hồi nhà thông minh (đơn & kép) cho:
- Danh sách phòng (cố định + tùy chỉnh từ Database).
- Danh sách thiết bị (chuẩn + thiết bị thực tế).
- Các trạng thái thành công và báo lỗi ngoại lệ.
"""

from __future__ import annotations

import logging
from typing import Iterable

logger = logging.getLogger("hesta.tts.matrix")

# Các thiết bị nhà thông minh cốt lõi phổ biến
STANDARD_DEVICES = [
    "đèn",
    "quạt",
    "điều hòa",
    "rèm",
    "cửa",
    "cổng",
    "cổng chính",
    "bình nóng lạnh",
    "máy bơm",
    "tất cả đèn",
    "tất cả thiết bị",
]

# Các phòng cơ bản mặc định
STANDARD_ROOMS = [
    "phòng khách",
    "phòng ngủ",
    "phòng bếp",
    "phòng làm việc",
    "phòng tắm",
    "ban công",
    "sân trước",
    "nhà xe",
]


def generate_phrases_for_room(
    room_name: str,
    device_types: Iterable[str] | None = None,
) -> list[str]:
    """Sinh ma trận câu thoại đơn hoàn chỉnh cho một phòng cụ thể.

    Bao gồm cả câu thành công và câu thông báo lỗi khi thiết bị ngoại tuyến.
    """
    clean_room = room_name.strip()
    if not clean_room:
        return []

    devices = list(device_types) if device_types else ["đèn", "quạt", "điều hòa", "rèm"]
    phrases: list[str] = []

    for dev in devices:
        # 1. Thành công
        phrases.append(f"Đã bật {dev} {clean_room}")
        phrases.append(f"Đã tắt {dev} {clean_room}")

        # 2. Báo lỗi khi chưa kết nối / lỗi thiết bị
        phrases.append(f"Không thể bật {dev} {clean_room} — tất cả thiết bị đều lỗi")
        phrases.append(f"Không thể tắt {dev} {clean_room} — tất cả thiết bị đều lỗi")

    # Thông báo không tìm thấy thiết bị ở phòng đó
    phrases.append(f"Không tìm thấy thiết bị nào ở {clean_room}.")
    phrases.append(f"Không tìm thấy thiết bị nào phù hợp tại {clean_room}.")

    return phrases


def generate_matrix_phrases(
    rooms: Iterable[str] | None = None,
    custom_devices: Iterable[str] | None = None,
    include_compounds: bool = True,
) -> list[str]:
    """Sinh toàn bộ danh sách câu thoại phản hồi thông dụng cho toàn bộ ngôi nhà.

    Args:
        rooms: Danh sách phòng (nếu None sẽ lấy danh sách chuẩn STANDARD_ROOMS).
        custom_devices: Danh sách thiết bị tùy chỉnh (nếu có).
        include_compounds: Có sinh các câu lệnh kép thông dụng giữa các phòng hay không.

    Returns:
        Danh sách các câu tiếng Việt không trùng lặp sẵn sàng cho TTS Pre-warming.
    """
    all_rooms = list(dict.fromkeys(list(rooms or []) + STANDARD_ROOMS))
    devices = list(dict.fromkeys(list(custom_devices or []) + ["đèn", "quạt", "điều hòa"]))

    phrases: list[str] = [
        # Thông báo trạng thái toàn nhà
        "Đã bật tất cả đèn",
        "Đã tắt tất cả đèn",
        "Đã bật tất cả thiết bị",
        "Đã tắt tất cả thiết bị",
        "Đã mở cổng",
        "Đã đóng cổng",
        "Đã mở cổng chính",
        "Đã đóng cổng chính",
        "Đã mở cửa",
        "Đã đóng cửa",
        "Không thể mở cổng — tất cả thiết bị đều lỗi",
        "Không thể đóng cổng — tất cả thiết bị đều lỗi",
        "Không thể mở cổng chính — tất cả thiết bị đều lỗi",
        "Không thể đóng cổng chính — tất cả thiết bị đều lỗi",
        "Không tìm thấy thiết bị nào phù hợp.",
        "Thiết bị chưa sẵn sàng hoặc đang ngoại tuyến.",
        "Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé.",
    ]

    # Sinh câu cho từng phòng
    for r in all_rooms:
        phrases.extend(generate_phrases_for_room(r, devices))

    # Sinh các câu lệnh kép thông dụng giữa 2 phòng lớn nhất (thường là phòng khách và phòng ngủ)
    if include_compounds and len(all_rooms) >= 2:
        r1, r2 = all_rooms[0], all_rooms[1]
        phrases.extend([
            f"Đã tắt quạt {r1} và bật đèn {r2}.",
            f"Đã bật đèn {r1} và tắt quạt {r2}.",
            f"Đã tắt đèn {r1} và tắt đèn {r2}.",
            f"Đã bật đèn {r1} và bật đèn {r2}.",
            f"Đã bật đèn {r1} và bật quạt {r1}.",
            f"Đã tắt đèn {r1} và tắt quạt {r1}.",
            f"Đã bật đèn {r2} và bật quạt {r2}.",
            f"Đã tắt đèn {r2} và tắt quạt {r2}.",
            f"Đã tắt tất cả đèn và tắt quạt {r1}.",
            f"Đã tắt tất cả đèn và tắt điều hòa {r2}.",
        ])

    # Khử trùng lặp và giữ thứ tự
    return list(dict.fromkeys(phrases))
