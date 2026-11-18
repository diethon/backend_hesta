"""
TTS Manager cho Voice Service HESTA — Kiến trúc Hybrid 3 tầng.

Cung cấp:
- Tầng 1 (< 2ms): Caching đa tầng (RAM + Disk .cache/tts/ + Bundled assets/) giọng Microsoft Hoài My.
- Tầng 2 (~100–250ms): Offline Neural TTS (Piper TTS trên Orange Pi ARM64) & fallback gTTS khi cache miss.
  → Không bao giờ khựng 4 giây hay lỗi 500 khi Edge-TTS lag hoặc mất mạng internet.
- Tầng 3 (Nâng cấp ngầm): Âm thầm tải bản giọng Hoài My từ Edge-TTS ở background để các lần sau
  phát đúng 1 giọng Hoài My đồng nhất từ RAM (< 2ms).
"""

from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import os
import re
import time
import wave
from pathlib import Path
from typing import Any

logger = logging.getLogger("hesta.tts")

DEFAULT_VOICE = "vi-VN-HoaiMyNeural"
VOICE_SERVICE_ROOT = Path(__file__).resolve().parent.parent
CACHE_DIR = VOICE_SERVICE_ROOT / ".cache" / "tts"
BUNDLED_DIR = VOICE_SERVICE_ROOT / "assets" / "tts_cache"
PIPER_DIR = VOICE_SERVICE_ROOT / "assets" / "piper"
DEFAULT_PIPER_MODEL = PIPER_DIR / "vi_VN-vais1000-medium.onnx"


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


def detect_audio_mime(data: bytes) -> str:
    """Nhận diện MIME type từ header bytes (WAV từ Piper hoặc MP3 từ Edge-TTS/gTTS)."""
    if data and len(data) >= 4 and data[:4] == b"RIFF":
        return "audio/wav"
    return "audio/mpeg"


