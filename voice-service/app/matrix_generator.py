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
    "rèm cửa",
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

    Bao gồm cả câu thành công và câu thông báo lỗi khi phân quyền, thiết bị ngoại tuyến, gateway, timeout.
    """
    clean_room = room_name.strip()
    if not clean_room:
        return []

    devices = list(device_types) if device_types else ["đèn", "quạt", "điều hòa", "rèm", "rèm cửa", "cửa", "cổng", "cổng chính", "cửa cuốn"]
    phrases: list[str] = []

    for dev in devices:
        is_open_close = any(k in dev.lower() for k in ["cửa", "cổng", "rèm"])
        actions = [("mở", "đóng")] if is_open_close else [("bật", "tắt")]
        if any(k in dev.lower() for k in ["rèm"]):
            actions.append(("bật", "tắt"))
            actions.append(("kéo", "hạ"))

        for act_on, act_off in actions:
            # 1. Thành công
            phrases.append(f"Đã {act_on} {dev} {clean_room}")
            phrases.append(f"Đã {act_off} {dev} {clean_room}")

            # 2. Báo lỗi phân quyền RBAC
            phrases.append(f"Bạn không có quyền {act_on} {dev} {clean_room}.")
            phrases.append(f"Bạn không có quyền {act_off} {dev} {clean_room}.")

            # 3. Báo lỗi Gateway MQTT
            phrases.append(f"Không thể kết nối đến Gateway để {act_on} {dev} {clean_room}.")
            phrases.append(f"Không thể kết nối đến Gateway để {act_off} {dev} {clean_room}.")

            # 4. Báo lỗi không phản hồi (Timeout)
            phrases.append(f"Không thể {act_on} {dev} {clean_room} — thiết bị không phản hồi.")
            phrases.append(f"Không thể {act_off} {dev} {clean_room} — thiết bị không phản hồi.")
            phrases.append(f"Không thể {act_on} {dev} {clean_room} — tất cả thiết bị đều lỗi")
            phrases.append(f"Không thể {act_off} {dev} {clean_room} — tất cả thiết bị đều lỗi")

        # 5. Báo lỗi ngoại tuyến / mất kết nối
        phrases.append(f"{dev.capitalize()} {clean_room} hiện đang ngoại tuyến hoặc chưa kết nối.")
        phrases.append(f"{dev.capitalize()} {clean_room} hiện đang mất kết nối.")
        phrases.append(f"{dev.capitalize()} {clean_room} không phản hồi.")
        phrases.append(f"Có lỗi xảy ra khi điều khiển {dev} {clean_room}.")

    # Thông báo không tìm thấy thiết bị ở phòng đó
    phrases.append(f"Không tìm thấy thiết bị nào ở {clean_room}.")
    phrases.append(f"Không tìm thấy thiết bị nào phù hợp tại {clean_room}.")
    for dev in devices:
        phrases.append(f"Không tìm thấy {dev} nào ở {clean_room}.")

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
    all_rooms = list(dict.fromkeys(
        list(rooms or [])
        + STANDARD_ROOMS
        + ["phòng của quân", "phòng ngủ của con", "phòng ngủ master", "garage", "garden"]
    ))
    devices = list(dict.fromkeys(
        list(custom_devices or [])
        + ["đèn", "quạt", "điều hòa", "đèn led", "đèn ngủ", "đèn led rgb", "cổng", "cổng chính", "cửa cuốn", "máy bơm nước"]
    ))

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
        "Đã mở cửa cuốn",
        "Đã đóng cửa cuốn",
        "Không thể mở cổng — tất cả thiết bị đều lỗi",
        "Không thể đóng cổng — tất cả thiết bị đều lỗi",
        "Không thể mở cổng chính — tất cả thiết bị đều lỗi",
        "Không thể đóng cổng chính — tất cả thiết bị đều lỗi",
        "Không thể mở cổng — thiết bị không phản hồi.",
        "Không thể đóng cổng — thiết bị không phản hồi.",
        "Không thể mở cổng chính — thiết bị không phản hồi.",
        "Không thể đóng cổng chính — thiết bị không phản hồi.",
        "Cổng hiện đang ngoại tuyến hoặc chưa kết nối.",
        "Cổng chính hiện đang ngoại tuyến hoặc chưa kết nối.",
        "Cổng không phản hồi.",
        "Cổng chính không phản hồi.",
        "Không thể kết nối đến Gateway để mở cổng.",
        "Không thể kết nối đến Gateway để đóng cổng.",
        "Không thể kết nối đến Gateway để mở cổng chính.",
        "Không thể kết nối đến Gateway để đóng cổng chính.",
        "Bạn không có quyền mở cổng.",
        "Bạn không có quyền đóng cổng.",
        "Bạn không có quyền mở cổng chính.",
        "Bạn không có quyền đóng cổng chính.",
        "Bạn không có quyền điều khiển thiết bị này.",
        "Bạn chưa đăng nhập hoặc không có quyền điều khiển thiết bị.",
        "Thiết bị hiện đang ngoại tuyến hoặc chưa kết nối.",
        "Không thể kết nối đến Gateway thiết bị.",
        "Không tìm thấy thiết bị nào phù hợp.",
        "Thiết bị chưa sẵn sàng hoặc đang ngoại tuyến.",
        "Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé.",
        # Lệnh kép phổ biến và lỗi kết hợp
        "Không thể bật đèn phòng của quân — thiết bị không phản hồi và không thể mở cổng — thiết bị không phản hồi.",
        "Không thể bật đèn phòng của quân — thiết bị không phản hồi và không thể mở cổng chính — thiết bị không phản hồi.",
        "Đã bật đèn phòng của quân và mở cổng chính.",
        "Đã bật đèn phòng của quân và mở cổng.",
        "Đã tắt đèn phòng của quân và đóng cổng chính.",
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


def generate_phrases_for_backend_devices(devices: Iterable[dict]) -> list[str]:
    """
    Sinh danh sách câu phản hồi chuẩn xác 100% khớp với dispatcher.py
    dựa trên danh sách thiết bị và phòng THỰC TẾ từ Backend (/internal/devices).

    Chỉ sinh đúng các câu thật sự có thể phát sinh trong ngôi nhà (kể cả số lượng N thiết bị
    trong cùng 1 phòng, rèm 2 mảnh, điều hòa, LED RGB, và các câu báo lỗi RBAC/Offline/Gateway).
    """
    from collections import Counter

    type_map: dict[str, tuple[list[str], list[tuple[str, str]]]] = {
        "light": (["đèn"], [("bật", "tắt")]),
        "led_rgb": (["đèn RGB", "đèn LED"], [("bật", "tắt")]),
        "led": (["đèn LED", "đèn RGB"], [("bật", "tắt")]),
        "fan": (["quạt"], [("bật", "tắt")]),
        "air_conditioner": (["điều hòa"], [("bật", "tắt")]),
        "ac": (["điều hòa"], [("bật", "tắt")]),
        "curtain": (["rèm", "rèm cửa"], [("mở", "đóng"), ("bật", "tắt")]),
        "gate": (["cổng"], [("mở", "đóng")]),
        "rolling_door": (["cổng"], [("mở", "đóng")]),
        "door": (["khóa cửa"], [("bật", "tắt"), ("mở", "đóng")]),
        "door_lock": (["khóa cửa"], [("bật", "tắt"), ("mở", "đóng")]),
        "water_pump": (["máy bơm nước"], [("bật", "tắt")]),
        "water_heater": (["bình nóng lạnh"], [("bật", "tắt")]),
        "tv": (["tivi"], [("bật", "tắt")]),
        "socket": (["ổ cắm"], [("bật", "tắt")]),
    }

    room_type_counts: Counter[tuple[str, str]] = Counter()
    house_type_counts: Counter[str] = Counter()
    type_actions: dict[str, list[tuple[str, str]]] = {}
    has_ac_rooms: set[str] = set()
    has_curtain_rooms: set[str] = set()
    total_controllable = 0

    for dev in devices:
        raw_type = str(dev.get("deviceType") or dev.get("type") or "").strip().lower()
        if not raw_type or "sensor" in raw_type or "camera" in raw_type:
            continue
        room = str(dev.get("roomName") or dev.get("room") or "").strip()
        name_lower = str(dev.get("name") or "").lower()

        spec = type_map.get(raw_type)
        if not spec:
            continue

        total_controllable += 1
        type_names, actions = spec
        expanded_names = list(type_names)
        if raw_type == "light" and "ngủ" in name_lower and "đèn ngủ" not in expanded_names:
            expanded_names.append("đèn ngủ")

        for tv in expanded_names:
            type_actions[tv] = actions
            house_type_counts[tv] += 1
            if room:
                room_type_counts[(room, tv)] += 1

        if raw_type in ("air_conditioner", "ac"):
            has_ac_rooms.add(room)
        if raw_type == "curtain":
            has_curtain_rooms.add(room)

    phrases: list[str] = [
        "Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé.",
        "Không tìm thấy thiết bị nào phù hợp.",
        "Thiết bị hiện đang ngoại tuyến hoặc chưa kết nối.",
        "Bạn không có quyền điều khiển thiết bị này.",
        "Đã bật tất cả thiết bị",
        "Đã tắt tất cả thiết bị",
    ]
    if total_controllable > 1:
        phrases.extend([
            f"Đã bật tất cả {total_controllable} thiết bị trong nhà",
            f"Đã tắt tất cả {total_controllable} thiết bị trong nhà",
            "Thiết bị trong nhà hiện đang ngoại tuyến hoặc chưa kết nối.",
            "Bạn không có quyền bật thiết bị trong nhà.",
            "Bạn không có quyền tắt thiết bị trong nhà.",
        ])

    def _append_group(type_vi: str, room_str: str, count: int, actions: list[tuple[str, str]]) -> None:
        phrases.append(f"{type_vi.capitalize()}{room_str} hiện đang ngoại tuyến hoặc chưa kết nối.")
        for act_on, act_off in actions:
            for act in (act_on, act_off):
                phrases.append(f"Đã {act} {type_vi}{room_str}")
                if count > 1:
                    phrases.append(f"Đã {act} {count} {type_vi}{room_str}")
                    phrases.append(f"Đã {act} tất cả {count} {type_vi}{room_str}")
                phrases.append(f"Bạn không có quyền {act} {type_vi}{room_str}.")
                phrases.append(f"Không thể kết nối đến Gateway để {act} {type_vi}{room_str}.")
                phrases.append(f"Không thể {act} {type_vi}{room_str} — thiết bị không phản hồi.")

    # 1. Câu theo từng (phòng thật, loại thiết bị thật)
    for (room, type_vi), count in room_type_counts.items():
        _append_group(type_vi, f" {room}", count, type_actions[type_vi])

    # 2. Câu không nêu tên phòng (hoặc "trong nhà" cho lệnh toàn bộ)
    for type_vi, count in house_type_counts.items():
        _append_group(type_vi, "", count, type_actions[type_vi])
        if count > 1:
            _append_group(type_vi, " trong nhà", count, type_actions[type_vi])

    # 3. Câu dừng motor rèm cho các phòng có rèm
    for room in has_curtain_rooms:
        room_str = f" {room}" if room else ""
        for tv in ("rèm", "rèm cửa"):
            phrases.append(f"Đã dừng {tv}{room_str}")
            phrases.append(f"Đã dừng {tv}")

    # 4. Câu đặt nhiệt độ điều hòa phổ biến (18–29°C) cho các phòng có điều hòa
    for room in has_ac_rooms:
        room_str = f" {room}" if room else ""
        for temp in range(18, 30):
            phrases.append(f"Đã đặt nhiệt độ điều hòa{room_str} về {temp} độ")
            phrases.append(f"Đã đặt nhiệt độ điều hòa về {temp} độ")

    return list(dict.fromkeys(phrases))
