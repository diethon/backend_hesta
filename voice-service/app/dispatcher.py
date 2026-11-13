"""
HESTA Voice Service — Dispatcher (Mục 2.1 bước 5)
Gọi REST nội bộ tới Spring Boot để thực thi hành động.

Sử dụng httpx + header X-Internal-Api-Key.
voice-service KHÔNG publish MQTT trực tiếp — đó là trách nhiệm của Spring Boot.
"""

from __future__ import annotations

import concurrent.futures
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
    request_timeout_seconds: float = 3.0
    max_workers: int = 10
    max_connections: int = 50
    max_keepalive_connections: int = 20
    keepalive_expiry: float = 30.0


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

    def summary_message(self, action: str, device_type: str, room: str | None = None, parameters: dict[str, Any] | None = None, pattern: str = "", hint: str = "") -> str:
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
            "SET_COLOR": "đổi màu",
            "SET_RGB": "đổi màu",
        }.get(action, action)

        pattern_lower = pattern.lower() if pattern else ""
        hint_lower = hint.lower() if hint else ""
        if device_type == "led_rgb":
            if "rgb" in pattern_lower or "rgb" in hint_lower:
                type_vi = "đèn RGB"
            elif "led" in pattern_lower or "led" in hint_lower:
                type_vi = "đèn LED"
            else:
                type_vi = "đèn LED RGB"
        elif device_type == "light" and (hint_lower == "ngủ" or "den ngu" in pattern_lower or "đèn ngủ" in pattern_lower):
            type_vi = "đèn ngủ"
            if room == "phòng ngủ":
                room = None  # tự nhiên hơn: "Đã bật đèn ngủ" thay vì "Đã bật đèn ngủ phòng ngủ"
        else:
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
                "socket": "ổ cắm",
                "tv": "tivi",
                "all": "thiết bị",
            }.get(device_type, device_type)

        is_all_cmd = (
            device_type == "all"
            or any(w in pattern_lower for w in ["tất cả", "tat ca", "toàn bộ", "toan bo", "hết", "het", "cả nhà", "ca nha"])
        )

        room_str = f" {room}" if room else ""
        if not room and (device_type == "all" or is_all_cmd) and self.total_devices > 1:
            room_str = " trong nhà"
        
        param_str = ""
        if parameters:
            if "mode" in parameters:
                param_str = f" chế độ {parameters['mode']}"
                if action in ("SET_COLOR", "SET_RGB", "SET_BRIGHTNESS"):
                    if "đổi" in pattern_lower or "chuyển" in pattern_lower:
                        action_vi = "chuyển"
                    else:
                        action_vi = "bật"
            elif "temperature" in parameters:
                param_str = f" về {parameters['temperature']} độ"
            elif "color" in parameters:
                param_str = f" sang màu {parameters['color']}"
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
                if is_all_cmd:
                    return f"Đã {action_vi} tất cả {self.successful_count} {type_vi}{room_str}{param_str}"
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
            limits = httpx.Limits(
                max_keepalive_connections=self.config.max_keepalive_connections,
                max_connections=self.config.max_connections,
                keepalive_expiry=self.config.keepalive_expiry,
            )
            self._client = httpx.Client(
                base_url=self.config.base_url,
                timeout=self.config.request_timeout_seconds,
                limits=limits,
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

        Dùng khi có nhiều thiết bị cùng loại trong 1 phòng hoặc toàn nhà.
        Dispatch song song (parallel) qua ThreadPoolExecutor kết hợp connection pooling
        giúp giảm thời gian trễ từ N * delay xuống ~1 * delay.

        Args:
            device_ids: danh sách device ID
            action: hành động
            parameters: tham số chung cho tất cả

        Returns:
            DispatchBatchResult tổng hợp.
        """
        if not device_ids:
            return DispatchBatchResult(
                results=[],
                total_devices=0,
                successful_count=0,
                failed_count=0,
                total_duration_ms=0.0,
            )

        t0 = time.perf_counter()
        results: list[DispatchResult] = []

        if len(device_ids) == 1:
            results.append(self.dispatch(device_ids[0], action, parameters))
        else:
            workers = min(self.config.max_workers, len(device_ids))
            with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
                results = list(executor.map(lambda d_id: self.dispatch(d_id, action, parameters), device_ids))

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
            "Batch dispatch: %d/%d success, %.0fms total (parallel across %d devices)",
            success_count, len(device_ids), total_duration, len(device_ids),
        )

        return batch_result

    def close(self) -> None:
        """Đóng HTTP client nếu tự tạo."""
        if self._owns_client and self._client is not None:
            self._client.close()
            self._client = None