class TTSManager:
    """Quản lý sinh âm thanh Hybrid 3 tầng và bộ nhớ đệm giọng nói tiếng Việt."""

    def __init__(
        self,
        cache_dir: Path = CACHE_DIR,
        bundled_dir: Path = BUNDLED_DIR,
        default_voice: str = DEFAULT_VOICE,
        piper_model_path: Path | str | None = None,
    ):
        self.cache_dir = cache_dir
        self.bundled_dir = bundled_dir
        self.default_voice = default_voice

        env_piper = os.environ.get("PIPER_MODEL_PATH", "").strip()
        if piper_model_path is not None:
            self.piper_model_path = Path(piper_model_path)
        elif env_piper:
            self.piper_model_path = Path(env_piper)
        else:
            self.piper_model_path = DEFAULT_PIPER_MODEL

        self._memory_cache: dict[str, bytes] = {}
        self._key_locks: dict[str, asyncio.Lock] = {}
        self._warmup_task: asyncio.Task | None = None
        self._upgrade_tasks: set[str] = set()
        self._edge_cooldown_until: float = 0.0

        # Piper offline voice instance (lazy-loaded / preloaded)
        self._piper_voice: Any = None
        self._piper_checked: bool = False

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

    # ------------------------------------------------------------------
    # Piper Offline Neural TTS (Chạy 100% local trên Orange Pi ARM64)
    # ------------------------------------------------------------------

    def _load_piper_voice(self) -> Any:
        """Nạp model Piper ONNX vào RAM (1 lần duy nhất). Trả về None nếu chưa cài."""
        if self._piper_checked:
            return self._piper_voice

        self._piper_checked = True
        if not self.piper_model_path or not self.piper_model_path.is_file():
            logger.info(
                "Piper offline model chưa có tại %s (sẽ dùng RAM cache + Edge-TTS/gTTS)",
                self.piper_model_path,
            )
            return None

        try:
            from piper import PiperVoice  # type: ignore

            t0 = time.perf_counter()
            self._piper_voice = PiperVoice.load(str(self.piper_model_path))
            dt_ms = (time.perf_counter() - t0) * 1000
            logger.info(
                "✅ Đã nạp Piper Offline TTS model (%s) vào RAM trong %.0fms",
                self.piper_model_path.name,
                dt_ms,
            )
        except ImportError:
            logger.info("Thư viện piper-tts chưa được cài đặt (tùy chọn offline cho Orange Pi)")
            self._piper_voice = None
        except Exception as e:
            logger.warning("Không thể nạp Piper TTS model %s: %s", self.piper_model_path, e)
            self._piper_voice = None

        return self._piper_voice

    @property
    def is_piper_ready(self) -> bool:
        """True nếu Piper offline model đã sẵn sàng."""
        return self._load_piper_voice() is not None

    def _synthesize_piper_sync(self, text: str) -> bytes:
        """Tổng hợp giọng tiếng Việt offline bằng Piper TTS → WAV bytes (~100–180ms)."""
        voice = self._load_piper_voice()
        if voice is None:
            return b""

        buf = io.BytesIO()
        with wave.open(buf, "wb") as wav_file:
            # Tương thích cả piper-tts v1.3+ (synthesize_wav) và v1.2 (synthesize(text, wav_file))
            if hasattr(voice, "synthesize_wav"):
                voice.synthesize_wav(text, wav_file)
            else:
                voice.synthesize(text, wav_file)
        return buf.getvalue()

    async def synthesize_piper(self, text: str) -> bytes:
        """Async wrapper chạy Piper trong worker thread để không block event loop."""
        if not self.is_piper_ready:
            return b""
        try:
            t0 = time.perf_counter()
            data = await asyncio.to_thread(self._synthesize_piper_sync, text)
            if data:
                dt_ms = (time.perf_counter() - t0) * 1000
                logger.info("⚡ Piper Offline TTS generated '%s' in %.0fms (%d bytes)", text[:40], dt_ms, len(data))
            return data
        except Exception as e:
            logger.warning("Piper offline TTS error for '%s': %s", text[:40], e)
            return b""

    # ------------------------------------------------------------------
    # gTTS Fallback (Nhanh ~250ms, thuần Python cho cả Windows & ARM64)
    # ------------------------------------------------------------------

    @staticmethod
    def _synthesize_gtts_sync(text: str) -> bytes:
        """Tổng hợp nhanh tiếng Việt bằng gTTS → MP3 bytes."""
        from gtts import gTTS  # type: ignore

        buf = io.BytesIO()
        tts = gTTS(text=text, lang="vi", slow=False)
        tts.write_to_fp(buf)
        return buf.getvalue()

    async def synthesize_gtts(self, text: str, timeout_sec: float = 3.0) -> bytes:
        """Async wrapper cho gTTS với timeout an toàn."""
        try:
            t0 = time.perf_counter()
            data = await asyncio.wait_for(
                asyncio.to_thread(self._synthesize_gtts_sync, text),
                timeout=timeout_sec,
            )
            if data:
                dt_ms = (time.perf_counter() - t0) * 1000
                logger.info("⚡ gTTS fallback generated '%s' in %.0fms (%d bytes)", text[:40], dt_ms, len(data))
            return data
        except ImportError:
            return b""
        except asyncio.TimeoutError:
            logger.debug("gTTS fallback timeout for: %s", text[:40])
            return b""
        except Exception as e:
            logger.debug("gTTS fallback error for '%s': %s", text[:40], e)
            return b""

    # ------------------------------------------------------------------
    # Edge-TTS (Giọng chuẩn Microsoft Hoài My chất lượng cao)
    # ------------------------------------------------------------------

    async def _synthesize_edge(
        self,
        clean: str,
        voice: str,
        timeout_sec: float = 6.0,
        max_attempts: int = 1,
        silent_errors: bool = False,
    ) -> bytes:
        """Gọi Microsoft Edge-TTS với số lần thử kiểm soát (không block dài)."""
        import edge_tts

        for attempt in range(max_attempts):
            try:
                # Không truyền custom rate cho giọng vi-VN vì Microsoft SSML có thể lỗi NoAudioReceived
                comm = edge_tts.Communicate(clean, voice)
                mp3_bytes = bytearray()

                async def _stream():
                    async for chunk in comm.stream():
                        if chunk["type"] == "audio":
                            mp3_bytes.extend(chunk["data"])

                await asyncio.wait_for(_stream(), timeout=timeout_sec)

                result_bytes = bytes(mp3_bytes)
                if result_bytes:
                    return result_bytes
            except asyncio.TimeoutError:
                if not silent_errors:
                    logger.warning(
                        "TTS timeout (attempt %d) for text: %s", attempt + 1, clean[:40]
                    )
                else:
                    logger.debug("TTS timeout (attempt %d) for: %s", attempt + 1, clean[:40])
            except Exception as e:
                if not silent_errors:
                    logger.warning(
                        "TTS error (attempt %d): %s for text: %s",
                        attempt + 1,
                        e,
                        clean[:40],
                    )
                else:
                    logger.debug("TTS error (attempt %d) for: %s", attempt + 1, clean[:40])

            if attempt < max_attempts - 1:
                await asyncio.sleep(0.25)

        return b""

    def _schedule_edge_upgrade(self, clean: str, voice: str) -> None:
        """Đặt tác vụ nền âm thầm nâng cấp cache sang giọng Microsoft Hoài My MP3 cho các lần sau."""
        if time.monotonic() < self._edge_cooldown_until:
            return

        key = self._get_cache_key(clean, voice)
        if key in self._upgrade_tasks:
            return

        try:
            loop = asyncio.get_running_loop()
            if not loop.is_running():
                return
        except RuntimeError:
            return

        self._upgrade_tasks.add(key)

        async def _upgrade_worker():
            try:
                mp3_bytes = await self._synthesize_edge(
                    clean, voice, timeout_sec=6.0, max_attempts=1, silent_errors=True
                )
                if mp3_bytes:
                    self.save_to_cache(clean, voice, mp3_bytes)
                    logger.debug("Background upgraded TTS cache to Hoài My MP3: '%s'", clean[:40])
                else:
                    # Tạm nghỉ 60s nếu Edge-TTS đang rớt mạng hoặc rate-limit
                    self._edge_cooldown_until = time.monotonic() + 60.0
            except Exception:
                self._edge_cooldown_until = time.monotonic() + 60.0
            finally:
                self._upgrade_tasks.discard(key)

        loop.create_task(_upgrade_worker())

    # ------------------------------------------------------------------
    # Cache Management (RAM + Disk + Bundled Assets)
    # ------------------------------------------------------------------

    def get_cached_audio(self, text: str, voice: str | None = None) -> bytes | None:
        """Lấy âm thanh từ cache (RAM, Disk Cache, hoặc Bundled Assets). Trả về None nếu chưa có."""
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

        # 3. Kiểm tra Bundled Assets (assets/tts_cache có sẵn trong mã nguồn)
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

    def save_to_cache(self, text: str, voice: str, data: bytes, persist_disk: bool = True) -> None:
        """Lưu âm thanh vào RAM (và tùy chọn Disk nếu là MP3 chuẩn Hoài My)."""
        if not data:
            return
        key = self._get_cache_key(text, voice)
        self._memory_cache[key] = data

        if persist_disk:
            file_path = self.cache_dir / f"{key}.mp3"
            try:
                file_path.write_bytes(data)
            except Exception as e:
                logger.warning("Error writing disk cache for key %s: %s", key, e)

    def preload_disk_cache(self) -> int:
        """
        Nạp toàn bộ file âm thanh MP3 có sẵn trên ổ đĩa (bundled assets và disk cache) vào RAM,
        đồng thời preload Piper Offline TTS model (nếu đã cài trên Orange Pi).
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
        # Nạp sẵn Piper offline model nếu tồn tại trên đĩa
        self._load_piper_voice()
        return loaded

    def get_engine_status(self) -> dict[str, Any]:
        """Trả về trạng thái các tầng TTS để kiểm tra nhanh trên /health."""
        gtts_installed = False
        try:
            import gtts  # noqa: F401
            gtts_installed = True
        except ImportError:
            pass

        return {
            "ramCachedPhrases": len(self._memory_cache),
            "piperOfflineReady": self.is_piper_ready,
            "gttsFallbackReady": gtts_installed,
            "primaryVoice": self.default_voice,
        }

    # ------------------------------------------------------------------
    # Main Synthesize Entrypoint (3-Tier Hybrid)
    # ------------------------------------------------------------------

    async def synthesize(
        self,
        text: str,
        voice: str | None = None,
        timeout_sec: float = 6.0,
        silent_errors: bool = False,
        fast_mode: bool = False,
        allow_fallback: bool = True,
    ) -> bytes:
        """
        Sinh âm thanh tiếng Việt theo kiến trúc Hybrid 3 tầng:
        1. RAM/Disk/Bundled Cache (< 2ms) — Giọng Microsoft Hoài My.
        2. Khi cache miss:
           - Nếu fast_mode=True & có Piper Offline: trả ngay âm thanh Piper (~120ms, 100% offline),
             đồng thời nâng cấp ngầm lên Hoài My ở background.
           - Nếu chưa có Piper: thử nhanh Edge-TTS 1 lần (không retry gây khựng 4s) → fallback gTTS (~250ms).
           - Nếu allow_fallback=False (dùng cho script generate_tts_matrix.py): chỉ dùng Edge-TTS.
        """
        voice = voice or self.default_voice
        clean = clean_tts_text(text)
        if not clean:
            return b""

        # Tầng 1: Kiểm tra cache (RAM hoặc Disk) → < 2ms
        cached = self.get_cached_audio(clean, voice)
        if cached:
            return cached

        key = self._get_cache_key(clean, voice)
        async with self._get_key_lock(key):
            # Re-check sau khi acquire lock
            cached = self.get_cached_audio(clean, voice)
            if cached:
                return cached

            # Tầng 2A (Tối ưu nhất cho Orange Pi):
            # Khi cần phản hồi tức thì (fast_mode) và có Piper offline → phát ngay (~120ms),
            # lưu tạm vào RAM để lặp lại 0ms, và âm thầm tải Hoài My ở background!
            if fast_mode and allow_fallback and self.is_piper_ready:
                piper_bytes = await self.synthesize_piper(clean)
                if piper_bytes:
                    self.save_to_cache(clean, voice, piper_bytes, persist_disk=False)
                    self._schedule_edge_upgrade(clean, voice)
                    return piper_bytes

            # Tầng 2B: Thử Microsoft Edge-TTS (1 lần duy nhất ở fast_mode để tránh khựng 4s)
            edge_attempts = 1 if (fast_mode or allow_fallback) else 2
            edge_timeout = min(timeout_sec, 1.5) if fast_mode else timeout_sec
            if allow_fallback and time.monotonic() < self._edge_cooldown_until:
                edge_bytes = b""
            else:
                edge_bytes = await self._synthesize_edge(
                    clean,
                    voice,
                    timeout_sec=edge_timeout,
                    max_attempts=edge_attempts,
                    silent_errors=silent_errors or allow_fallback,
                )

            if edge_bytes:
                self.save_to_cache(clean, voice, edge_bytes, persist_disk=True)
                return edge_bytes

            if not allow_fallback:
                return b""

            # Đánh dấu Edge-TTS đang lag để các request tiếp theo không phải chờ lại timeout
            self._edge_cooldown_until = time.monotonic() + 30.0

            # Tầng 2C: Fallback offline Piper TTS (~120ms, không cần internet)
            if self.is_piper_ready:
                piper_bytes = await self.synthesize_piper(clean)
                if piper_bytes:
                    self.save_to_cache(clean, voice, piper_bytes, persist_disk=False)
                    self._schedule_edge_upgrade(clean, voice)
                    return piper_bytes

            # Tầng 2D: Fallback gTTS (~250ms, thuần Python cho Laptop/Windows)
            gtts_bytes = await self.synthesize_gtts(clean, timeout_sec=3.0)
            if gtts_bytes:
                self.save_to_cache(clean, voice, gtts_bytes, persist_disk=False)
                self._schedule_edge_upgrade(clean, voice)
                return gtts_bytes

        return b""

    def start_warmup(self, extra_phrases: list[str] | None = None) -> None:
        """Khởi động nạp trước bộ nhớ đệm: nạp disk cache vào RAM và chỉ nạp nền nếu có yêu cầu cụ thể."""
        self.preload_disk_cache()
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
                pass

    def warmup_room(self, room_name: str, device_types: list[str] | None = None) -> None:
        """Nạp trước ngầm toàn bộ câu phản hồi cho một phòng mới được tạo."""
        from app.matrix_generator import generate_phrases_for_room

        phrases = generate_phrases_for_room(room_name, device_types)
        logger.info("Scheduling TTS warmup for room '%s' (%d phrases)...", room_name, len(phrases))
        self.add_warmup_phrases(phrases)

    async def _warmup_worker(self) -> None:
        """Tải trước các câu thoại trong hàng đợi vào bộ nhớ đệm một cách nhẹ nhàng."""
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
                    data = await self.synthesize(
                        phrase,
                        self.default_voice,
                        timeout_sec=5.0,
                        silent_errors=True,
                        allow_fallback=False,
                    )
                    if data:
                        count += 1
                        consecutive_errors = 0
                    else:
                        consecutive_errors += 1
                except Exception as e:
                    logger.debug("Warmup error for '%s': %s", phrase, e)
                    consecutive_errors += 1

                if consecutive_errors >= 3:
                    logger.info(
                        "TTS background pre-warming tạm dừng (Microsoft Edge-TTS rate limit). "
                        "Các câu còn lại sẽ dùng Hybrid TTS khi người dùng gọi lệnh thực tế."
                    )
                    self._pending_warmup.clear()
                    break

                await asyncio.sleep(0.5)

        logger.info("TTS pre-warming finished. %d new phrases cached.", count)


# Singleton instance
tts_manager = TTSManager()
