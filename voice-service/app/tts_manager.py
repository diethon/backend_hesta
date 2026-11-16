"""
TTS Manager cho Voice Service HESTA.

Cung cấp:
- Caching đa tầng (RAM LRU + Disk Cache .cache/tts/) giúp phản hồi < 5ms cho các lệnh lặp lại.
- Tự động lọc sạch ký tự emoji/markdown trước khi đọc.
- Pre-warming (khởi động sẵn bộ nhớ đệm cho các câu lệnh nhà thông minh phổ biến).
- Cơ chế retry và timeout chống treo mạng.
"""

import asyncio
import hashlib
import logging
import os
import re
from pathlib import Path

logger = logging.getLogger("hesta.tts")

DEFAULT_VOICE = "vi-VN-HoaiMyNeural"
CACHE_DIR = Path(__file__).resolve().parent.parent / ".cache" / "tts"
BUNDLED_DIR = Path(__file__).resolve().parent.parent / "assets" / "tts_cache"


def clean_tts_text(text: str) -> str:
    """Lọc sạch ký tự icon, emoji, markdown để bộ đọc phát âm tự nhiên."""
    if not text:
        return ""
    # Xóa các icon trạng thái
    cleaned = re.sub(r"[✅⚠️❌⚡📝🔒🔓💡🔌🌀❄️☀️🌡️🔊🔇]", "", text)
    # Xóa markdown bold/italic/code
    cleaned = re.sub(r"[\*_`~#]", "", cleaned)
    # Thay dấu gạch ngang dài — hoặc – bằng dấu phẩy để giọng đọc ngắt nghỉ tự nhiên
    cleaned = re.sub(r"\s*[—–]\s*", ", ", cleaned)
    # Xóa nhiều khoảng trắng
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned


