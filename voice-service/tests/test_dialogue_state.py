"""
HESTA Voice Service — Test Dialogue State
Unit test cho dialogue_state.py (Mục 11)

Test TTL hết hạn đúng lúc, fill_missing hoạt động đúng.
"""

import time

import pytest

from app.dialogue_state import DialogueState, DialogueContext


class TestDialogueContext:
    def test_empty_context_is_invalid(self):
        ctx = DialogueContext()
        assert not ctx.is_valid

    def test_expired_context(self):
        ctx = DialogueContext(
            last_room="phòng khách",
            last_device_type="light",
            expires_at=time.time() - 1,  # Đã hết hạn
        )
        assert ctx.is_expired
        assert not ctx.is_valid

    def test_valid_context(self):
        ctx = DialogueContext(
            last_room="phòng khách",
            last_device_type="light",
            expires_at=time.time() + 45,  # Còn hạn
        )
        assert not ctx.is_expired
        assert ctx.is_valid


class TestDialogueState:
    def test_initial_state(self):
        state = DialogueState()
        assert not state.has_context

    def test_update_creates_context(self):
        state = DialogueState(ttl_seconds=45)
        state.update(
            room="phòng khách",
            device_type="light",
            device_ids=["dev-001"],
            action="TURN_ON",
        )
        assert state.has_context
        ctx = state.get_context()
        assert ctx.last_room == "phòng khách"
        assert ctx.last_device_type == "light"
        assert ctx.last_device_ids == ["dev-001"]
        assert ctx.last_action == "TURN_ON"

    def test_context_expires(self):
        """Context phải hết hạn sau TTL."""
        state = DialogueState(ttl_seconds=1)  # TTL 1 giây
        state.update(room="phòng ngủ", device_type="fan")

        assert state.has_context
        time.sleep(1.1)  # Đợi qua TTL
        assert not state.has_context

    def test_get_context_after_expiry(self):
        """Sau khi hết hạn, get_context trả context rỗng."""
        state = DialogueState(ttl_seconds=1)
        state.update(room="phòng khách", device_type="light")
        time.sleep(1.1)

        ctx = state.get_context()
        assert not ctx.is_valid
        assert ctx.last_room is None

    def test_update_resets_ttl(self):
        """Update mới phải reset TTL."""
        state = DialogueState(ttl_seconds=2)
        state.update(room="phòng khách", device_type="light")
        time.sleep(1)
        # Vẫn valid vì chưa qua 2 giây
        assert state.has_context

        # Update lại → reset TTL
        state.update(room="phòng ngủ", device_type="fan")
        time.sleep(1.5)
        # Vẫn valid vì TTL mới bắt đầu từ update thứ 2
        assert state.has_context

    def test_clear(self):
        state = DialogueState()
        state.update(room="phòng khách", device_type="light")
        assert state.has_context

        state.clear()
        assert not state.has_context


class TestFillMissing:
    def test_fill_room_from_context(self):
        """Câu thiếu room → lấy từ context trước."""
        state = DialogueState(ttl_seconds=45)
        state.update(room="phòng khách", device_type="light",
                     device_ids=["dev-001"], action="TURN_ON")

        room, dtype, home_id = state.fill_missing(None, "light")
        assert room == "phòng khách"
        assert dtype == "light"

    def test_fill_device_type_from_context(self):
        """Câu thiếu device_type → lấy từ context trước."""
        state = DialogueState(ttl_seconds=45)
        state.update(room="phòng khách", device_type="light")

        room, dtype, _ = state.fill_missing("phòng khách", None)
        assert dtype == "light"

    def test_fill_both_from_context(self):
        """Câu thiếu cả room lẫn device_type → lấy cả 2 từ context."""
        state = DialogueState(ttl_seconds=45)
        state.update(room="phòng khách", device_type="light")

        room, dtype, _ = state.fill_missing(None, None)
        assert room == "phòng khách"
        assert dtype == "light"

    def test_no_fill_when_expired(self):
        """Context hết hạn → không fill gì."""
        state = DialogueState(ttl_seconds=1)
        state.update(room="phòng khách", device_type="light")
        time.sleep(1.1)

        room, dtype, _ = state.fill_missing(None, None)
        assert room is None
        assert dtype is None

    def test_no_overwrite_explicit_values(self):
        """Giá trị tường minh từ câu nói mới → KHÔNG bị ghi đè bởi context."""
        state = DialogueState(ttl_seconds=45)
        state.update(room="phòng khách", device_type="light")

        room, dtype, _ = state.fill_missing("phòng ngủ", "fan")
        assert room == "phòng ngủ"  # Giữ nguyên, không bị đè
        assert dtype == "fan"

    def test_fill_home_id(self):
        """home_id cũng được fill từ context."""
        state = DialogueState(ttl_seconds=45)
        state.update(room="phòng khách", device_type="light", home_id="home-1")

        _, _, home_id = state.fill_missing(None, None)
        assert home_id == "home-1"
