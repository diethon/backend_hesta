"""
HESTA Voice Service — Dialogue State (Mục 2.2)
Quản lý context multi-turn (đa lượt hội thoại).

Cơ chế:
- Lưu tạm last_device_id, last_room, last_device_type, expires_at trong dict Python
- TTL mặc định 45 giây
- Tham chiếu khi câu nói thiếu chủ ngữ (VD: "tắt đi" sau khi vừa "bật đèn phòng khách")
- Hết hạn → xóa context, không suy luận sai
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


@dataclass
class DialogueEntry:
    """Bản ghi của một hành động thành công trong ngữ cảnh gần nhất."""
    room: str | None = None
    device_type: str | None = None
    device_ids: list[str] = field(default_factory=list)
    action: str | None = None
    device_name_hint: str = ""
    home_id: str = ""
    timestamp: float = field(default_factory=time.time)


@dataclass
class DialogueContext:
    """Context của 1 lượt hội thoại (hỗ trợ cả truy cập đơn lẻ và danh sách đa tương tác)."""
    last_room: str | None = None
    last_device_type: str | None = None
    last_device_ids: list[str] = field(default_factory=list)
    last_action: str | None = None
    last_home_id: str = ""
    last_device_name_hint: str = ""
    expires_at: float = 0.0  # timestamp UNIX
    entries: list[DialogueEntry] = field(default_factory=list)

    @property
    def is_expired(self) -> bool:
        return time.time() > self.expires_at

    @property
    def is_valid(self) -> bool:
        return not self.is_expired and (
            self.last_room is not None or self.last_device_type is not None or bool(self.entries)
        )


class DialogueState:
    """
    Quản lý context multi-turn hội thoại.

    Sử dụng:
        state = DialogueState(ttl_seconds=45)

        # Sau khi xử lý "bật đèn phòng khách"
        state.update(room="phòng khách", device_type="light",
                     device_ids=["dev-001", "dev-002"], action="TURN_ON")

        # Khi xử lý "tắt đi" (thiếu room + device_type)
        ctx = state.get_context()
        if ctx.is_valid:
            # Dùng ctx.last_room, ctx.last_device_type, ctx.last_device_ids
    """

    def __init__(self, ttl_seconds: int = 45):
        self.ttl_seconds = ttl_seconds
        self._context = DialogueContext()

    def update(
        self,
        room: str | None = None,
        device_type: str | None = None,
        device_ids: list[str] | None = None,
        action: str | None = None,
        home_id: str = "",
        device_name_hint: str = "",
    ) -> None:
        """Cập nhật context sau khi xử lý thành công 1 lệnh (lưu cả entry mới lẫn snapshot).

        Args:
            room: tên phòng vừa sử dụng
            device_type: loại thiết bị vừa điều khiển
            device_ids: danh sách device_id vừa dispatch
            action: hành động vừa thực hiện
            home_id: ID nhà
            device_name_hint: gợi ý tên thiết bị (VD: "ngủ", "trần", "bàn")
        """
        now = time.time()
        # Lọc bỏ các entries đã quá hạn
        valid_entries = [
            e for e in self._context.entries
            if (now - e.timestamp) <= self.ttl_seconds
        ]
        new_entry = DialogueEntry(
            room=room,
            device_type=device_type,
            device_ids=device_ids or [],
            action=action,
            device_name_hint=device_name_hint,
            home_id=home_id,
            timestamp=now,
        )
        valid_entries.append(new_entry)

        self._context = DialogueContext(
            last_room=room,
            last_device_type=device_type,
            last_device_ids=device_ids or [],
            last_action=action,
            last_home_id=home_id,
            last_device_name_hint=device_name_hint,
            expires_at=now + self.ttl_seconds,
            entries=valid_entries,
        )
        logger.info(
            "Dialogue context updated: room=%s, type=%s, hint=%s, devices=%s (TTL=%ds, total_entries=%d)",
            room, device_type, device_name_hint, device_ids, self.ttl_seconds, len(valid_entries),
        )

    def get_context(self) -> DialogueContext:
        """Lấy context hiện tại.

        Returns:
            DialogueContext. Kiểm tra is_valid trước khi dùng.
        """
        if self._context.is_expired:
            logger.debug("Dialogue context đã hết hạn.")
            return DialogueContext()  # Trả context rỗng
        return self._context

    def clear(self) -> None:
        """Xóa context thủ công (VD: khi có lỗi hoặc reset)."""
        self._context = DialogueContext()
        logger.debug("Dialogue context cleared.")

    def fill_missing(
        self,
        room: str | None,
        device_type: str | None,
        home_id: str = "",
        action: str | None = None,
        device_name_hint: str = "",
    ) -> tuple[str | None, str | None, str]:
        """Bổ sung thông tin thiếu từ context trước đó.

        Hỗ trợ giải quyết ngữ cảnh thông minh:
        1. Nếu câu mới đã có device_type nhưng thiếu room (VD: "tắt đèn đi"):
           Ưu tiên tìm trong các tương tác gần đây xem có phòng nào gắn với loại thiết bị này không.
        2. Nếu câu mới thiếu cả room và device_type (VD: "tắt đi", "bật đi", "mở ra", "đóng lại"):
           Dựa vào action để chọn thiết bị phù hợp gần nhất trong lịch sử tương tác.
        3. Nếu câu mới đã có room nhưng thiếu device_type (VD: "tắt phòng khách đi"):
           Tìm thiết bị gần nhất tại phòng đó hoặc thiết bị gần nhất nói chung.

        Args:
            room: room từ câu nói mới (None nếu thiếu)
            device_type: device_type từ câu nói mới (None nếu thiếu)
            home_id: home_id từ câu nói mới
            action: hành động từ câu nói mới (nếu có, VD: "TURN_OFF", "TURN_ON", "OPEN", "CLOSE")
            device_name_hint: gợi ý tên thiết bị từ câu mới (nếu có)

        Returns:
            (filled_room, filled_device_type, filled_home_id)
        """
        ctx = self.get_context()
        filled_room = room
        filled_device_type = device_type
        filled_home_id = home_id

        if not ctx.is_valid:
            return filled_room, filled_device_type, filled_home_id

        now = time.time()
        valid_entries = [
            e for e in ctx.entries
            if (now - e.timestamp) <= self.ttl_seconds
        ]

        # -------------------------------------------------------------
        # Tình huống 1: Câu mới ĐÃ CÓ device_type nhưng THIẾU room
        # Ví dụ: "tắt đèn đi", "bật quạt lên", "đóng rèm lại"
        # -------------------------------------------------------------
        if filled_device_type is not None and filled_room is None:
            if filled_device_type not in {"gate", "water_pump", "all"}:
                # 1.1. Ưu tiên tìm entry khớp cùng device_type mà có room
                match_entry = next(
                    (e for e in reversed(valid_entries) if e.device_type == filled_device_type and e.room is not None),
                    None,
                )
                if match_entry:
                    filled_room = match_entry.room
                    logger.info("Dùng room từ context khớp device_type '%s': '%s'", filled_device_type, filled_room)
                elif ctx.last_room is not None:
                    # 1.2. Fallback sang last_room gần nhất (nếu có)
                    filled_room = ctx.last_room
                    logger.info("Dùng room từ context gần nhất: '%s'", filled_room)

        # -------------------------------------------------------------
        # Tình huống 2: Câu mới THIẾU CẢ device_type và room
        # Ví dụ: "tắt đi", "bật đi", "mở ra", "đóng lại", "dừng lại"
        # -------------------------------------------------------------
        elif filled_device_type is None and filled_room is None:
            target_entry = None
            if valid_entries:
                if action in {"OPEN", "CLOSE"}:
                    target_entry = next(
                        (e for e in reversed(valid_entries) if e.device_type in {"curtain", "gate", "door_lock"}),
                        None,
                    )
                elif action in {"TURN_ON", "TURN_OFF"}:
                    target_entry = next(
                        (e for e in reversed(valid_entries) if e.device_type in {"light", "led_rgb", "fan", "air_conditioner", "water_pump", "water_heater", "tv", "socket"}),
                        None,
                    )
                elif action == "STOP":
                    target_entry = next(
                        (e for e in reversed(valid_entries) if e.device_type in {"curtain", "gate"}),
                        None,
                    )

                if target_entry is None:
                    target_entry = valid_entries[-1]

            if target_entry:
                filled_device_type = target_entry.device_type
                if target_entry.device_type not in {"gate", "water_pump", "all"}:
                    filled_room = target_entry.room
                logger.info(
                    "Dùng device_type '%s' và room '%s' từ context entry (action=%s)",
                    filled_device_type, filled_room, action,
                )
            else:
                # Fallback sang snapshot đơn
                if ctx.last_device_type is not None:
                    filled_device_type = ctx.last_device_type
                    logger.info("Dùng device_type từ context trước: '%s'", filled_device_type)
                if filled_device_type not in {"gate", "water_pump", "all"} and ctx.last_room is not None:
                    filled_room = ctx.last_room
                    logger.info("Dùng room từ context trước: '%s'", filled_room)

        # -------------------------------------------------------------
        # Tình huống 3: Câu mới ĐÃ CÓ room nhưng THIẾU device_type
        # Ví dụ: "ở phòng ngủ master tắt đi", "tắt phòng khách đi"
        # -------------------------------------------------------------
        elif filled_device_type is None and filled_room is not None:
            match_entry = next(
                (e for e in reversed(valid_entries) if e.room == filled_room and e.device_type is not None),
                None,
            )
            if match_entry:
                filled_device_type = match_entry.device_type
                logger.info("Dùng device_type '%s' từ entry khớp room '%s'", filled_device_type, filled_room)
            elif ctx.last_device_type is not None:
                filled_device_type = ctx.last_device_type
                logger.info("Dùng device_type từ context gần nhất: '%s'", filled_device_type)

        if not filled_home_id and ctx.last_home_id:
            filled_home_id = ctx.last_home_id

        return filled_room, filled_device_type, filled_home_id

    def get_hint_for(self, room: str | None, device_type: str | None) -> str:
        """Lấy device_name_hint gần nhất phù hợp với room và device_type."""
        ctx = self.get_context()
        if not ctx.is_valid:
            return ""

        now = time.time()
        valid_entries = [
            e for e in ctx.entries
            if (now - e.timestamp) <= self.ttl_seconds
        ]

        # 1. Khớp cả room và device_type
        for e in reversed(valid_entries):
            if e.device_type == device_type and (room is None or e.room == room) and e.device_name_hint:
                return e.device_name_hint

        # 2. Khớp device_type
        for e in reversed(valid_entries):
            if e.device_type == device_type and e.device_name_hint:
                return e.device_name_hint

        if ctx.last_device_type == device_type and ctx.last_device_name_hint:
            return ctx.last_device_name_hint

        return ""

    @property
    def has_context(self) -> bool:
        """Kiểm tra có context valid hay không."""
        return self._context.is_valid
