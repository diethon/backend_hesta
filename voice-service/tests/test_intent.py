"""
HESTA Voice Service — Test Intent Mapping
Unit test cho intent.py (Mục 11)

Test nhiều biến thể câu nói cho cùng 1 ý định.
"""

import json
import os
import tempfile

import pytest

from app.intent import IntentMapper, IntentConfig, IntentResult, ROOM_PATTERNS


# --- Fixtures ---

SAMPLE_INTENTS = {
    "intents": [
        {
            "action": "TURN_ON",
            "device_type": "light",
            "patterns": ["bật đèn", "mở đèn", "bật sáng"]
        },
        {
            "action": "TURN_OFF",
            "device_type": "light",
            "patterns": ["tắt đèn", "tắt hết đèn"]
        },
        {
            "action": "TURN_ON",
            "device_type": "fan",
            "patterns": ["bật quạt", "mở quạt"]
        },
        {
            "action": "TURN_OFF",
            "device_type": "fan",
            "patterns": ["tắt quạt"]
        },
        {
            "action": "SET_TEMPERATURE",
            "device_type": "air_conditioner",
            "patterns": ["chỉnh điều hòa", "đặt nhiệt độ", "chỉnh nhiệt độ"],
            "has_parameter": "temperature"
        },
        {
            "action": "TURN_ON",
            "device_type": "air_conditioner",
            "patterns": ["bật điều hòa", "mở điều hòa", "bật máy lạnh"]
        },
        {
            "action": "TURN_OFF",
            "device_type": "air_conditioner",
            "patterns": ["tắt điều hòa", "tắt máy lạnh"]
        },
    ]
}


@pytest.fixture
def intents_file():
    """Tạo file intents_vi.json tạm."""
    fd, path = tempfile.mkstemp(suffix=".json")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(SAMPLE_INTENTS, f, ensure_ascii=False)
    yield path
    os.unlink(path)


@pytest.fixture
def mapper(intents_file):
    config = IntentConfig(min_confidence=60.0)
    m = IntentMapper(config)
    m.load_intents(intents_file)
    return m


# --- Tests ---

class TestExtractRoom:
    def test_room_at_end(self, mapper):
        room, remaining = mapper.extract_room("bật đèn phòng khách")
        assert room == "phòng khách"
        assert "bật đèn" in remaining

    def test_room_at_beginning(self, mapper):
        room, remaining = mapper.extract_room("phòng ngủ tắt đèn")
        assert room == "phòng ngủ"
        assert "tắt đèn" in remaining

    def test_no_room(self, mapper):
        room, remaining = mapper.extract_room("bật đèn")
        assert room is None
        assert remaining == "bật đèn"

    def test_room_with_preposition(self, mapper):
        room, remaining = mapper.extract_room("bật đèn ở phòng khách")
        assert room == "phòng khách"
        assert "ở" not in remaining or remaining.strip() == "bật đèn"

    def test_long_room_name_priority(self, mapper):
        """Tên phòng dài hơn phải được ưu tiên (VD: 'phòng làm việc' trước 'phòng')."""
        room, remaining = mapper.extract_room("bật đèn phòng làm việc")
        assert room == "phòng làm việc"

    def test_case_insensitive(self, mapper):
        room, remaining = mapper.extract_room("Bật Đèn Phòng Khách")
        assert room == "phòng khách"


class TestIntentMapping:
    def test_turn_on_light(self, mapper):
        result = mapper.map("bật đèn phòng khách")
        assert result is not None
        assert result.action == "TURN_ON"
        assert result.device_type == "light"
        assert result.room == "phòng khách"

    def test_turn_off_light(self, mapper):
        result = mapper.map("tắt đèn phòng ngủ")
        assert result is not None
        assert result.action == "TURN_OFF"
        assert result.device_type == "light"
        assert result.room == "phòng ngủ"

    def test_turn_on_fan(self, mapper):
        result = mapper.map("mở quạt phòng khách")
        assert result is not None
        assert result.action == "TURN_ON"
        assert result.device_type == "fan"
        assert result.room == "phòng khách"

    def test_turn_on_ac(self, mapper):
        result = mapper.map("bật điều hòa phòng ngủ")
        assert result is not None
        assert result.action == "TURN_ON"
        assert result.device_type == "air_conditioner"
        assert result.room == "phòng ngủ"

    def test_set_temperature(self, mapper):
        result = mapper.map("chỉnh điều hòa 25 độ phòng ngủ")
        assert result is not None
        assert result.action == "SET_TEMPERATURE"
        assert result.device_type == "air_conditioner"
        assert result.parameters.get("temperature") == 25

    def test_set_temperature_no_unit(self, mapper):
        """Nhiệt độ không có đơn vị vẫn phải nhận."""
        result = mapper.map("đặt nhiệt độ 22")
        assert result is not None
        assert result.parameters.get("temperature") == 22

    def test_temperature_out_of_range(self, mapper):
        """Nhiệt độ ngoài phạm vi (16-32) → không trích xuất."""
        result = mapper.map("đặt nhiệt độ 50 độ")
        assert result is not None
        assert "temperature" not in result.parameters

    def test_no_room_specified(self, mapper):
        """Câu nói không có tên phòng → room = None."""
        result = mapper.map("bật đèn")
        assert result is not None
        assert result.action == "TURN_ON"
        assert result.device_type == "light"
        assert result.room is None

    def test_empty_text(self, mapper):
        assert mapper.map("") is None
        assert mapper.map("   ") is None

    def test_no_match(self, mapper):
        """Câu không liên quan → None (confidence thấp)."""
        result = mapper.map("hôm nay thời tiết thế nào")
        assert result is None

    def test_fuzzy_matching(self, mapper):
        """Câu nói có typo/biến thể nhẹ vẫn phải match."""
        result = mapper.map("bật cái đèn phòng khách")
        # Fuzzy match "bật cái đèn" với "bật đèn"
        assert result is not None
        assert result.action == "TURN_ON"
        assert result.device_type == "light"

    def test_synonym_mo_den(self, mapper):
        """'mở đèn' = 'bật đèn'."""
        result = mapper.map("mở đèn phòng bếp")
        assert result is not None
        assert result.action == "TURN_ON"
        assert result.device_type == "light"

    def test_confidence_stored(self, mapper):
        result = mapper.map("bật đèn")
        assert result is not None
        assert result.confidence > 0

    def test_raw_text_stored(self, mapper):
        result = mapper.map("bật đèn phòng khách")
        assert result is not None
        assert result.raw_text == "bật đèn phòng khách"


class TestIntentMapperNoLoad:
    def test_map_without_loading(self):
        """Gọi map() trước khi load_intents() → trả None."""
        mapper = IntentMapper()
        result = mapper.map("bật đèn")
        assert result is None


class TestIntentConfig:
    def test_defaults(self):
        config = IntentConfig()
        assert config.min_confidence == 60.0
        assert config.context_ttl_seconds == 45

    def test_high_confidence_threshold(self, intents_file):
        """Min confidence cao → reject match kém."""
        config = IntentConfig(min_confidence=99.0)
        mapper = IntentMapper(config)
        mapper.load_intents(intents_file)

        result = mapper.map("bật cái gì đó giống đèn")
        assert result is None  # Confidence không đủ 99%
