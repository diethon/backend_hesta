"""
HESTA Voice Service — Device Directory (Mục 2.4)
Cache + lookup (room, device_type) → [device_id, ...]

Cơ chế:
1. Cache cục bộ trong bộ nhớ: (home_id, room_name, device_type) → [device_id, ...]
2. Refresh định kỳ mỗi 60s (background thread)
3. Refresh ngay lập tức khi cache miss
4. Nhiều thiết bị cùng loại trong 1 phòng → trả TẤT CẢ, không tự chọn 1 cái
5. Không khớp → trả lỗi rõ ràng

Gọi GET /internal/devices?homeId=... với header X-Internal-Api-Key.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

logger = logging.getLogger(__name__)


@dataclass
class DeviceDirectoryConfig:
    """Cấu hình cho device directory."""
    base_url: str = "http://localhost:8080"
    devices_endpoint: str = "/internal/devices"
    api_key_env: str = "INTERNAL_API_KEY"
    refresh_interval_seconds: int = 60
    request_timeout_seconds: float = 5.0


@dataclass
class DeviceInfo:
    """Thông tin 1 thiết bị đã resolve."""
    device_id: str
    device_name: str
    device_type: str
    room_name: str
    home_id: str


class DeviceDirectory:
    """
    Cache thiết bị trong bộ nhớ, tra cứu (room, type) → device_id.

    Sử dụng:
        directory = DeviceDirectory(config)
        directory.start()  # bắt đầu refresh background
        devices = directory.resolve("phòng khách", "light", home_id="...")
        directory.stop()   # dừng refresh
    """

    def __init__(self, config: DeviceDirectoryConfig | None = None,
                 http_client: httpx.Client | None = None):
        self.config = config or DeviceDirectoryConfig()
        self._client = http_client
        self._owns_client = http_client is None

        # Cache: (home_id, room_name_lower, device_type_lower) → list[DeviceInfo]
        self._cache: dict[tuple[str, str, str], list[DeviceInfo]] = {}
        # Raw list for rebuild
        self._raw_devices: list[dict[str, Any]] = []
        self._lock = threading.Lock()
        self._refresh_thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._last_refresh_time: float = 0.0

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

    def _build_cache(self, devices: list[dict[str, Any]]) -> dict[tuple[str, str, str], list[DeviceInfo]]:
        """Xây dựng cache từ danh sách thiết bị raw."""
        cache: dict[tuple[str, str, str], list[DeviceInfo]] = {}

        # Các alias mapping từ loại phần cứng / DB sang loại chuẩn của voice intent
        TYPE_ALIASES = {
            "light": {"light", "dimmer_switch", "lamp"},
            "led_rgb": {"led_rgb", "rgb_light", "led", "rgb", "strip_light", "light_rgb"},
            "fan": {"fan", "exhaust_fan", "ceiling_fan"},
            "air_conditioner": {"air_conditioner", "ac", "hvac", "máy lạnh", "điều hòa"},
            "door_lock": {"door_lock", "smart_lock", "lock"},
            "water_heater": {"water_heater", "boiler"},
            "water_pump": {"water_pump", "pump"},
            "curtain": {"curtain", "blind", "shade"},
            "gate": {"gate", "rolling_door", "garage_door"},
            "socket": {"socket", "plug", "outlet"},
            "speaker": {"speaker", "audio"},
        }

        for dev in devices:
            try:
                db_type = str(dev.get("deviceType", dev.get("type", ""))).strip().lower()
                if not db_type or "id" not in dev:
                    continue
                db_name = dev.get("name", "").lower()
                
                # Bản thân nó là type gì thì lưu type đó
                types_to_register = {db_type}
                
                # Kiểm tra thiết bị có phải là LED / RGB hay không
                is_rgb_led = (
                    db_type in {"led_rgb", "led", "rgb", "rgb_light", "strip_light", "light_rgb"}
                    or any(w in db_name for w in ["rgb", "led", "đổi màu", "doi mau", "dải led", "dai led", "dây led", "day led"])
                )

                # Nếu không phải cảm biến, phân loại thêm theo aliases và tên
                if "sensor" not in db_type:
                    if is_rgb_led:
                        types_to_register.add("led_rgb")
                    elif db_type in TYPE_ALIASES["light"] or any(w in db_name for w in ["đèn", "light", "lamp", "bóng đèn"]):
                        types_to_register.add("light")

                    for standard_type, aliases in TYPE_ALIASES.items():
                        if standard_type not in {"light", "led_rgb"}:
                            if db_type in aliases:
                                types_to_register.add(standard_type)

                    if any(w in db_name for w in ["quạt", "fan"]):
                        types_to_register.add("fan")
                    if any(w in db_name for w in ["điều hòa", "điều hoà", "máy lạnh", "ac"]):
                        types_to_register.add("air_conditioner")
                    if any(w in db_name for w in ["khóa", "khoá", "cửa"]):
                        types_to_register.add("door_lock")
                    if any(w in db_name for w in ["cổng"]):
                        types_to_register.add("gate")
                    if any(w in db_name for w in ["nóng lạnh", "bình nóng", "nước nóng"]):
                        types_to_register.add("water_heater")
                    if any(w in db_name for w in ["bơm", "máy bơm"]):
                        types_to_register.add("water_pump")
                    if any(w in db_name for w in ["rèm", "màn"]):
                        types_to_register.add("curtain")

                # Nếu nó là bộ điều khiển hồng ngoại (IR_REMOTE), phân loại thêm dựa vào tên
                if db_type == "ir_remote":
                    if "điều hòa" in db_name or "điều hoà" in db_name or "máy lạnh" in db_name or "ac" in db_name:
                        types_to_register.add("air_conditioner")
                    if "tv" in db_name or "tivi" in db_name:
                        types_to_register.add("tv")
                    if "quạt" in db_name or "fan" in db_name:
                        types_to_register.add("fan")

                for t in types_to_register:
                    info = DeviceInfo(
                        device_id=str(dev["id"]),
                        device_name=dev.get("name", ""),
                        device_type=t,
                        room_name=str(dev.get("roomName", dev.get("room", ""))).strip().lower(),
                        home_id=str(dev.get("homeId", dev.get("home_id", ""))),
                    )
                    key = (info.home_id, info.room_name, t)
                    if key not in cache:
                        cache[key] = []
                    cache[key].append(info)
            except (KeyError, TypeError) as e:
                logger.warning("Bỏ qua thiết bị lỗi dữ liệu: %s — %s", dev, e)

        return cache

    def refresh(self, home_id: str = "") -> bool:
        """Làm mới cache bằng cách gọi API backend.

        Args:
            home_id: ID nhà cần lấy thiết bị. Nếu rỗng, lấy tất cả.

        Returns:
            True nếu refresh thành công, False nếu lỗi.
        """
        try:
            client = self._get_client()
            api_key = self._get_api_key()

            params = {}
            if home_id:
                params["homeId"] = home_id

            response = client.get(
                self.config.devices_endpoint,
                params=params,
                headers={"X-Internal-Api-Key": api_key},
            )
            response.raise_for_status()

            devices = response.json()
            if not isinstance(devices, list):
                # Có thể API trả về wrapper object
                devices = devices.get("data", devices.get("devices", []))

            new_cache = self._build_cache(devices)

            with self._lock:
                self._raw_devices = devices
                self._cache = new_cache
                self._last_refresh_time = time.time()

            logger.info("Device cache refreshed: %d thiết bị, %d nhóm", len(devices), len(new_cache))
            return True

        except httpx.HTTPStatusError as e:
            logger.error("Lỗi HTTP khi refresh cache: %s %s", e.response.status_code, e.response.text[:200])
            return False
        except httpx.RequestError as e:
            logger.error("Lỗi kết nối khi refresh cache: %s", e)
            return False
        except Exception as e:
            logger.error("Lỗi không xác định khi refresh cache: %s", e)
            return False

    def resolve(self, room_name: str, device_type: str,
                home_id: str = "", device_name_hint: str = "",
                is_all: bool = False) -> list[DeviceInfo]:
        """Tra cứu thiết bị theo (room, type).

        Nếu có nhiều thiết bị cùng loại trong 1 phòng → trả TẤT CẢ (Mục 2.4 bước 3).
        Nếu cache miss → refresh ngay lập tức rồi thử lại 1 lần.

        Args:
            room_name: tên phòng (vd: "phòng khách")
            device_type: loại thiết bị (vd: "light", "led_rgb")
            home_id: ID nhà
            device_name_hint: từ khóa tên thiết bị (vd: "ngủ" cho đèn ngủ, "trần" cho đèn trần)
            is_all: nếu True (lệnh tắt hết/bật hết đèn), gom cả light và led_rgb

        Returns:
            Danh sách DeviceInfo khớp. List rỗng nếu không tìm thấy.
        """
        r_name = room_name.strip().lower() if room_name else ""
        d_type = device_type.strip().lower()
        key = (home_id, r_name, d_type)

        def _filter_hint(devs: list[DeviceInfo]) -> list[DeviceInfo]:
            if not device_name_hint or len(devs) <= 1:
                return devs
            hint_lower = device_name_hint.lower()
            filtered = [d for d in devs if hint_lower in d.device_name.lower()]
            return filtered if filtered else devs

        def _lookup() -> list[DeviceInfo]:
            # Trường hợp is_all cho đèn (bật/tắt hết đèn): trả về cả light và led_rgb
            if is_all and d_type in {"light", "led_rgb"}:
                all_devs: dict[str, DeviceInfo] = {}
                for (h, r, t), ds in self._cache.items():
                    if (not home_id or h == home_id) and (not r_name or r == r_name) and t in {"light", "led_rgb"}:
                        for d in ds:
                            all_devs[d.device_id] = d
                return list(all_devs.values())

            # Trường hợp tìm kiếm led_rgb
            if d_type == "led_rgb":
                matched: dict[str, DeviceInfo] = {}
                for (h, r, t), ds in self._cache.items():
                    if (not home_id or h == home_id) and (not r_name or r == r_name) and t == "led_rgb":
                        for d in ds:
                            matched[d.device_id] = d
                res = list(matched.values())
                return _filter_hint(res)

            # Trường hợp tìm kiếm light thông thường
            if d_type == "light":
                matched: dict[str, DeviceInfo] = {}
                for (h, r, t), ds in self._cache.items():
                    if (not home_id or h == home_id) and (not r_name or r == r_name) and t == "light":
                        for d in ds:
                            matched[d.device_id] = d
                res = list(matched.values())
                if res:
                    return _filter_hint(res)
                # Fallback: nếu phòng hoặc nhà KHÔNG CÓ đèn thường nào, fallback sang led_rgb
                fallback_led: dict[str, DeviceInfo] = {}
                for (h, r, t), ds in self._cache.items():
                    if (not home_id or h == home_id) and (not r_name or r == r_name) and t == "led_rgb":
                        for d in ds:
                            fallback_led[d.device_id] = d
                if fallback_led:
                    logger.info("Không có đèn thường, fallback sang điều khiển đèn LED/RGB: %d thiết bị", len(fallback_led))
                    return list(fallback_led.values())
                return []

            # Các loại thiết bị khác (fan, air_conditioner, gate, v.v.)
            if r_name:
                devs = self._cache.get(key, [])
                if devs:
                    return _filter_hint(list(devs))
                if not home_id:
                    matched = []
                    for (h, r, t), ds in self._cache.items():
                        if r == r_name and t == d_type:
                            matched.extend(ds)
                    if matched:
                        return _filter_hint(matched)
            else:
                matched = []
                for (h, r, t), ds in self._cache.items():
                    if t == d_type and (not home_id or h == home_id):
                        matched.extend(ds)
                if matched:
                    return _filter_hint(matched)
            return []

        with self._lock:
            devices = _lookup()

        if devices:
            logger.debug("Cache HIT: %s → %d thiết bị", key, len(devices))
            return devices

        # Cache miss → refresh ngay lập tức (Mục 2.4 bước 2)
        logger.info("Cache MISS: %s — refreshing ngay...", key)
        if self.refresh(home_id=home_id):
            with self._lock:
                devices = _lookup()
            if devices:
                logger.info("Sau refresh: tìm thấy %d thiết bị", len(devices))
                return devices

        # Vẫn không tìm thấy → trả lỗi rõ ràng (Mục 2.4 bước 4)
        logger.warning("Không tìm thấy thiết bị: room='%s', type='%s', home_id='%s'", room_name, device_type, home_id)
        return []

    def start(self, home_id: str = "") -> None:
        """Bắt đầu refresh background thread (định kỳ mỗi refresh_interval_seconds)."""
        if self._refresh_thread is not None and self._refresh_thread.is_alive():
            logger.warning("Refresh thread đã chạy rồi.")
            return

        self._stop_event.clear()

        def _refresh_loop():
            while not self._stop_event.is_set():
                self.refresh(home_id=home_id)
                self._stop_event.wait(self.config.refresh_interval_seconds)

        self._refresh_thread = threading.Thread(
            target=_refresh_loop, daemon=True, name="device-directory-refresh"
        )
        self._refresh_thread.start()
        logger.info("Device directory refresh thread started (interval=%ds)",
                     self.config.refresh_interval_seconds)

    def stop(self) -> None:
        """Dừng refresh background thread."""
        self._stop_event.set()
        if self._refresh_thread is not None:
            self._refresh_thread.join(timeout=5)
            self._refresh_thread = None
        if self._owns_client and self._client is not None:
            self._client.close()
            self._client = None
        logger.info("Device directory stopped.")

    @property
    def cache_size(self) -> int:
        """Số nhóm thiết bị trong cache."""
        with self._lock:
            return len(self._cache)

    @property
    def last_refresh_time(self) -> float:
        """Timestamp lần refresh cuối cùng."""
        return self._last_refresh_time
