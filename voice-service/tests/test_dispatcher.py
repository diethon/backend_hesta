"""
HESTA Voice Service — Test Dispatcher
Integration test cho dispatcher.py (Mục 11)

Test:
- Đúng header X-Internal-Api-Key
- Đúng payload
- Test trường hợp bị từ chối (401/403)
- Batch dispatch cho nhiều thiết bị
"""

from unittest.mock import patch

import httpx
import pytest

from app.dispatcher import Dispatcher, DispatchConfig, DispatchResult, DispatchBatchResult


# --- Fixtures ---

def make_mock_transport(
    status_code: int = 200,
    response_body: dict | None = None,
    require_api_key: bool = True,
    expected_api_key: str = "test-key-123",
) -> httpx.MockTransport:
    """Tạo mock transport cho httpx.Client mô phỏng Spring Boot."""

    def handler(request: httpx.Request) -> httpx.Response:
        # Kiểm tra auth
        if require_api_key:
            api_key = request.headers.get("X-Internal-Api-Key", "")
            if not api_key:
                return httpx.Response(401, json={"error": "Missing API key"})
            if api_key != expected_api_key:
                return httpx.Response(403, json={"error": "Invalid API key"})

        body = response_body or {"status": "ok", "message": "Action executed"}
        return httpx.Response(status_code, json=body)

    return httpx.MockTransport(handler)


@pytest.fixture
def config():
    return DispatchConfig(base_url="http://localhost:8080")


@pytest.fixture
def dispatcher(config):
    transport = make_mock_transport()
    client = httpx.Client(base_url=config.base_url, transport=transport)
    return Dispatcher(config=config, http_client=client)


# --- Tests ---

class TestDispatchSingle:
    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_dispatch_success(self, dispatcher):
        result = dispatcher.dispatch("dev-001", "TURN_ON")
        assert result.success is True
        assert result.device_id == "dev-001"
        assert result.status_code == 200
        assert result.duration_ms > 0

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_dispatch_with_parameters(self, config):
        """Gửi action có tham số (VD: nhiệt độ)."""
        received_payload = {}

        def handler(request: httpx.Request) -> httpx.Response:
            import json
            nonlocal received_payload
            received_payload = json.loads(request.content)
            return httpx.Response(200, json={"status": "ok"})

        transport = httpx.MockTransport(handler)
        client = httpx.Client(base_url=config.base_url, transport=transport)
        dispatcher = Dispatcher(config=config, http_client=client)

        result = dispatcher.dispatch("dev-005", "SET_TEMPERATURE", {"temperature": 25})
        assert result.success is True
        assert received_payload["action"] == "SET_TEMPERATURE"
        assert received_payload["parameters"]["temperature"] == 25

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "wrong-key"})
    def test_dispatch_wrong_api_key(self, config):
        """API key sai → 403."""
        transport = make_mock_transport(expected_api_key="correct-key")
        client = httpx.Client(base_url=config.base_url, transport=transport)
        dispatcher = Dispatcher(config=config, http_client=client)

        result = dispatcher.dispatch("dev-001", "TURN_ON")
        assert result.success is False
        assert result.status_code == 403

    @patch.dict("os.environ", {}, clear=True)
    def test_dispatch_missing_api_key(self, config):
        """Thiếu API key → 401."""
        transport = make_mock_transport()
        client = httpx.Client(base_url=config.base_url, transport=transport)
        dispatcher = Dispatcher(config=config, http_client=client)

        result = dispatcher.dispatch("dev-001", "TURN_ON")
        assert result.success is False
        assert result.status_code == 401

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_dispatch_server_error(self, config):
        """Server trả 500 → dispatch failed."""
        transport = make_mock_transport(status_code=500)
        client = httpx.Client(base_url=config.base_url, transport=transport)
        dispatcher = Dispatcher(config=config, http_client=client)

        result = dispatcher.dispatch("dev-001", "TURN_ON")
        assert result.success is False
        assert result.status_code == 500

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_dispatch_connection_error(self, config):
        """Không kết nối được → error message."""
        dispatcher = Dispatcher(config=DispatchConfig(
            base_url="http://localhost:99999",
            request_timeout_seconds=1.0,
        ))

        result = dispatcher.dispatch("dev-001", "TURN_ON")
        assert result.success is False
        assert result.error_message != ""

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_correct_endpoint_url(self, config):
        """Endpoint URL phải chứa device_id."""
        requested_url = ""

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal requested_url
            requested_url = str(request.url)
            return httpx.Response(200, json={"status": "ok"})

        transport = httpx.MockTransport(handler)
        client = httpx.Client(base_url=config.base_url, transport=transport)
        dispatcher = Dispatcher(config=config, http_client=client)

        dispatcher.dispatch("abc-123", "TURN_ON")
        assert "abc-123" in requested_url
        assert "/internal/devices/" in requested_url


