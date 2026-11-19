"""
HESTA Voice Service — Test STT (WER)
Unit test cho stt.py (Mục 11)

Lưu ý: Test WER thật cần file audio + ground truth thật.
Ở đây test các chức năng cơ bản với mock.
File test_stt_wer.py chạy WER thật khi có dữ liệu audio mẫu.
"""

from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from app.stt import SpeechToText, STTConfig, STTResult


class TestSTTConfig:
    def test_defaults(self):
        config = STTConfig()
        assert config.model_size == "base"
        assert config.language == "vi"
        assert config.compute_type == "int8"
        assert config.vad_filter is True

    def test_custom(self):
        config = STTConfig(model_size="tiny", compute_type="float32")
        assert config.model_size == "tiny"
        assert config.compute_type == "float32"


class TestSTTResult:
    def test_basic(self):
        result = STTResult(text="bật đèn phòng khách", confidence=-0.3, duration_ms=500.0)
        assert result.text == "bật đèn phòng khách"
        assert result.language == "vi"
        assert len(result.segments) == 0


class TestSpeechToText:
    def test_transcribe_without_load_raises(self):
        """Gọi transcribe trước khi load_model phải raise RuntimeError."""
        stt = SpeechToText()
        with pytest.raises(RuntimeError, match="Model chưa được load"):
            stt.transcribe(np.zeros(16000, dtype=np.float32))

    def test_is_loaded(self):
        stt = SpeechToText()
        assert not stt.is_loaded

    def test_invalid_audio_type(self):
        """Audio type không hỗ trợ phải raise TypeError."""
        stt = SpeechToText()
        stt._model = MagicMock()  # Fake model to bypass load check
        with pytest.raises(TypeError):
            stt.transcribe(12345)  # type: ignore

    @patch("faster_whisper.WhisperModel", autospec=True)
    def test_transcribe_with_mock(self, mock_whisper_cls):
        """Test transcribe với mock model, không cần model thật."""
        # Setup mock
        mock_model = MagicMock()
        mock_whisper_cls.return_value = mock_model

        mock_segment = MagicMock()
        mock_segment.start = 0.0
        mock_segment.end = 1.5
        mock_segment.text = " bật đèn phòng khách "
        mock_segment.avg_logprob = -0.25
        mock_segment.no_speech_prob = 0.05

        mock_info = MagicMock()
        mock_info.language = "vi"
        mock_info.language_probability = 0.95

        mock_model.transcribe.return_value = (iter([mock_segment]), mock_info)

        # Test
        stt = SpeechToText(STTConfig(model_size="tiny"))
        stt.load_model()
        assert stt.is_loaded

        audio = np.zeros(16000, dtype=np.float32)  # 1 giây audio giả
        result = stt.transcribe(audio)

        assert isinstance(result, STTResult)
        assert result.text == "bật đèn phòng khách"
        assert result.language == "vi"
        assert result.duration_ms >= 0
        assert len(result.segments) == 1

        # Verify transcribe được gọi với đúng params
        mock_model.transcribe.assert_called_with(
            audio,
            language="vi",
            vad_filter=True,
        )

    @patch("faster_whisper.WhisperModel", autospec=True)
    def test_transcribe_int16_conversion(self, mock_whisper_cls):
        """Audio int16 phải được chuyển sang float32."""
        mock_model = MagicMock()
        mock_whisper_cls.return_value = mock_model
        mock_model.transcribe.return_value = (iter([]), MagicMock())

        stt = SpeechToText()
        stt.load_model()

        audio_int16 = np.array([0, 16384, -16384, 32767], dtype=np.int16)
        result = stt.transcribe(audio_int16)

        # Kiểm tra audio đã được chuyển đổi
        call_args = mock_model.transcribe.call_args
        actual_audio = call_args[0][0]
        assert actual_audio.dtype == np.float32
        # 16384 / 32768 = 0.5
        np.testing.assert_allclose(actual_audio[1], 0.5, atol=0.001)

    @patch("faster_whisper.WhisperModel", autospec=True)
    def test_transcribe_file_path(self, mock_whisper_cls):
        """Phải chấp nhận file path string."""
        mock_model = MagicMock()
        mock_whisper_cls.return_value = mock_model
        mock_model.transcribe.return_value = (iter([]), MagicMock())

        stt = SpeechToText()
        stt.load_model()
        stt.transcribe("/tmp/test.wav")

        call_args = mock_model.transcribe.call_args
        assert call_args[0][0] == "/tmp/test.wav"

    @patch("faster_whisper.WhisperModel", autospec=True)
    def test_empty_transcription(self, mock_whisper_cls):
        """Không có segment nào → text rỗng, confidence 0."""
        mock_model = MagicMock()
        mock_whisper_cls.return_value = mock_model
        mock_model.transcribe.return_value = (iter([]), MagicMock())

        stt = SpeechToText()
        stt.load_model()
        result = stt.transcribe(np.zeros(16000, dtype=np.float32))

        assert result.text == ""
        assert result.confidence == 0.0
