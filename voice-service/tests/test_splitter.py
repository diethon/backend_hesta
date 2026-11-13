"""
HESTA Voice Service — Test Compound Command Splitter
Unit tests cho app/splitter.py.
"""

import pytest
from app.splitter import split_compound_command


class TestCompoundCommandSplitter:
    """Kiểm thử tính năng phân tách câu lệnh kép."""

    def test_full_independent_commands(self):
        """Hai mệnh đề độc lập đầy đủ động từ và thiết bị/phòng."""
        res1 = split_compound_command("tắt quạt phòng khách và bật đèn phòng ngủ")
        assert res1 == ["tắt quạt phòng khách", "bật đèn phòng ngủ"]

        res2 = split_compound_command("bật đèn phòng khách rồi tắt điều hòa phòng ngủ")
        assert res2 == ["bật đèn phòng khách", "tắt điều hòa phòng ngủ"]

        res3 = split_compound_command("bật đèn phòng khách sau đó tắt quạt phòng khách")
        assert res3 == ["bật đèn phòng khách", "tắt quạt phòng khách"]

        res4 = split_compound_command("bật đèn phòng khách đồng thời mở rèm")
        assert res4 == ["bật đèn phòng khách", "mở rèm"]

        res5 = split_compound_command("tắt quạt phòng khách, bật đèn phòng ngủ")
        assert res5 == ["tắt quạt phòng khách", "bật đèn phòng ngủ"]

    def test_shared_action_verb_ellipsis(self):
        """Tỉnh lược động từ ở vế sau: 'bật quạt phòng khách và đèn phòng ngủ'."""
        res1 = split_compound_command("bật quạt phòng khách và đèn phòng ngủ")
        assert res1 == ["bật quạt phòng khách", "bật đèn phòng ngủ"]

        res2 = split_compound_command("tắt đèn phòng khách và điều hòa phòng ngủ")
        assert res2 == ["tắt đèn phòng khách", "tắt điều hòa phòng ngủ"]

        res3 = split_compound_command("mở rèm cửa và quạt phòng khách")
        assert res3 == ["mở rèm cửa phòng khách", "mở quạt phòng khách"]

    def test_shared_device_multiple_rooms(self):
        """Chung thiết bị khác phòng: 'tắt đèn phòng khách và phòng ngủ'."""
        res1 = split_compound_command("tắt đèn phòng khách và phòng ngủ")
        assert res1 == ["tắt đèn phòng khách", "tắt đèn phòng ngủ"]

        res2 = split_compound_command("bật điều hòa phòng làm việc và phòng khách")
        assert res2 == ["bật điều hòa phòng làm việc", "bật điều hòa phòng khách"]

        # Với custom room động
        custom_rooms = ["Phòng của Đức", "Phòng Gaming"]
        res3 = split_compound_command("tắt đèn phòng khách và phòng của đức", custom_rooms=custom_rooms)
        assert res3 == ["tắt đèn phòng khách", "tắt đèn phòng của đức"]

    def test_shared_room_multiple_devices(self):
        """Chung phòng khác thiết bị: 'bật đèn và quạt phòng khách'."""
        res1 = split_compound_command("bật đèn và quạt phòng khách")
        assert res1 == ["bật đèn phòng khách", "bật quạt phòng khách"]

        res2 = split_compound_command("tắt quạt và đèn phòng ngủ")
        assert res2 == ["tắt quạt phòng ngủ", "tắt đèn phòng ngủ"]

    def test_chaining_three_commands(self):
        """Chuỗi 3 câu lệnh nối tiếp: 'tắt quạt phòng khách, bật đèn phòng ngủ và đóng cửa chính'."""
        cmd = "tắt quạt phòng khách, bật đèn phòng ngủ và đóng cửa chính"
        res = split_compound_command(cmd)
        assert res == ["tắt quạt phòng khách", "bật đèn phòng ngủ", "đóng cửa chính"]

    def test_param_action_room_inheritance(self):
        """Kế thừa phòng cho lệnh điều chỉnh tham số kế tiếp."""
        res1 = split_compound_command("bật đèn phòng khách và đổi màu sang đỏ")
        assert res1 == ["bật đèn phòng khách", "đổi màu sang đỏ phòng khách"]

        res2 = split_compound_command("bật điều hòa phòng ngủ và đặt nhiệt độ 24 độ")
        assert res2 == ["bật điều hòa phòng ngủ", "đặt nhiệt độ 24 độ phòng ngủ"]

    def test_false_positives_not_split(self):
        """Các trường hợp có chữ 'và' nhưng KHÔNG ĐƯỢC TÁCH."""
        # Nối 2 màu sắc
        res1 = split_compound_command("đổi đèn sang màu xanh và đỏ")
        assert res1 == ["đổi đèn sang màu xanh và đỏ"]

        # Cụm từ kỹ thuật 'đèn led và rgb'
        res2 = split_compound_command("bật đèn led và rgb")
        assert res2 == ["bật đèn led rgb"]

        # Nối 2 chế độ chiếu sáng
        res3 = split_compound_command("bật đèn chế độ thư giãn và đọc sách")
        assert res3 == ["bật đèn chế độ thư giãn và đọc sách"]

        # Số đếm / tham số
        res4 = split_compound_command("bật quạt số một và số hai")
        assert res4 == ["bật quạt số một và số hai"]

        # Câu đơn thông thường
        res5 = split_compound_command("bật đèn phòng của đức", custom_rooms=["Phòng của Đức"])
        assert res5 == ["bật đèn phòng của đức"]

        # Chuỗi rỗng
        assert split_compound_command("") == []


class TestCombineCommandMessages:
    """Kiểm thử ghép nối thông báo tự nhiên tiếng Việt."""

    def test_all_success_two_commands(self):
        from app.splitter import combine_command_messages
        msgs = [
            (True, "Đã tắt quạt phòng khách"),
            (True, "Đã bật đèn phòng ngủ"),
        ]
        res = combine_command_messages(msgs)
        assert res == "Đã tắt quạt phòng khách và bật đèn phòng ngủ."

    def test_all_success_three_commands(self):
        from app.splitter import combine_command_messages
        msgs = [
            (True, "Đã tắt quạt phòng khách"),
            (True, "Đã bật đèn phòng ngủ"),
            (True, "Đã đóng cửa chính"),
        ]
        res = combine_command_messages(msgs)
        assert res == "Đã tắt quạt phòng khách, bật đèn phòng ngủ và đóng cửa chính."

    def test_partial_success(self):
        from app.splitter import combine_command_messages
        msgs = [
            (True, "Đã tắt quạt phòng khách"),
            (False, "Không tìm thấy đèn nào ở phòng ngủ"),
        ]
        res = combine_command_messages(msgs)
        assert res == "Đã tắt quạt phòng khách, nhưng không tìm thấy đèn nào ở phòng ngủ."

    def test_all_failed(self):
        from app.splitter import combine_command_messages
        msgs = [
            (False, "Không tìm thấy quạt nào ở phòng khách"),
            (False, "Không tìm thấy đèn nào ở phòng ngủ"),
        ]
        res = combine_command_messages(msgs)
        assert res == "Không tìm thấy quạt nào ở phòng khách và không tìm thấy đèn nào ở phòng ngủ."

