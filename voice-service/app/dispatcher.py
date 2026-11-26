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
    request_timeout_seconds: float = 4.0
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
    error_code: str = ""
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
            "STOP": "dừng",
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

        if device_type in ("curtain", "gate"):
            if "bật" in pattern_lower or "bat" in pattern_lower:
                action_vi = "bật"
            elif "tắt" in pattern_lower or "tat" in pattern_lower:
                action_vi = "tắt"
            elif "dừng" in pattern_lower or "dung" in pattern_lower:
                action_vi = "dừng"
        elif device_type == "door_lock":
            if action == "TURN_ON":
                action_vi = "mở"
            elif action == "TURN_OFF":
                action_vi = "khóa" if ("khóa" in pattern_lower or "khoa" in pattern_lower) else "đóng"

        if device_type == "led_rgb":
            if "rgb" in pattern_lower or "rgb" in hint_lower:
                type_vi = "đèn RGB"
            elif "led" in pattern_lower or "led" in hint_lower:
                type_vi = "đèn LED"
            else:
                type_vi = "đèn LED RGB"
        elif device_type == "gate":
            if any(w in pattern_lower for w in ["cửa cuốn", "cua cuon", "cửa cuống", "cua cuong", "cửa gara", "cua gara", "cửa garage", "cua garage"]):
                type_vi = "cửa cuốn"
            else:
                type_vi = "cổng"
        elif device_type == "curtain":
            if any(w in pattern_lower for w in ["rèm cửa", "rem cua"]):
                type_vi = "rèm cửa"
            elif any(w in pattern_lower for w in ["rèm 2 mảnh", "rèm hai mảnh", "rem 2 manh", "rem hai manh"]):
                type_vi = "rèm hai mảnh"
            elif any(w in pattern_lower for w in ["màn", "man"]):
                type_vi = "màn"
            else:
                type_vi = "rèm"
        elif device_type == "door_lock":
            if "cửa chính" in pattern_lower or "cua chinh" in pattern_lower:
                type_vi = "cửa chính"
            elif action_vi == "khóa":
                type_vi = "cửa"
            elif "khóa" in pattern_lower or "khoa" in pattern_lower:
                type_vi = "khóa cửa"
            else:
                type_vi = "cửa"
        elif device_type == "light" and (hint_lower == "ngủ" or "den ngu" in pattern_lower or "đèn ngủ" in pattern_lower):
            type_vi = "đèn ngủ"
            if room == "phòng ngủ":
                room = None  # tự nhiên hơn: "Đã bật đèn ngủ" thay vì "Đã bật đèn ngủ phòng ngủ"
        elif device_type == "light" and (
            hint_lower in ("chiếu tv", "chieu tv", "chiếu tivi", "chieu tivi")
            or any(k in pattern_lower for k in ("chiếu tv", "chieu tv", "chiếu tivi", "chieu tivi", "đèn tv", "đèn tivi", "den tv", "den tivi"))
        ):
            type_vi = "đèn chiếu TV"
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
            failed_results = [r for r in self.results if not r.success]
            denied_count = sum(
                1 for r in failed_results
                if r.error_code in ("ACCESS_DENIED", "UNAUTHORIZED") or r.status_code in (401, 403, 404)
            )
            offline_count = sum(
                1 for r in failed_results
                if r.error_code in ("DEVICE_OFFLINE", "GATEWAY_DISCONNECTED")
            )
            other_err_count = len(failed_results) - denied_count - offline_count

            # Nếu toàn bộ thiết bị còn lại chỉ bị bỏ qua do không có quyền (không có lỗi kỹ thuật):
            # Không báo "lỗi" và không để lộ số lượng thiết bị ẩn ở các phòng bị cấm.
            if failed_results and denied_count == len(failed_results):
                return (
                    f"Đã {action_vi} {self.successful_count} {type_vi}{room_str}{param_str} "
                    f"trong phạm vi quyền của bạn"
                )

            reasons: list[str] = []
            if denied_count > 0:
                reasons.append(f"{denied_count} thiết bị không có quyền")
            if offline_count > 0:
                reasons.append(f"{offline_count} thiết bị ngoại tuyến")
            if other_err_count > 0:
                reasons.append(f"{other_err_count} thiết bị lỗi")
            if not reasons:
                reasons.append(f"{self.failed_count} thiết bị lỗi")

            if denied_count > 0:
                return (
                    f"Đã {action_vi} {self.successful_count} {type_vi}{room_str}{param_str}, "
                    + ", ".join(reasons)
                )
            return (
                f"Đã {action_vi} {self.successful_count}/{self.total_devices} "
                f"{type_vi}{room_str}, " + ", ".join(reasons)
            )
        else:
            # Phân loại nguyên nhân lỗi chi tiết dựa theo kết quả phản hồi từ Backend
            err_codes = {r.error_code for r in self.results if r.error_code}
            if "ACCESS_DENIED" in err_codes or any(r.status_code == 403 for r in self.results):
                return f"Bạn không có quyền {action_vi} {type_vi}{room_str}."
            if "UNAUTHORIZED" in err_codes or any(r.status_code == 401 for r in self.results):
                return f"Bạn chưa đăng nhập hoặc không có quyền điều khiển {type_vi}{room_str}."
            if "DEVICE_OFFLINE" in err_codes:
                return f"{type_vi.capitalize()}{room_str} hiện đang ngoại tuyến hoặc chưa kết nối."
            if "GATEWAY_DISCONNECTED" in err_codes:
                return f"Không thể kết nối đến Gateway để {action_vi} {type_vi}{room_str}."
            return f"Không thể {action_vi} {type_vi}{room_str} — thiết bị không phản hồi."


