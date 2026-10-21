"""
HESTA Voice Service — Test Device Directory
Unit test cho device_directory.py (Mục 11)

Test cases:
- Cache hit/miss
- Refresh định kỳ
- Xử lý nhiều thiết bị cùng loại trong 1 phòng (Mục 2.4 bước 3)
- Xử lý không tìm thấy (Mục 2.4 bước 4)
- Auth header đúng
"""

import json
import time
from unittest.mock import MagicMock, patch

import httpx
import pytest

from app.device_directory import DeviceDirectory, DeviceDirectoryConfig, DeviceInfo


# --- Fixtures ---

SAMPLE_DEVICES = [
    {"id": "dev-001", "name": "Đèn trần", "type": "light", "room": "Phòng khách", "homeId": "home-1"},
    {"id": "dev-002", "name": "Đèn bàn", "type": "light", "room": "Phòng khách", "homeId": "home-1"},
    {"id": "dev-003", "name": "Đèn ngủ", "type": "light", "room": "Phòng ngủ", "homeId": "home-1"},
    {"id": "dev-004", "name": "Quạt trần", "type": "fan", "room": "Phòng khách", "homeId": "home-1"},
    {"id": "dev-005", "name": "Điều hòa", "type": "air_conditioner", "room": "Phòng ngủ", "homeId": "home-1"},
    {"id": "dev-006", "name": "Đèn LED RGB", "type": "led_rgb", "room": "Phòng ngủ", "homeId": "home-1"},
    {"id": "dev-007", "name": "Dây LED trang trí", "type": "light", "room": "Ban công", "homeId": "home-1"},
]


def make_mock_transport(devices: list[dict] = SAMPLE_DEVICES,
                        status_code: int = 200) -> httpx.MockTransport:
    """Tạo mock transport cho httpx.Client."""

    def handler(request: httpx.Request) -> httpx.Response:
        # Kiểm tra auth header
        if "X-Internal-Api-Key" not in request.headers:
            return httpx.Response(401, json={"error": "Missing API key"})

        if status_code != 200:
            return httpx.Response(status_code, json={"error": "Error"})

        return httpx.Response(200, json=devices)

    return httpx.MockTransport(handler)


@pytest.fixture
def config():
    return DeviceDirectoryConfig(
        base_url="http://localhost:8080",
        devices_endpoint="/internal/devices",
        refresh_interval_seconds=60,
    )


@pytest.fixture
def directory(config):
    transport = make_mock_transport()
    client = httpx.Client(base_url=config.base_url, transport=transport)
    d = DeviceDirectory(config=config, http_client=client)
    return d


# --- Tests ---

class TestDeviceDirectoryConfig:
    def test_defaults(self):
        config = DeviceDirectoryConfig()
        assert config.base_url == "http://localhost:8080"
        assert config.devices_endpoint == "/internal/devices"
        assert config.refresh_interval_seconds == 60


class TestDeviceInfo:
    def test_fields(self):
        info = DeviceInfo(
            device_id="dev-001",
            device_name="Đèn trần",
            device_type="light",
            room_name="phòng khách",
            home_id="home-1",
        )
        assert info.device_id == "dev-001"
        assert info.device_type == "light"


class TestBuildCache:
    def test_build_from_raw(self, directory):
        cache = directory._build_cache(SAMPLE_DEVICES)

        # 2 đèn phòng khách
        key = ("home-1", "phòng khách", "light")
        assert key in cache
        assert len(cache[key]) == 2

        # 1 đèn phòng ngủ
        key = ("home-1", "phòng ngủ", "light")
        assert key in cache
        assert len(cache[key]) == 1

    def test_skip_malformed_devices(self, directory):
        """Thiết bị thiếu field quan trọng → bỏ qua, không crash."""
        devices = [
            {"id": "dev-001", "type": "light", "room": "Phòng khách"},  # OK
            {"name": "Missing ID"},  # Thiếu id và type → bỏ qua
            {"id": "dev-002"},  # Thiếu type → bỏ qua
        ]
        cache = directory._build_cache(devices)
        total_devices = sum(len(v) for v in cache.values())
        assert total_devices == 1


class TestRefresh:
    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_refresh_success(self, directory):
        """Refresh thành công → cache được cập nhật."""
        result = directory.refresh()
        assert result is True
        assert directory.cache_size > 0
        assert directory.last_refresh_time > 0

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_refresh_failure_http_error(self, config):
        """Lỗi HTTP → refresh trả False, cache không đổi."""
        transport = make_mock_transport(status_code=500)
        client = httpx.Client(base_url=config.base_url, transport=transport)
        directory = DeviceDirectory(config=config, http_client=client)

        result = directory.refresh()
        assert result is False
        assert directory.cache_size == 0


