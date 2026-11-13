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
        self._lock = asyncio.Lock()
        self._warmup_task: asyncio.Task | None = None

        # Đảm bảo thư mục cache tồn tại
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            self.bundled_dir.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            logger.warning("Could not create TTS cache dir: %s", e)

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

    async def synthesize(
        self,
        text: str,
        voice: str | None = None,
        timeout_sec: float = 6.0,
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

        # 2. Gọi Edge TTS qua mạng với khóa chống trùng lặp
        async with self._lock:
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
                    logger.warning(
                        "TTS timeout (attempt %d) for text: %s", attempt + 1, clean[:40]
                    )
                except Exception as e:
                    logger.warning(
                        "TTS error (attempt %d): %s for text: %s",
                        attempt + 1,
                        e,
                        clean[:40],
                    )

                if attempt == 0:
                    await asyncio.sleep(0.3)

        return b""

    def start_warmup(self, extra_phrases: list[str] | None = None) -> None:
        """Khởi động pre-warming trong background task."""
        from app.matrix_generator import generate_matrix_phrases

        # Tự động sinh ma trận phrases kết hợp với extra_phrases
        all_phrases = generate_matrix_phrases(custom_devices=None, include_compounds=True)
        if extra_phrases:
            all_phrases = list(dict.fromkeys(all_phrases + extra_phrases))

        self.add_warmup_phrases(all_phrases)

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
        """Tải trước các câu thoại trong hàng đợi vào bộ nhớ đệm."""
        if not hasattr(self, "_pending_warmup"):
            self._pending_warmup = set()

        count = 0
        total_initially = len(self._pending_warmup)
        logger.info("Starting TTS background pre-warming for %d phrases...", total_initially)

        while self._pending_warmup:
            phrase = self._pending_warmup.pop()
            if not self.get_cached_audio(phrase, self.default_voice):
                try:
                    await self.synthesize(phrase, self.default_voice)
                    count += 1
                    # Nghỉ 0.15s giữa các câu để hoàn toàn không chiếm dụng CPU và không dồn request
                    await asyncio.sleep(0.15)
                except Exception as e:
                    logger.debug("Warmup error for '%s': %s", phrase, e)

        logger.info("TTS pre-warming finished. %d new phrases cached.", count)


# Singleton instance
tts_manager = TTSManager()