@dataclass
class SceneDispatchResult:
    """Kết quả thực thi / tắt 1 kịch bản qua REST nội bộ (/internal/scenes/{id}/execute|deactivate)."""
    scene_id: str
    scene_name: str
    mode: str = "ACTIVATE"  # ACTIVATE hoặc DEACTIVATE
    success: bool = False
    status: str = "FAILED"  # SUCCESS, PARTIAL, ALREADY_IN_STATE, FAILED, SKIPPED
    status_code: int = 0
    total_devices: int = 0
    executed_count: int = 0
    already_in_state_count: int = 0
    conflict_resolved_count: int = 0
    overridden_count: int = 0
    overridden_scene_name: str | None = None
    offline_count: int = 0
    failed_count: int = 0
    skipped_count: int = 0
    error_code: str | None = None
    error_message: str | None = None
    duration_ms: float = 0.0

    def summary_message(self) -> str:
        """Sinh câu phản hồi tiếng Việt tự nhiên, rõ ràng về xung đột và trạng thái kịch bản."""
        name = self.scene_name
        is_deactivate = self.mode.upper() == "DEACTIVATE"
        verb = "tắt" if is_deactivate else "bật"

        if self.error_code in ("ACCESS_DENIED", "UNAUTHORIZED") or self.status_code in (401, 403):
            return f"Bạn không có quyền {verb} kịch bản {name}."
        if self.error_code == "SCENE_DISABLED":
            return f"Kịch bản {name} hiện đang bị vô hiệu hóa."
        if self.error_code == "SCENE_NOT_FOUND" or self.status_code == 404:
            return f"Không tìm thấy kịch bản {name}."
        if self.error_code == "CONNECTION_ERROR":
            return f"Không thể kết nối máy chủ để {verb} kịch bản {name}."

        if is_deactivate:
            if self.status == "ALREADY_IN_STATE":
                return f"Các thiết bị trong kịch bản {name} đều đã tắt sẵn rồi."
            if self.status == "SKIPPED":
                return f"Đã dừng kịch bản {name}."
            if self.status == "SUCCESS":
                if self.already_in_state_count > 0 and self.executed_count > 0:
                    return f"Đã tắt kịch bản {name}, tắt {self.executed_count} thiết bị và giữ nguyên {self.already_in_state_count} thiết bị đã tắt."
                return f"Đã tắt kịch bản {name}."
            if self.status == "PARTIAL":
                ok_total = self.executed_count + self.already_in_state_count
                if self.offline_count > 0:
                    return f"Đã tắt kịch bản {name} cho {ok_total} thiết bị, {self.offline_count} thiết bị đang ngoại tuyến."
                return f"Đã tắt kịch bản {name} cho {ok_total} thiết bị, {self.failed_count} thiết bị không phản hồi."
            if self.offline_count > 0:
                return f"Không thể tắt kịch bản {name}, thiết bị hiện đang ngoại tuyến."
            return f"Không thể tắt kịch bản {name}, thiết bị không phản hồi."

        # ACTIVATE
        if self.status == "ALREADY_IN_STATE":
            return f"Kịch bản {name} đã sẵn sàng, tất cả thiết bị đều đang ở đúng trạng thái."

        if self.status == "SKIPPED":
            return f"Kịch bản {name} chưa có hành động thiết bị nào."

        if self.status == "SUCCESS":
            if self.overridden_scene_name:
                base = f"Đã chuyển từ kịch bản {self.overridden_scene_name} sang kịch bản {name}"
            else:
                base = f"Đã bật kịch bản {name}"

            extras: list[str] = []
            if self.already_in_state_count > 0 and self.executed_count > 0:
                extras.append(f"điều chỉnh {self.executed_count} thiết bị, {self.already_in_state_count} thiết bị đã đúng trạng thái")
            if self.conflict_resolved_count > 0:
                extras.append("đã tự động ưu tiên trạng thái sau cùng cho thiết bị cấu hình trùng")

            if extras:
                return f"{base}, " + "; ".join(extras) + "."
            return f"{base}."

        if self.status == "PARTIAL":
            ok_total = self.executed_count + self.already_in_state_count
            if self.overridden_scene_name:
                prefix = f"Đã chuyển sang kịch bản {name} cho {ok_total} thiết bị"
            else:
                prefix = f"Đã bật kịch bản {name} cho {ok_total} thiết bị"
            if self.offline_count > 0 and self.failed_count == 0:
                return f"{prefix}, {self.offline_count} thiết bị đang ngoại tuyến."
            if self.offline_count > 0:
                return f"{prefix}, {self.offline_count} thiết bị ngoại tuyến và {self.failed_count} thiết bị lỗi."
            return f"{prefix}, {self.failed_count} thiết bị không phản hồi."

        if self.offline_count > 0:
            return f"Không thể bật kịch bản {name}, các thiết bị hiện đang ngoại tuyến."
        return f"Không thể bật kịch bản {name}, thiết bị không phản hồi."


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
                 parameters: dict[str, Any] | None = None,
                 user_id: str = "") -> DispatchResult:
        """Gửi 1 action tới 1 thiết bị qua REST nội bộ.

        Args:
            device_id: ID thiết bị (UUID)
            action: hành động (VD: "TURN_ON", "SET_TEMPERATURE")
            parameters: tham số bổ sung (VD: {"temperature": 25})
            user_id: ID người dùng ra lệnh (nếu có để kiểm tra phân quyền RBAC)

        Returns:
            DispatchResult với thông tin kết quả.
        """
        endpoint = self.config.action_endpoint_template.replace("{device_id}", device_id)
        payload: dict[str, Any] = {"action": action}
        if parameters:
            payload["parameters"] = parameters
        if user_id:
            payload["userId"] = user_id

        try:
            client = self._get_client()
            api_key = self._get_api_key()

            headers = {"X-Internal-Api-Key": api_key}
            if user_id:
                headers["X-User-Id"] = user_id

            t0 = time.perf_counter()
            response = client.post(
                endpoint,
                json=payload,
                headers=headers,
            )
            duration_ms = (time.perf_counter() - t0) * 1000

            if response.status_code in (200, 201, 204):
                response_data = response.json() if response.content else {}
                # Kiểm tra ApiResponse wrapper và CommandResult từ Spring Boot
                result_obj = response_data.get("result", {})
                if isinstance(result_obj, dict):
                    cmd_success = result_obj.get("success", True)
                    cmd_err_code = result_obj.get("errorCode", "")
                    cmd_msg = result_obj.get("message", "")
                    if not cmd_success:
                        logger.warning(
                            "Dispatch rejected by backend: %s → %s — [%s] %s",
                            device_id, action, cmd_err_code, cmd_msg,
                        )
                        return DispatchResult(
                            device_id=device_id,
                            success=False,
                            status_code=response.status_code,
                            response_data=response_data,
                            error_code=cmd_err_code or "FAILED",
                            error_message=cmd_msg or "Thực thi thất bại",
                            duration_ms=round(duration_ms, 1),
                        )

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
            elif response.status_code in (401, 403) or (response.status_code == 404 and user_id):
                if response.status_code == 401:
                    err_code = "UNAUTHORIZED"
                    err_msg = "Chưa xác thực người dùng"
                else:
                    # Backend fail-closed trả 403 (VIEW) hoặc 404 (NONE) cho thiết bị ngoài phạm vi quyền
                    err_code = "ACCESS_DENIED"
                    err_msg = "Bạn không có quyền điều khiển thiết bị này"
                logger.warning("Dispatch permission error: %s → %s — HTTP %d: %s", device_id, action, response.status_code, err_msg)
                return DispatchResult(
                    device_id=device_id,
                    success=False,
                    status_code=response.status_code,
                    error_code=err_code,
                    error_message=err_msg,
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
                    error_code="INTERNAL_ERROR",
                    error_message=error_msg,
                    duration_ms=round(duration_ms, 1),
                )

        except httpx.RequestError as e:
            duration_ms = (time.perf_counter() - t0) * 1000
            logger.error("Dispatch ERROR: %s → %s — %s", device_id, action, e)
            return DispatchResult(
                device_id=device_id,
                success=False,
                error_code="CONNECTION_ERROR",
                error_message=str(e),
                duration_ms=round(duration_ms, 1),
            )

    def dispatch_batch(self, device_ids: list[str], action: str,
                       parameters: dict[str, Any] | None = None,
                       user_id: str = "") -> DispatchBatchResult:
        """Gửi cùng 1 action tới nhiều thiết bị (Mục 2.4 bước 3).

        Dùng khi có nhiều thiết bị cùng loại trong 1 phòng hoặc toàn nhà.
        Dispatch song song (parallel) qua ThreadPoolExecutor kết hợp connection pooling
        giúp giảm thời gian trễ từ N * delay xuống ~1 * delay.

        Args:
            device_ids: danh sách device ID
            action: hành động
            parameters: tham số chung cho tất cả
            user_id: ID người dùng ra lệnh (để kiểm tra phân quyền)

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
            results.append(self.dispatch(device_ids[0], action, parameters, user_id=user_id))
        else:
            workers = min(self.config.max_workers, len(device_ids))
            with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
                results = list(executor.map(lambda d_id: self.dispatch(d_id, action, parameters, user_id=user_id), device_ids))

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

    def dispatch_scene(
        self,
        scene_id: str,
        scene_name: str,
        mode: str = "ACTIVATE",
        home_id: str = "",
        user_id: str = "",
    ) -> SceneDispatchResult:
        """Gửi lệnh kích hoạt (/execute) hoặc tắt (/deactivate) kịch bản tới Spring Boot."""
        action_suffix = "deactivate" if mode.upper() == "DEACTIVATE" else "execute"
        endpoint = f"/internal/scenes/{scene_id}/{action_suffix}"
        payload: dict[str, Any] = {}
        if home_id:
            payload["homeId"] = home_id
        if user_id:
            payload["userId"] = user_id

        headers = {"X-Internal-Api-Key": self._get_api_key()}
        if user_id:
            headers["X-User-Id"] = user_id

        try:
            client = self._get_client()
            t0 = time.perf_counter()
            response = client.post(endpoint, json=payload, headers=headers, timeout=max(self.config.request_timeout_seconds, 10.0))
            duration_ms = round((time.perf_counter() - t0) * 1000, 1)

            data = response.json() if response.content else {}
            if response.status_code in (200, 201):
                res_obj = data.get("result") if isinstance(data, dict) else {}
                if not isinstance(res_obj, dict):
                    res_obj = {}
                return SceneDispatchResult(
                    scene_id=scene_id,
                    scene_name=str(res_obj.get("sceneName") or scene_name),
                    mode=str(res_obj.get("mode") or mode).upper(),
                    success=bool(res_obj.get("success", True)),
                    status=str(res_obj.get("status") or "SUCCESS"),
                    status_code=response.status_code,
                    total_devices=int(res_obj.get("totalDevices") or 0),
                    executed_count=int(res_obj.get("executedCount") or 0),
                    already_in_state_count=int(res_obj.get("alreadyInStateCount") or 0),
                    conflict_resolved_count=int(res_obj.get("conflictResolvedCount") or 0),
                    overridden_count=int(res_obj.get("overriddenCount") or 0),
                    overridden_scene_name=res_obj.get("overriddenSceneName"),
                    offline_count=int(res_obj.get("offlineCount") or 0),
                    failed_count=int(res_obj.get("failedCount") or 0),
                    skipped_count=int(res_obj.get("skippedCount") or 0),
                    error_code=res_obj.get("errorCode"),
                    duration_ms=duration_ms,
                )

            err_msg = str(data.get("message") or response.text) if isinstance(data, dict) else response.text
            err_code = None
            if response.status_code == 401:
                err_code = "UNAUTHORIZED"
            elif response.status_code == 403:
                err_code = "ACCESS_DENIED"
            elif response.status_code == 404:
                err_code = "SCENE_NOT_FOUND"
            elif "disabled" in err_msg.lower() or "vô hiệu" in err_msg.lower() or (isinstance(data, dict) and data.get("code") == 3006):
                err_code = "SCENE_DISABLED"

            return SceneDispatchResult(
                scene_id=scene_id,
                scene_name=scene_name,
                mode=mode.upper(),
                success=False,
                status="FAILED",
                status_code=response.status_code,
                error_code=err_code or "FAILED",
                error_message=err_msg,
                duration_ms=duration_ms,
            )
        except Exception as e:
            logger.error("Dispatch scene error (%s %s): %s", mode, scene_id, e)
            return SceneDispatchResult(
                scene_id=scene_id,
                scene_name=scene_name,
                mode=mode.upper(),
                success=False,
                status="FAILED",
                error_code="CONNECTION_ERROR",
                error_message=str(e),
            )

    def close(self) -> None:
        """Đóng HTTP client nếu tự tạo."""
        if self._owns_client and self._client is not None:
            self._client.close()
            self._client = None