class TestResolve:
    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_resolve_single_device(self, directory):
        """Resolve 1 thiết bị duy nhất trong phòng."""
        directory.refresh()
        devices = directory.resolve("phòng ngủ", "air_conditioner", home_id="home-1")
        assert len(devices) == 1
        assert devices[0].device_id == "dev-005"

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_resolve_multiple_same_type(self, directory):
        """Mục 2.4 bước 3: nhiều thiết bị cùng loại → trả TẤT CẢ."""
        directory.refresh()
        devices = directory.resolve("phòng khách", "light", home_id="home-1")
        assert len(devices) == 2
        device_ids = {d.device_id for d in devices}
        assert device_ids == {"dev-001", "dev-002"}

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_resolve_not_found(self, directory):
        """Mục 2.4 bước 4: không khớp → list rỗng."""
        directory.refresh()
        devices = directory.resolve("phòng bếp", "light", home_id="home-1")
        assert devices == []

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_resolve_case_insensitive(self, directory):
        """Room name và device type phải case-insensitive."""
        directory.refresh()
        devices = directory.resolve("Phòng Khách", "LIGHT", home_id="home-1")
        assert len(devices) == 2

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_resolve_led_rgb_distinct_from_regular_light(self, directory):
        """Phòng ngủ có cả đèn ngủ (light) và đèn LED RGB (led_rgb) -> resolve độc lập."""
        directory.refresh()
        rgb_devs = directory.resolve("phòng ngủ", "led_rgb", home_id="home-1")
        assert len(rgb_devs) == 1
        assert rgb_devs[0].device_id == "dev-006"

        light_devs = directory.resolve("phòng ngủ", "light", home_id="home-1")
        assert len(light_devs) == 1
        assert light_devs[0].device_id == "dev-003"

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_resolve_light_with_device_name_hint(self, directory):
        """Tìm 'đèn ngủ' với hint='ngủ' trong phòng ngủ -> trả đúng dev-003."""
        directory.refresh()
        devs = directory.resolve("phòng ngủ", "light", home_id="home-1", device_name_hint="ngủ")
        assert len(devs) == 1
        assert devs[0].device_id == "dev-003"

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_resolve_light_is_all(self, directory):
        """is_all=True cho 'light' trong phòng ngủ -> trả cả đèn thường và đèn LED RGB."""
        directory.refresh()
        devs = directory.resolve("phòng ngủ", "light", home_id="home-1", is_all=True)
        assert len(devs) == 2
        dev_ids = {d.device_id for d in devs}
        assert dev_ids == {"dev-003", "dev-006"}

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_resolve_light_fallback_to_led_rgb(self, directory):
        """Phòng chỉ có đèn LED (Ban công) mà không có đèn thường -> fallback điều khiển đèn LED."""
        directory.refresh()
        devs = directory.resolve("ban công", "light", home_id="home-1")
        assert len(devs) == 1
        assert devs[0].device_id == "dev-007"

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_cache_miss_triggers_refresh(self, config):
        """Cache miss → tự động refresh rồi thử lại."""
        call_count = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            return httpx.Response(200, json=SAMPLE_DEVICES)

        transport = httpx.MockTransport(handler)
        client = httpx.Client(base_url=config.base_url, transport=transport)
        directory = DeviceDirectory(config=config, http_client=client)

        # Không refresh trước, resolve trực tiếp
        devices = directory.resolve("phòng khách", "light", home_id="home-1")
        assert len(devices) == 2
        # Phải có ít nhất 1 lần gọi API (do cache miss)
        assert call_count >= 1


class TestBackgroundRefresh:
    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_start_stop(self, directory):
        """Start/stop background refresh thread."""
        directory.start()
        time.sleep(0.2)  # Đợi thread chạy 1 lần
        assert directory.cache_size > 0
        directory.stop()

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_start_twice_no_duplicate(self, directory):
        """Gọi start() 2 lần không tạo 2 thread."""
        directory.start()
        directory.start()  # Không lỗi, cảnh báo
        time.sleep(0.1)
        directory.stop()


class TestAuthHeader:
    def test_missing_api_key_env(self, config):
        """Thiếu INTERNAL_API_KEY → vẫn gọi nhưng có thể bị 401."""
        request_headers = {}

        def handler(request: httpx.Request) -> httpx.Response:
            request_headers.update(dict(request.headers))
            return httpx.Response(200, json=[])

        transport = httpx.MockTransport(handler)
        client = httpx.Client(base_url=config.base_url, transport=transport)
        directory = DeviceDirectory(config=config, http_client=client)

        with patch.dict("os.environ", {}, clear=True):
            directory.refresh()

        # Header vẫn được gửi, nhưng giá trị rỗng
        assert "x-internal-api-key" in request_headers

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "secret-key-456"})
    def test_correct_api_key_sent(self, config):
        """API key từ env phải được gửi đúng trong header."""
        request_headers = {}

        def handler(request: httpx.Request) -> httpx.Response:
            request_headers.update(dict(request.headers))
            return httpx.Response(200, json=[])

        transport = httpx.MockTransport(handler)
        client = httpx.Client(base_url=config.base_url, transport=transport)
        directory = DeviceDirectory(config=config, http_client=client)

        directory.refresh()

        assert request_headers.get("x-internal-api-key") == "secret-key-456"
