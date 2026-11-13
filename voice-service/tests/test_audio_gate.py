"""
HESTA Voice Service — Test Audio Gate
Unit test cho audio_gate.py (Mục 11)
"""

import numpy as np
import pytest

from app.audio_gate import AudioGate, AudioGateConfig, compute_rms


# --- Test compute_rms ---

class TestComputeRms:
    def test_silence(self):
        """RMS của mảng toàn 0 phải bằng 0."""
        chunk = np.zeros(480, dtype=np.int16)
        assert compute_rms(chunk) == 0.0

    def test_empty_array(self):
        """RMS của mảng rỗng phải bằng 0."""
        chunk = np.array([], dtype=np.int16)
        assert compute_rms(chunk) == 0.0

    def test_constant_value(self):
        """RMS của mảng giá trị đồng nhất phải bằng giá trị tuyệt đối đó."""
        chunk = np.full(480, 1000, dtype=np.int16)
        rms = compute_rms(chunk)
        assert abs(rms - 1000.0) < 1.0

    def test_speech_like_signal(self):
        """Tín hiệu sine wave mô phỏng giọng nói phải có RMS > 0."""
        t = np.linspace(0, 0.03, 480, dtype=np.float32)
        chunk = (np.sin(2 * np.pi * 300 * t) * 5000).astype(np.int16)
        rms = compute_rms(chunk)
        assert rms > 1000  # Sine wave amplitude 5000 → RMS ~ 3535

    def test_float32_input(self):
        """Phải hoạt động với cả float32 input."""
        chunk = np.full(480, 0.5, dtype=np.float32)
        rms = compute_rms(chunk)
        assert abs(rms - 0.5) < 0.01


# --- Test AudioGateConfig ---

class TestAudioGateConfig:
    def test_default_values(self):
        config = AudioGateConfig()
        assert config.rms_threshold == 500.0
        assert config.min_speech_ms == 250
        assert config.max_silence_ms == 800
        assert config.sample_rate == 16000
        assert config.chunk_ms == 30

    def test_chunk_size(self):
        config = AudioGateConfig(sample_rate=16000, chunk_ms=30)
        assert config.chunk_size == 480  # 16000 * 30 / 1000

    def test_min_speech_chunks(self):
        config = AudioGateConfig(min_speech_ms=250, chunk_ms=30)
        assert config.min_speech_chunks == 8  # 250 // 30 = 8

    def test_max_silence_chunks(self):
        config = AudioGateConfig(max_silence_ms=800, chunk_ms=30)
        assert config.max_silence_chunks == 26  # 800 // 30 = 26


# --- Test AudioGate ---

def make_speech_chunk(chunk_size: int = 480, amplitude: int = 5000) -> np.ndarray:
    """Tạo 1 chunk mô phỏng giọng nói (sine wave, RMS cao)."""
    t = np.linspace(0, 0.03, chunk_size, dtype=np.float32)
    return (np.sin(2 * np.pi * 300 * t) * amplitude).astype(np.int16)


def make_silence_chunk(chunk_size: int = 480) -> np.ndarray:
    """Tạo 1 chunk im lặng (toàn 0)."""
    return np.zeros(chunk_size, dtype=np.int16)


class TestAudioGate:
    @pytest.fixture
    def gate(self):
        """Gate với config đơn giản để test nhanh."""
        config = AudioGateConfig(
            rms_threshold=500,
            min_speech_ms=60,    # 2 chunks cần
            max_silence_ms=90,   # 3 chunks im lặng
            chunk_ms=30,
            sample_rate=16000,
        )
        return AudioGate(config)

    def test_initial_state(self, gate):
        assert gate.state == "waiting"
        assert not gate.is_recording()
        assert not gate.is_speech_complete()
        assert gate.get_audio() is None

    def test_silence_stays_waiting(self, gate):
        """Chỉ im lặng → vẫn ở trạng thái waiting."""
        for _ in range(10):
            gate.feed(make_silence_chunk())
        assert gate.state == "waiting"

    def test_speech_triggers_recording(self, gate):
        """Đủ chunk speech liên tục → chuyển sang recording."""
        # min_speech_chunks = 60 // 30 = 2
        gate.feed(make_speech_chunk())
        gate.feed(make_speech_chunk())
        assert gate.is_recording()

    def test_speech_then_silence_completes(self, gate):
        """Speech → silence kéo dài → complete."""
        # Bắt đầu nói
        for _ in range(3):
            gate.feed(make_speech_chunk())
        assert gate.is_recording()

        # Im lặng kéo dài
        for _ in range(3):
            gate.feed(make_silence_chunk())
        assert gate.is_speech_complete()

    def test_get_audio_returns_data(self, gate):
        """Khi complete, get_audio() trả về numpy array."""
        for _ in range(3):
            gate.feed(make_speech_chunk())
        for _ in range(3):
            gate.feed(make_silence_chunk())

        audio = gate.get_audio()
        assert audio is not None
        assert isinstance(audio, np.ndarray)
        assert len(audio) > 0

    def test_get_audio_trims_silence(self, gate):
        """Audio trả về phải cắt bỏ phần im lặng cuối."""
        speech_chunks = 5
        silence_chunks = 3  # = max_silence_chunks

        for _ in range(speech_chunks):
            gate.feed(make_speech_chunk())
        for _ in range(silence_chunks):
            gate.feed(make_silence_chunk())

        audio = gate.get_audio()
        # Phải cắt 3 chunk silence cuối
        expected_chunks = speech_chunks  # 5 chunks speech (đã trừ 3 silence)
        expected_samples = expected_chunks * gate.config.chunk_size
        assert len(audio) == expected_samples

    def test_reset(self, gate):
        """Reset phải đưa gate về trạng thái ban đầu."""
        for _ in range(3):
            gate.feed(make_speech_chunk())
        for _ in range(3):
            gate.feed(make_silence_chunk())
        assert gate.is_speech_complete()

        gate.reset()
        assert gate.state == "waiting"
        assert gate.get_audio() is None

    def test_intermittent_speech(self, gate):
        """Giọng nói xen kẽ im lặng ngắn (< max_silence) → vẫn recording."""
        # Bắt đầu nói
        for _ in range(3):
            gate.feed(make_speech_chunk())
        assert gate.is_recording()

        # Im lặng ngắn (1 chunk < max_silence_chunks=3)
        gate.feed(make_silence_chunk())
        assert gate.is_recording()  # Vẫn recording

        # Nói tiếp
        gate.feed(make_speech_chunk())
        assert gate.is_recording()

    def test_no_feed_after_complete(self, gate):
        """Sau khi complete, feed thêm không thay đổi gì."""
        for _ in range(3):
            gate.feed(make_speech_chunk())
        for _ in range(3):
            gate.feed(make_silence_chunk())
        assert gate.is_speech_complete()

        audio_before = gate.get_audio()
        gate.feed(make_speech_chunk())  # Thêm nhưng bị bỏ qua
        audio_after = gate.get_audio()
        assert len(audio_before) == len(audio_after)

    def test_short_noise_ignored(self, gate):
        """1 chunk speech đơn lẻ (< min_speech_chunks) rồi im → vẫn waiting."""
        gate.feed(make_speech_chunk())  # Chỉ 1 chunk, cần 2
        gate.feed(make_silence_chunk())
        assert gate.state == "waiting"
