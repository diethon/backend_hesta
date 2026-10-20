"""
HESTA Voice Service — Dispatcher (Mục 2.1 bước 5)
Gọi REST nội bộ tới Spring Boot để thực thi hành động.

Sử dụng httpx + header X-Internal-Api-Key.
voice-service KHÔNG publish MQTT trực tiếp — đó là trách nhiệm của Spring Boot.
"""

from __future__ import annotations

import logging
import os
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

logger = logging.getLogger(__name__)


@dataclass
class DispatchConfig:
    """Cấu hình dispatcher."""
    base_url: str = "http://localhost:8080"
    action_endpoint_template: str = "/internal/devices/{device_id}/actions"
    api_key_env: str = "INTERNAL_API_KEY"
    request_timeout_seconds: float = 8.0


@dataclass
class DispatchResult:
    """Kết quả dispatch 1 action tới 1 thiết bị."""
    device_id: str
    success: bool
    status_code: int = 0
    response_data: dict[str, Any] = field(default_factory=dict)
    error_message: str = ""
    duration_ms: float = 0.0


@dataclass
class DispatchBatchResult:
    """Kết quả dispatch cho nhiều thiết bị cùng lúc (trường hợp Mục 2.4 bước 3)."""
    results: list[DispatchResult] = field(default_factory=list)
    total_devices: int = 0
    successful_count: int = 0
    failed_count: int = 0
    total_duration_ms: float = 0.0

    @property
    def all_success(self) -> bool:
        return self.failed_count == 0 and self.successful_count > 0

    def summary_message(self, action: str, device_type: str, room: str | None = None, parameters: dict[str, Any] | None = None) -> str:
        """Tạo thông báo xác nhận cho người dùng.
        VD: 'Đã bật 2 đèn phòng khách' hoặc 'Đã chỉnh nhiệt độ điều hòa phòng ngủ về 26 độ'
        """
        action_vi = {
            "TURN_ON": "bật",
            "TURN_OFF": "tắt",
            "OPEN": "mở",
            "CLOSE": "đóng",
            "SET_TEMPERATURE": "đặt nhiệt độ",
            "INCREASE_TEMPERATURE": "tăng nhiệt độ",
            "DECREASE_TEMPERATURE": "giảm nhiệt độ",
            "SET_BRIGHTNESS": "đặt độ sáng",
            "INCREASE_BRIGHTNESS": "tăng sáng",
            "DECREASE_BRIGHTNESS": "giảm sáng",
        }.get(action, action)

        type_vi = {
            "light": "đèn",
            "fan": "quạt",
            "air_conditioner": "điều hòa",
            "door_lock": "khóa cửa",
            "curtain": "rèm",
            "water_heater": "bình nóng lạnh",
            "gate": "cổng",
            "water_pump": "máy bơm nước",
            "led_rgb": "đèn RGB",
        }.get(device_type, device_type)

        room_str = f" {room}" if room else ""
        
        param_str = ""
        if parameters:
            if "temperature" in parameters:
                param_str = f" về {parameters['temperature']} độ"
            elif "brightness" in parameters:
                param_str = f" về mức {parameters['brightness']}"
            elif "amount" in parameters:
                if "TEMPERATURE" in action:
                    param_str = f" thêm {parameters['amount']} độ"
                elif "BRIGHTNESS" in action:
                    param_str = f" thêm {parameters['amount']} mức"

        if self.all_success:
            if self.total_devices == 1:
                return f"Đã {action_vi} {type_vi}{room_str}{param_str}"
            else:
                return f"Đã {action_vi} {self.successful_count} {type_vi}{room_str}{param_str}"
        elif self.successful_count > 0:
            return (f"Đã {action_vi} {self.successful_count}/{self.total_devices} "
                    f"{type_vi}{room_str}, {self.failed_count} thiết bị lỗi")
        else:
            return f"Không thể {action_vi} {type_vi}{room_str} — tất cả thiết bị đều lỗi"