class TestDispatchBatch:
    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_batch_all_success(self, dispatcher):
        """Dispatch cho 3 thiết bị, tất cả thành công."""
        batch = dispatcher.dispatch_batch(
            ["dev-001", "dev-002", "dev-003"],
            "TURN_ON",
        )
        assert batch.total_devices == 3
        assert batch.successful_count == 3
        assert batch.failed_count == 0
        assert batch.all_success is True
        assert batch.total_duration_ms > 0

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_batch_partial_failure(self, config):
        """1 trong 3 thiết bị fail."""
        call_count = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            if call_count == 2:  # Thiết bị thứ 2 fail
                return httpx.Response(500, json={"error": "Device offline"})
            return httpx.Response(200, json={"status": "ok"})

        transport = httpx.MockTransport(handler)
        client = httpx.Client(base_url=config.base_url, transport=transport)
        dispatcher = Dispatcher(config=config, http_client=client)

        batch = dispatcher.dispatch_batch(
            ["dev-001", "dev-002", "dev-003"],
            "TURN_ON",
        )
        assert batch.total_devices == 3
        assert batch.successful_count == 2
        assert batch.failed_count == 1
        assert batch.all_success is False

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key-123"})
    def test_batch_empty_list(self, dispatcher):
        batch = dispatcher.dispatch_batch([], "TURN_ON")
        assert batch.total_devices == 0
        assert batch.successful_count == 0


class TestDispatchBatchResultMessages:
    def test_summary_single_success(self):
        batch = DispatchBatchResult(
            total_devices=1, successful_count=1, failed_count=0
        )
        msg = batch.summary_message("TURN_ON", "light", "phòng khách")
        assert "bật" in msg
        assert "đèn" in msg
        assert "phòng khách" in msg

    def test_summary_multiple_success(self):
        """Mục 2.4 bước 3: 'Đã bật 2 đèn phòng khách'."""
        batch = DispatchBatchResult(
            total_devices=2, successful_count=2, failed_count=0
        )
        msg = batch.summary_message("TURN_ON", "light", "phòng khách")
        assert "2" in msg
        assert "đèn" in msg

    def test_summary_partial_failure(self):
        batch = DispatchBatchResult(
            total_devices=3, successful_count=2, failed_count=1
        )
        msg = batch.summary_message("TURN_OFF", "light", "phòng khách")
        assert "2/3" in msg
        assert "lỗi" in msg

    def test_summary_all_failed(self):
        batch = DispatchBatchResult(
            total_devices=2, successful_count=0, failed_count=2
        )
        msg = batch.summary_message("TURN_ON", "fan")
        assert "không thể" in msg.lower()

    def test_summary_no_room(self):
        batch = DispatchBatchResult(
            total_devices=1, successful_count=1, failed_count=0
        )
        msg = batch.summary_message("TURN_ON", "light")
        assert "đèn" in msg
        # Không có tên phòng
