"""
TTS Manager cho Voice Service HESTA — Kiến trúc Hybrid 3 tầng & Ghép MP3 Lệnh Kép.

Cung cấp:
- Tầng 1 (< 2ms): Caching đa tầng (RAM + Disk .cache/tts/ + Bundled assets/) giọng Microsoft Hoài My.
- Ghép lệnh kép (< 1ms): Tự động ghép trực tiếp các frame MP3 giọng Hoài My của từng lệnh con trong RAM
  → 100% đồng nhất 1 giọng Hoài My cho mọi tổ hợp lệnh kép mà không cần gọi mạng!
- Tầng 2 (Câu đơn mới): Gọi Microsoft Edge-TTS (giọng Hoài My) 1 lần nhanh; chỉ fallback sang
  Piper Offline / gTTS khi Edge-TTS lỗi hoặc mất mạng internet (chống im lặng / lỗi 500).
- Tầng 3 (Nâng cấp ngầm): Tự nâng cấp câu fallback sang giọng Hoài My ở background cho lần sau.
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


def strip_mp3_id3v2(data: bytes) -> bytes:
    """Gỡ bỏ header ID3v2 (nếu có) trước khi nối trực tiếp các frame MP3 cùng chuẩn."""
    if len(data) >= 10 and data[:3] == b"ID3":
        # Synchsafe integer size (4 bytes)
        size = (
            ((data[6] & 0x7F) << 21)
            | ((data[7] & 0x7F) << 14)
            | ((data[8] & 0x7F) << 7)
            | (data[9] & 0x7F)
        )
        offset = 10 + size
        if offset < len(data):
            return data[offset:]
    return data


class TTSManager:
    """Quản lý sinh âm thanh Hybrid 3 tầng, nối MP3 lệnh kép và bộ nhớ đệm giọng nói tiếng Việt."""

    def __init__(
        self,
        cache_dir: Path = CACHE_DIR,
        bundled_dir: Path = BUNDLED_DIR,
        default_voice: str = DEFAULT_VOICE,
        piper_model_path: Path | str | None = None,
        primary_engine: str | None = None,
    ):
        self.cache_dir = cache_dir
        self.bundled_dir = bundled_dir
        self.default_voice = default_voice
        # hoaimy (mặc định - đồng nhất 100% giọng Hoài My), piper (100% offline), gtts (100% Google)
        self.primary_engine = (
            primary_engine or os.environ.get("TTS_PRIMARY_ENGINE", "hoaimy")
        ).strip().lower()

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
    # gTTS Fallback (Nhanh ~250–600ms, thuần Python cho cả Windows & ARM64)
    # ------------------------------------------------------------------

    @staticmethod
    def _synthesize_gtts_sync(text: str) -> bytes:
        """Tổng hợp nhanh tiếng Việt bằng gTTS → MP3 bytes."""
        from gtts import gTTS  # type: ignore

        buf = io.BytesIO()
        tts = gTTS(text=text, lang="vi", slow=False)
        tts.write_to_fp(buf)
        return buf.getvalue()

    async def synthesize_gtts(self, text: str, timeout_sec: float = 3.5) -> bytes:
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
                    self.save_to_cache(clean, voice, mp3_bytes, persist_disk=True)
                    logger.debug("Background upgraded TTS cache to Hoài My MP3: '%s'", clean[:40])
                else:
                    self._edge_cooldown_until = time.monotonic() + 60.0
            except Exception:
                self._edge_cooldown_until = time.monotonic() + 60.0
            finally:
                self._upgrade_tasks.discard(key)

        loop.create_task(_upgrade_worker())

    # ------------------------------------------------------------------
    # Cache Management (RAM + Disk + Bundled Assets)
    # ------------------------------------------------------------------

    def _lookup_exact_key(self, key: str) -> bytes | None:
        if key in self._memory_cache:
            return self._memory_cache[key]

        file_path = self.cache_dir / f"{key}.mp3"
        if file_path.is_file():
            try:
                data = file_path.read_bytes()
                if data:
                    self._memory_cache[key] = data
                    return data
            except Exception as e:
                logger.warning("Error reading disk cache for key %s: %s", key, e)

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

    def get_cached_audio(self, text: str, voice: str | None = None) -> bytes | None:
        """Lấy âm thanh từ cache (RAM, Disk Cache, hoặc Bundled Assets).
        Tự động chuẩn hóa dấu chấm cuối câu để tránh miss cache oan.
        """
        voice = voice or self.default_voice
        clean = clean_tts_text(text)
        if not clean:
            return None

        # Kiểm tra chính xác trước, sau đó kiểm tra biến thể có/không có dấu chấm cuối câu
        no_dot = clean.rstrip(".")
        candidates = [clean]
        if no_dot != clean:
            candidates.append(no_dot)
        else:
            candidates.append(f"{no_dot}.")

        for candidate in candidates:
            key = self._get_cache_key(candidate, voice)
            hit = self._lookup_exact_key(key)
            if hit:
                return hit

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
            "primaryEngine": self.primary_engine,
            "ramCachedPhrases": len(self._memory_cache),
            "piperOfflineReady": self.is_piper_ready,
            "gttsFallbackReady": gtts_installed,
            "primaryVoice": self.default_voice,
        }

    # ------------------------------------------------------------------
    # Compound MP3 Stitching (< 1ms, 100% đồng nhất giọng Hoài My!)
    # ------------------------------------------------------------------

    async def synthesize_compound(
        self,
        sub_messages: list[str],
        combined_text: str,
        voice: str | None = None,
        timeout_sec: float = 3.5,
    ) -> bytes:
        """
        Sinh âm thanh cho câu lệnh kép bằng cách ghép trực tiếp các file MP3 giọng Hoài My
        của từng câu con ngay trong RAM (< 1ms nếu các câu con đã có sẵn trong cache!).

        Ví dụ:
          sub 1: 'Đã bật 2 đèn Phòng khách' (MP3 Hoài My từ RAM)
          sub 2: 'Đã đóng cổng'             (MP3 Hoài My từ RAM)
          → nối trực tiếp 2 luồng frame MP3 → 100% giọng Hoài My, 0ms latency!
        """
        voice = voice or self.default_voice
        clean_combined = clean_tts_text(combined_text)

        # 1. Nếu nguyên câu kép đã có trong cache → trả ngay (< 2ms)
        if clean_combined:
            cached_full = self.get_cached_audio(clean_combined, voice)
            if cached_full:
                return cached_full

        valid_subs = [clean_tts_text(m) for m in sub_messages if clean_tts_text(m)]
        if len(valid_subs) < 2:
            return await self.synthesize(clean_combined, voice, timeout_sec=timeout_sec, fast_mode=True)

        # 2. Nếu tất cả câu con đều đã có trong cache MP3 → ghép thẳng trong < 1ms!
        cached_parts = [self.get_cached_audio(m, voice) for m in valid_subs]
        if all(p and detect_audio_mime(p) == "audio/mpeg" for p in cached_parts):
            stitched = b"".join(strip_mp3_id3v2(p) for p in cached_parts if p)
            if stitched:
                if clean_combined:
                    self.save_to_cache(clean_combined, voice, stitched, persist_disk=False)
                logger.info(
                    "⚡ Compound MP3 stitched from RAM cache (%d parts, %d bytes, 100%% Hoài My)",
                    len(valid_subs),
                    len(stitched),
                )
                return stitched

        # 3. Nếu một số câu con chưa có trong cache → sinh song song các câu con còn thiếu bằng Hoài My!
        if self.primary_engine == "hoaimy":
            async def _fetch_sub(idx: int, msg: str) -> bytes:
                existing = cached_parts[idx]
                if existing and detect_audio_mime(existing) == "audio/mpeg":
                    return existing
                return await self.synthesize(
                    msg, voice, timeout_sec=timeout_sec, fast_mode=True, allow_fallback=False
                )

            resolved = await asyncio.gather(*(_fetch_sub(i, m) for i, m in enumerate(valid_subs)))
            if all(p and detect_audio_mime(p) == "audio/mpeg" for p in resolved):
                stitched = b"".join(strip_mp3_id3v2(p) for p in resolved)
                if stitched:
                    if clean_combined:
                        self.save_to_cache(clean_combined, voice, stitched, persist_disk=False)
                    logger.info(
                        "⚡ Compound MP3 stitched (%d Hoài My parts, %d bytes)",
                        len(valid_subs),
                        len(stitched),
                    )
                    return stitched

        # 4. Fallback an toàn nếu mạng Edge-TTS đang đứt hoàn toàn
        return await self.synthesize(clean_combined, voice, timeout_sec=timeout_sec, fast_mode=True)

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
        Sinh âm thanh tiếng Việt theo kiến trúc ưu tiên đồng nhất giọng:
        - primary_engine='hoaimy' (mặc định - Cách 1):
            1. RAM/Disk/Bundled Cache (< 2ms) — Giọng Hoài My.
            2. Microsoft Edge-TTS Hoài My (1 lần thử gọn) — giữ 100% đồng nhất giọng Hoài My.
            3. Chỉ fallback sang Piper Offline / gTTS khi Edge-TTS timeout hoặc mất mạng (chống lỗi 500).
        - primary_engine='piper': 100% Piper Offline (~120ms).
        - primary_engine='gtts': 100% Google TTS (~300–600ms) + RAM cache.
        """
        voice = voice or self.default_voice
        clean = clean_tts_text(text)
        if not clean:
            return b""

        # Chế độ chuyên biệt: 100% Piper Offline hoặc 100% gTTS
        if self.primary_engine == "piper" and allow_fallback and self.is_piper_ready:
            piper_key = self._get_cache_key(clean, "piper")
            if piper_key in self._memory_cache:
                return self._memory_cache[piper_key]
            piper_bytes = await self.synthesize_piper(clean)
            if piper_bytes:
                self._memory_cache[piper_key] = piper_bytes
                return piper_bytes

        if self.primary_engine == "gtts" and allow_fallback:
            gtts_key = self._get_cache_key(clean, "gtts")
            if gtts_key in self._memory_cache:
                return self._memory_cache[gtts_key]
            gtts_bytes = await self.synthesize_gtts(clean, timeout_sec=3.5)
            if gtts_bytes:
                self._memory_cache[gtts_key] = gtts_bytes
                return gtts_bytes

        # Tầng 1: Kiểm tra cache Hoài My (RAM / Disk / Bundled) → < 2ms
        cached = self.get_cached_audio(clean, voice)
        if cached:
            return cached

        key = self._get_cache_key(clean, voice)
        async with self._get_key_lock(key):
            cached = self.get_cached_audio(clean, voice)
            if cached:
                return cached

            # Tầng 2: Thử Microsoft Edge-TTS Hoài My (1 lần thử gọn để ưu tiên đồng nhất giọng Hoài My)
            edge_attempts = 1 if (fast_mode or allow_fallback) else 2
            edge_timeout = min(timeout_sec, 1.8) if fast_mode else timeout_sec
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

            # Edge-TTS đang lag/đứt mạng → đánh dấu cooldown 60s và dùng fallback để không bao giờ im lặng
            self._edge_cooldown_until = time.monotonic() + 60.0

            # Tầng 3A: Fallback offline Piper TTS (~120ms, không cần internet)
            if self.is_piper_ready:
                piper_bytes = await self.synthesize_piper(clean)
                if piper_bytes:
                    self.save_to_cache(clean, voice, piper_bytes, persist_disk=False)
                    self._schedule_edge_upgrade(clean, voice)
                    return piper_bytes

            # Tầng 3B: Fallback gTTS (~300–600ms, thuần Python)
            gtts_timeout = min(timeout_sec, 2.0) if fast_mode else 3.5
            gtts_bytes = await self.synthesize_gtts(clean, timeout_sec=gtts_timeout)
            if gtts_bytes:
                self.save_to_cache(clean, voice, gtts_bytes, persist_disk=False)
                self._schedule_edge_upgrade(clean, voice)
                return gtts_bytes

        return b""

    def get_fallback_cached_audio(
        self,
        error_code: str | None = None,
        message: str = "",
        voice: str | None = None,
    ) -> bytes | None:
        """Tìm câu âm thanh báo lỗi/trạng thái gần nhất đã có sẵn trong RAM cache.

        Dùng làm lưới an toàn cuối cùng khi câu thoại động bị miss cache và cả
        Edge-TTS + Piper + gTTS đều timeout/không khả dụng → đảm bảo < 2ms,
        100% có giọng phản hồi thay vì im lặng hoặc chờ 7-10 giây.
        """
        voice = voice or self.default_voice
        msg_lower = (message or "").lower()
        code = (error_code or "").upper()

        candidates: list[str] = []
        if code == "INTENT_NOT_FOUND" or "không hiểu" in msg_lower or "chưa hiểu" in msg_lower:
            candidates.extend([
                "Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé.",
            ])
        elif code in ("ACCESS_DENIED", "UNAUTHORIZED") or "không có quyền" in msg_lower:
            candidates.extend([
                "Bạn không có quyền điều khiển thiết bị này.",
                "Bạn chưa đăng nhập hoặc không có quyền điều khiển thiết bị.",
            ])
        elif code == "GATEWAY_DISCONNECTED" or "gateway" in msg_lower:
            candidates.extend([
                "Không thể kết nối đến Gateway thiết bị.",
                "Thiết bị hiện đang ngoại tuyến hoặc chưa kết nối.",
            ])
        elif code in ("DEVICE_OFFLINE", "TIMEOUT") or any(
            k in msg_lower for k in ("ngoại tuyến", "mất kết nối", "không phản hồi", "chưa kết nối")
        ):
            candidates.extend([
                "Thiết bị hiện đang ngoại tuyến hoặc chưa kết nối.",
                "Thiết bị chưa sẵn sàng hoặc đang ngoại tuyến.",
            ])
        elif "không tìm thấy" in msg_lower:
            candidates.extend([
                "Không tìm thấy thiết bị nào phù hợp.",
            ])
        elif code in ("FAILED", "DEVICE_TYPE_UNKNOWN") or "lỗi" in msg_lower:
            candidates.extend([
                "Thiết bị chưa sẵn sàng hoặc đang ngoại tuyến.",
                "Thiết bị hiện đang ngoại tuyến hoặc chưa kết nối.",
            ])

        for phrase in candidates:
            hit = self.get_cached_audio(phrase, voice)
            if hit:
                logger.info("⚡ Semantic RAM audio fallback hit: '%s' (for code=%s)", phrase, code or "NONE")
                return hit

        return None

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