class Dispatcher:
    """
    Dispatch actions tới Spring Boot qua REST nội bộ.

    Sử dụng:
        dispatcher = Dispatcher(config)
        result = dispatcher.dispatch("dev-001", "TURN_ON")
        batch = dispatcher.dispatch_batch(["dev-001", "dev-002"], "TURN_ON")
    """

    def __init__(self, config: DispatchConfig | None = None,
                 http_client: httpx.Client | None = None):
        self.config = config or DispatchConfig()
        self._client = http_client
        self._owns_client = http_client is None

    def _get_client(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(
                base_url=self.config.base_url,
                timeout=self.config.request_timeout_seconds,
            )
        return self._client

    def _get_api_key(self) -> str:
        key = os.environ.get(self.config.api_key_env, "")
        if not key:
            logger.warning("Biến môi trường %s chưa được cấu hình!", self.config.api_key_env)
        return key

    def dispatch(self, device_id: str, action: str,
                 parameters: dict[str, Any] | None = None) -> DispatchResult:
        """Gửi 1 action tới 1 thiết bị qua REST nội bộ.

        Args:
            device_id: ID thiết bị (UUID)
            action: hành động (VD: "TURN_ON", "SET_TEMPERATURE")
            parameters: tham số bổ sung (VD: {"temperature": 25})

        Returns:
            DispatchResult với thông tin kết quả.
        """
        endpoint = self.config.action_endpoint_template.replace("{device_id}", device_id)
        payload: dict[str, Any] = {"action": action}
        if parameters:
            payload["parameters"] = parameters

        try:
            client = self._get_client()
            api_key = self._get_api_key()

            t0 = time.perf_counter()
            response = client.post(
                endpoint,
                json=payload,
                headers={"X-Internal-Api-Key": api_key},
            )
            duration_ms = (time.perf_counter() - t0) * 1000

            if response.status_code in (200, 201, 204):
                response_data = response.json() if response.content else {}
                logger.info(
                    "Dispatch OK: %s → %s (%.0fms)",
                    device_id, action, duration_ms,
                )
                return DispatchResult(
                    device_id=device_id,
                    success=True,
                    status_code=response.status_code,
                    response_data=response_data,
                    duration_ms=round(duration_ms, 1),
                )
            else:
                error_msg = response.text[:200] if response.text else f"HTTP {response.status_code}"
                logger.error(
                    "Dispatch FAILED: %s → %s — HTTP %d: %s",
                    device_id, action, response.status_code, error_msg,
                )
                return DispatchResult(
                    device_id=device_id,
                    success=False,
                    status_code=response.status_code,
                    error_message=error_msg,
                    duration_ms=round(duration_ms, 1),
                )

        except httpx.RequestError as e:
            logger.error("Dispatch ERROR: %s → %s — %s", device_id, action, e)
            return DispatchResult(
                device_id=device_id,
                success=False,
                error_message=str(e),
            )

    def dispatch_batch(self, device_ids: list[str], action: str,
                       parameters: dict[str, Any] | None = None) -> DispatchBatchResult:
        """Gửi cùng 1 action tới nhiều thiết bị (Mục 2.4 bước 3).

        Dùng khi có nhiều thiết bị cùng loại trong 1 phòng.
        Dispatch tuần tự (không parallel) — đơn giản, phù hợp quy mô MVP.

        Args:
            device_ids: danh sách device ID
            action: hành động
            parameters: tham số chung cho tất cả

        Returns:
            DispatchBatchResult tổng hợp.
        """
        t0 = time.perf_counter()
        results: list[DispatchResult] = []

        for device_id in device_ids:
            result = self.dispatch(device_id, action, parameters)
            results.append(result)

        total_duration = (time.perf_counter() - t0) * 1000
        success_count = sum(1 for r in results if r.success)

        batch_result = DispatchBatchResult(
            results=results,
            total_devices=len(device_ids),
            successful_count=success_count,
            failed_count=len(device_ids) - success_count,
            total_duration_ms=round(total_duration, 1),
        )

        logger.info(
            "Batch dispatch: %d/%d success, %.0fms total",
            success_count, len(device_ids), total_duration,
        )

        return batch_result

    def close(self) -> None:
        """Đóng HTTP client nếu tự tạo."""
        if self._owns_client and self._client is not None:
            self._client.close()
            self._client = None
