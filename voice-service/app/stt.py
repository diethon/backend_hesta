"""
HESTA Voice Service — STT Module (Mục 2.1 bước 2)
Wrapper cho faster-Whisper với vad_filter=True, language="vi".

Tích hợp Silero-VAD sẵn trong faster-whisper (qua onnxruntime),
KHÔNG cần cài torch hay model VAD riêng.
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np

if TYPE_CHECKING:
    from faster_whisper import WhisperModel

logger = logging.getLogger(__name__)


@dataclass
class STTConfig:
    """Cấu hình STT, map từ voice_config.yaml mục 'stt'."""
    model_size: str = "base"       # tiny/base/small
    language: str = "vi"           # ép cứng tiếng Việt
    compute_type: str = "int8"     # quantization cho ARM
    vad_filter: bool = True        # dùng Silero-VAD tích hợp
    initial_prompt: str | None = None
    temperature: float | None = None
    condition_on_previous_text: bool | None = None
    repetition_penalty: float | None = None
    no_repeat_ngram_size: int | None = None
    hotwords: str | None = None


@dataclass
class STTResult:
    """Kết quả transcribe."""
    text: str                        # transcript đã nối
    language: str = "vi"
    confidence: float = 0.0          # avg log prob (càng gần 0 càng tốt)
    duration_ms: float = 0.0         # thời gian transcribe
    segments: list[dict] = field(default_factory=list)  # chi tiết từng segment


class SpeechToText:
    """
    Wrapper faster-Whisper: load model 1 lần, transcribe nhiều lần.

    Sử dụng:
        stt = SpeechToText(config)
        stt.load_model()
        result = stt.transcribe(audio_array)
    """

    def __init__(self, config: STTConfig | None = None):
        self.config = config or STTConfig()
        self._model: WhisperModel | None = None

    def load_model(self) -> None:
        """Load model faster-Whisper. Nên gọi 1 lần khi khởi động service.
        Lần đầu sẽ tải model từ Hugging Face Hub, sau đó dùng cache local.
        """
        from faster_whisper import WhisperModel

        logger.info(
            "Loading faster-Whisper model '%s' (compute_type=%s)...",
            self.config.model_size,
            self.config.compute_type,
        )
        t0 = time.perf_counter()
        self._model = WhisperModel(
            self.config.model_size,
            device="cpu",
            compute_type=self.config.compute_type,
        )
        load_ms = (time.perf_counter() - t0) * 1000
        logger.info("Model loaded in %.0fms", load_ms)

    @property
    def is_loaded(self) -> bool:
        return self._model is not None

    def transcribe(self, audio: np.ndarray | str | Path) -> STTResult:
        """Transcribe audio thành text tiếng Việt.

        Args:
            audio: numpy array (float32/int16, 16kHz mono) hoặc path tới file WAV.

        Returns:
            STTResult với text, confidence, duration_ms, segments.

        Raises:
            RuntimeError: nếu model chưa được load.
        """
        if self._model is None:
            raise RuntimeError("Model chưa được load. Gọi load_model() trước.")

        # Chuyển đổi audio nếu cần
        if isinstance(audio, (str, Path)):
            audio_input = str(audio)
        elif isinstance(audio, np.ndarray):
            # faster-whisper cần float32 [-1, 1]
            if audio.dtype == np.int16:
                audio_input = audio.astype(np.float32) / 32768.0
            elif audio.dtype == np.float32:
                audio_input = audio
            else:
                audio_input = audio.astype(np.float32)
        else:
            raise TypeError(f"Loại audio không hỗ trợ: {type(audio)}")

        t0 = time.perf_counter()
        kwargs = {
            "language": self.config.language,
            "vad_filter": self.config.vad_filter,
        }
        if self.config.initial_prompt is not None:
            kwargs["initial_prompt"] = self.config.initial_prompt
        if self.config.temperature is not None:
            kwargs["temperature"] = self.config.temperature
        if self.config.condition_on_previous_text is not None:
            kwargs["condition_on_previous_text"] = self.config.condition_on_previous_text
        if self.config.repetition_penalty is not None:
            kwargs["repetition_penalty"] = self.config.repetition_penalty
        if self.config.no_repeat_ngram_size is not None:
            kwargs["no_repeat_ngram_size"] = self.config.no_repeat_ngram_size
        if self.config.hotwords is not None:
            kwargs["hotwords"] = self.config.hotwords

        segments_iter, info = self._model.transcribe(
            audio_input,
            **kwargs,
        )

        # Collect segments
        segments = []
        texts = []
        total_log_prob = 0.0
        segment_count = 0

        for seg in segments_iter:
            segments.append({
                "start": seg.start,
                "end": seg.end,
                "text": seg.text.strip(),
                "avg_logprob": seg.avg_logprob,
                "no_speech_prob": seg.no_speech_prob,
            })
            if seg.text.strip():
                texts.append(seg.text.strip())
                total_log_prob += seg.avg_logprob
                segment_count += 1

        duration_ms = (time.perf_counter() - t0) * 1000
        avg_confidence = total_log_prob / segment_count if segment_count > 0 else 0.0

        full_text = " ".join(texts).strip()
        # Loại bỏ lặp từ bất thường (VD: '90, 90, 90, 90') do ảo giác decoder
        full_text = re.sub(r'(\b\w+\b)(?:\s*,\s*\1)+', r'\1', full_text)
        full_text = re.sub(r',\s*,+', ',', full_text).strip(' ,')

        result = STTResult(
            text=full_text,
            language=self.config.language,
            confidence=avg_confidence,
            duration_ms=round(duration_ms, 1),
            segments=segments,
        )

        logger.info(
            "Transcribed in %.0fms: '%s' (confidence=%.3f)",
            result.duration_ms,
            result.text[:80],
            result.confidence,
        )

        return result
