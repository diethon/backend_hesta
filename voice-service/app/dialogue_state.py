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
class DialogueContext:
    """Context của 1 lượt hội thoại."""
    last_room: str | None = None
    last_device_type: str | None = None
    last_device_ids: list[str] = field(default_factory=list)
    last_action: str | None = None
    last_home_id: str = ""
    expires_at: float = 0.0  # timestamp UNIX

    @property
    def is_expired(self) -> bool:
        return time.time() > self.expires_at

    @property
    def is_valid(self) -> bool:
        return not self.is_expired and (self.last_room is not None or self.last_device_type is not None)


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

    def update(self, room: str | None = None, device_type: str | None = None,
               device_ids: list[str] | None = None, action: str | None = None,
               home_id: str = "") -> None:
        """Cập nhật context sau khi xử lý thành công 1 lệnh.

        Args:
            room: tên phòng vừa sử dụng
            device_type: loại thiết bị vừa điều khiển
            device_ids: danh sách device_id vừa dispatch
            action: hành động vừa thực hiện
            home_id: ID nhà
        """
        self._context = DialogueContext(
            last_room=room,
            last_device_type=device_type,
            last_device_ids=device_ids or [],
            last_action=action,
            last_home_id=home_id,
            expires_at=time.time() + self.ttl_seconds,
        )
        logger.info(
            "Dialogue context updated: room=%s, type=%s, devices=%s (TTL=%ds)",
            room, device_type, device_ids, self.ttl_seconds,
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

    def fill_missing(self, room: str | None, device_type: str | None,
                     home_id: str = "") -> tuple[str | None, str | None, str]:
        """Bổ sung thông tin thiếu từ context trước đó.

        Dùng khi câu nói thiếu room hoặc device_type.
        Chỉ fill nếu context còn valid (chưa hết TTL).

        Args:
            room: room từ câu nói mới (None nếu thiếu)
            device_type: device_type từ câu nói mới (None nếu thiếu)
            home_id: home_id từ câu nói mới

        Returns:
            (filled_room, filled_device_type, filled_home_id)
        """
        ctx = self.get_context()
        filled_room = room
        filled_device_type = device_type
        filled_home_id = home_id

        if ctx.is_valid:
            if filled_room is None and ctx.last_room is not None:
                filled_room = ctx.last_room
                logger.info("Dùng room từ context trước: '%s'", filled_room)

            if filled_device_type is None and ctx.last_device_type is not None:
                filled_device_type = ctx.last_device_type
                logger.info("Dùng device_type từ context trước: '%s'", filled_device_type)

            if not filled_home_id and ctx.last_home_id:
                filled_home_id = ctx.last_home_id

        return filled_room, filled_device_type, filled_home_id

    @property
    def has_context(self) -> bool:
        """Kiểm tra có context valid hay không."""
        return self._context.is_valid