class TTSManager:
    """Quản lý sinh âm thanh và bộ nhớ đệm giọng nói tiếng Việt."""

    def __init__(
        self,
        cache_dir: Path = CACHE_DIR,
        bundled_dir: Path = BUNDLED_DIR,
        default_voice: str = DEFAULT_VOICE,
    ):
        self.cache_dir = cache_dir
        self.bundled_dir = bundled_dir
        self.default_voice = default_voice
        self._memory_cache: dict[str, bytes] = {}
        self._key_locks: dict[str, asyncio.Lock] = {}
        self._warmup_task: asyncio.Task | None = None

        # Đảm bảo thư mục cache tồn tại
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            self.bundled_dir.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            logger.warning("Could not create TTS cache dir: %s", e)

    def _get_key_lock(self, key: str) -> asyncio.Lock:
        """Khóa lock riêng cho từng key để các câu thoại khác nhau có thể sinh đồng thời."""
        if key not in self._key_locks:
            self._key_locks[key] = asyncio.Lock()
        return self._key_locks[key]

    def _get_cache_key(self, text: str, voice: str) -> str:
        clean = clean_tts_text(text).lower()
        raw = f"{voice}:{clean}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    def get_cached_audio(self, text: str, voice: str | None = None) -> bytes | None:
        """Lấy âm thanh MP3 từ cache (RAM, Disk Cache, hoặc Bundled Assets). Trả về None nếu chưa có."""
        voice = voice or self.default_voice
        clean = clean_tts_text(text)
        if not clean:
            return None

        key = self._get_cache_key(clean, voice)

        # 1. Kiểm tra RAM cache
        if key in self._memory_cache:
            return self._memory_cache[key]

        # 2. Kiểm tra Disk cache (.cache/tts)
        file_path = self.cache_dir / f"{key}.mp3"
        if file_path.is_file():
            try:
                data = file_path.read_bytes()
                if data:
                    self._memory_cache[key] = data
                    return data
            except Exception as e:
                logger.warning("Error reading disk cache for key %s: %s", key, e)

        # 3. Kiểm tra Bundled Assets (assets/tts_cache có sẵn trong mã nguồn cho thiết bị mới)
        bundled_file = self.bundled_dir / f"{key}.mp3"
        if bundled_file.is_file():
            try:
                data = bundled_file.read_bytes()
                if data:
                    self._memory_cache[key] = data
                    return data
            except Exception as e:
                logger.warning("Error reading bundled assets for key %s: %s", key, e)

        return None

    def save_to_cache(self, text: str, voice: str, data: bytes) -> None:
        """Lưu âm thanh vào RAM và Disk."""
        if not data:
            return
        key = self._get_cache_key(text, voice)
        self._memory_cache[key] = data

        file_path = self.cache_dir / f"{key}.mp3"
        try:
            file_path.write_bytes(data)
        except Exception as e:
            logger.warning("Error writing disk cache for key %s: %s", key, e)

    def preload_disk_cache(self) -> int:
        """
        Nạp toàn bộ file âm thanh MP3 có sẵn trên ổ đĩa (bundled assets và disk cache) vào RAM.
        Hoạt động hoàn toàn offline, 0ms latency khi gọi và không gọi ra mạng.
        """
        loaded = 0
        for directory in (self.bundled_dir, self.cache_dir):
            if directory.is_dir():
                for p in directory.glob("*.mp3"):
                    key = p.stem
                    if key not in self._memory_cache:
                        try:
                            data = p.read_bytes()
                            if data:
                                self._memory_cache[key] = data
                                loaded += 1
                        except Exception as e:
                            logger.debug("Error preloading cache file %s: %s", p, e)

        logger.info("✅ Đã nạp %d file giọng nói từ disk cache vào RAM (sẵn sàng phản hồi tức thì)", loaded)
        return loaded

    async def synthesize(
        self,
        text: str,
        voice: str | None = None,
        timeout_sec: float = 6.0,
        silent_errors: bool = False,
    ) -> bytes:
        """
        Sinh âm thanh tiếng Việt MP3.
        Nếu đã có trong cache -> trả về ngay lập tức (< 5ms).
        Nếu chưa có -> gọi edge-tts rồi lưu vào cache.
        """
        voice = voice or self.default_voice
        clean = clean_tts_text(text)
        if not clean:
            return b""

        # 1. Kiểm tra cache (RAM hoặc Disk)
        cached = self.get_cached_audio(clean, voice)
        if cached:
            return cached

        # 2. Gọi Edge TTS qua mạng với khóa chống trùng lặp theo từng key
        key = self._get_cache_key(clean, voice)
        async with self._get_key_lock(key):
            # Re-check sau khi acquire lock
            cached = self.get_cached_audio(clean, voice)
            if cached:
                return cached

            import edge_tts

            for attempt in range(2):
                try:
                    # Lưu ý: Không truyền custom rate cho giọng vi-VN vì Microsoft SSML có thể lỗi NoAudioReceived
                    comm = edge_tts.Communicate(clean, voice)
                    mp3_bytes = bytearray()

                    async def _stream():
                        async for chunk in comm.stream():
                            if chunk["type"] == "audio":
                                mp3_bytes.extend(chunk["data"])

                    await asyncio.wait_for(_stream(), timeout=timeout_sec)

                    result_bytes = bytes(mp3_bytes)
                    if result_bytes:
                        self.save_to_cache(clean, voice, result_bytes)
                        return result_bytes
                except asyncio.TimeoutError:
                    if not silent_errors:
                        logger.warning(
                            "TTS timeout (attempt %d) for text: %s", attempt + 1, clean[:40]
                        )
                    else:
                        logger.debug("TTS timeout (attempt %d) during warmup: %s", attempt + 1, clean[:40])
                except Exception as e:
                    if not silent_errors:
                        logger.warning(
                            "TTS error (attempt %d): %s for text: %s",
                            attempt + 1,
                            e,
                            clean[:40],
                        )
                    else:
                        logger.debug("TTS error (attempt %d) during warmup: %s", attempt + 1, clean[:40])

                if attempt == 0:
                    await asyncio.sleep(0.3)

        return b""

    def start_warmup(self, extra_phrases: list[str] | None = None) -> None:
        """Khởi động nạp trước bộ nhớ đệm: nạp disk cache vào RAM và chỉ nạp nền nếu có yêu cầu cụ thể."""
        self.preload_disk_cache()
        # Không tự động quét hàng ngàn câu ma trận qua mạng để tránh bị Microsoft Edge-TTS rate-limit.
        # Chỉ nạp nếu có danh sách câu cụ thể được truyền vào (giới hạn tối đa 20 câu ưu tiên).
        if extra_phrases:
            priority_phrases = extra_phrases[:20]
            self.add_warmup_phrases(priority_phrases)

    def add_warmup_phrases(self, phrases: list[str]) -> None:
        """Thêm các câu thoại mới vào hàng đợi pre-warming nền."""
        if not hasattr(self, "_pending_warmup"):
            self._pending_warmup: set[str] = set()

        for p in phrases:
            clean = clean_tts_text(p)
            if clean and not self.get_cached_audio(clean, self.default_voice):
                self._pending_warmup.add(clean)

        if self._pending_warmup and (self._warmup_task is None or self._warmup_task.done()):
            try:
                loop = asyncio.get_running_loop()
                if loop.is_running():
                    self._warmup_task = loop.create_task(self._warmup_worker())
            except RuntimeError:
                # Chưa có running event loop (chạy trong unit test hoặc sync script)
                pass

    def warmup_room(self, room_name: str, device_types: list[str] | None = None) -> None:
        """Nạp trước ngầm toàn bộ câu phản hồi cho một phòng mới được tạo."""
        from app.matrix_generator import generate_phrases_for_room

        phrases = generate_phrases_for_room(room_name, device_types)
        logger.info("Scheduling TTS warmup for room '%s' (%d phrases)...", room_name, len(phrases))
        self.add_warmup_phrases(phrases)

    async def _warmup_worker(self) -> None:
        """Tải trước các câu thoại trong hàng đợi vào bộ nhớ đệm một cách nhẹ nhàng, không gây nghẽn mạng."""
        if not hasattr(self, "_pending_warmup"):
            self._pending_warmup = set()

        total_initially = len(self._pending_warmup)
        if total_initially == 0:
            return

        logger.info("Starting gentle TTS background pre-warming for %d phrases...", total_initially)

        count = 0
        consecutive_errors = 0

        while self._pending_warmup:
            try:
                phrase = self._pending_warmup.pop()
            except KeyError:
                break

            if not self.get_cached_audio(phrase, self.default_voice):
                try:
                    data = await self.synthesize(phrase, self.default_voice, timeout_sec=5.0, silent_errors=True)
                    if data:
                        count += 1
                        consecutive_errors = 0
                    else:
                        consecutive_errors += 1
                except Exception as e:
                    logger.debug("Warmup error for '%s': %s", phrase, e)
                    consecutive_errors += 1

                # Nếu bị Edge-TTS từ chối 3 lần liên tiếp (dấu hiệu rate limit hoặc đứt mạng)
                if consecutive_errors >= 3:
                    logger.info(
                        "TTS background pre-warming tạm dừng (Microsoft Edge-TTS rate limit). "
                        "Các câu còn lại sẽ được tạo khi người dùng gọi lệnh thực tế."
                    )
                    self._pending_warmup.clear()
                    break

                # Giãn cách 0.5s giữa các request để tôn trọng rate limit của Edge-TTS
                await asyncio.sleep(0.5)

        logger.info("TTS pre-warming finished. %d new phrases cached.", count)


# Singleton instance
tts_manager = TTSManager()

