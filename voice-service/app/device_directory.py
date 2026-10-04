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

        for dev in devices:
            try:
                db_type = str(dev.get("deviceType", dev.get("type", ""))).lower()
                db_name = dev.get("name", "").lower()
                
                # Bản thân nó là type gì thì lưu type đó
                types_to_register = [db_type]
                
                # Nếu nó là bộ điều khiển hồng ngoại (IR_REMOTE), phân loại thêm dựa vào tên
                if db_type == "ir_remote":
                    if "điều hòa" in db_name or "điều hoà" in db_name or "máy lạnh" in db_name or "ac" in db_name:
                        types_to_register.append("air_conditioner")
                    if "tv" in db_name or "tivi" in db_name:
                        types_to_register.append("tv")
                    if "quạt" in db_name or "fan" in db_name:
                        types_to_register.append("fan")

                for t in types_to_register:
                    info = DeviceInfo(
                        device_id=str(dev["id"]),
                        device_name=dev.get("name", ""),
                        device_type=t,
                        room_name=str(dev.get("roomName", dev.get("room", ""))).lower(),
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
                home_id: str = "") -> list[DeviceInfo]:
        """Tra cứu thiết bị theo (room, type).

        Nếu có nhiều thiết bị cùng loại trong 1 phòng → trả TẤT CẢ (Mục 2.4 bước 3).
        Nếu cache miss → refresh ngay lập tức rồi thử lại 1 lần.

        Args:
            room_name: tên phòng (vd: "phòng khách")
            device_type: loại thiết bị (vd: "light")
            home_id: ID nhà

        Returns:
            Danh sách DeviceInfo khớp. List rỗng nếu không tìm thấy.
        """
        key = (home_id, room_name.lower(), device_type.lower())

        with self._lock:
            devices = self._cache.get(key, [])

        if devices:
            logger.debug("Cache HIT: %s → %d thiết bị", key, len(devices))
            return list(devices)

        # Cache miss → refresh ngay lập tức (Mục 2.4 bước 2)
        logger.info("Cache MISS: %s — refreshing ngay...", key)
        if self.refresh(home_id=home_id):
            with self._lock:
                devices = self._cache.get(key, [])
            if devices:
                logger.info("Sau refresh: tìm thấy %d thiết bị", len(devices))
                return list(devices)

        # Vẫn không tìm thấy → trả lỗi rõ ràng (Mục 2.4 bước 4)
        logger.warning("Không tìm thấy thiết bị: room='%s', type='%s'", room_name, device_type)
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
