"""
HESTA Voice Service — Audio Gate (Mục 2.1 bước 1)
RMS-based: quyết định khi nào bắt đầu/dừng ghi âm.

Thuật toán energy-based đơn giản:
- Liên tục đọc audio từ micro theo chunk
- Khi RMS vượt ngưỡng → bắt đầu ghi
- Khi RMS dưới ngưỡng liên tục > max_silence_ms → dừng ghi
- Trả về audio buffer hoàn chỉnh (numpy array, 16kHz mono)

Lưu ý: Module này KHÔNG dùng model ML, chỉ dựa trên mức năng lượng âm thanh.
Khác với VAD tích hợp trong faster-Whisper (dọn khoảng lặng bên trong đoạn đã ghi).
"""

from dataclasses import dataclass

import numpy as np


@dataclass
class AudioGateConfig:
    """Cấu hình cho audio gate, map từ voice_config.yaml"""
    rms_threshold: float = 500.0     # ngưỡng RMS để kích hoạt ghi âm
    min_speech_ms: int = 250         # tối thiểu bao lâu mới tính là có lời nói
    max_silence_ms: int = 800        # im lặng bao lâu thì coi là kết thúc
    sample_rate: int = 16000         # 16kHz mono (yêu cầu của Whisper)
    chunk_ms: int = 30               # kích thước mỗi chunk đọc từ micro (ms)

    @property
    def chunk_size(self) -> int:
        """Số sample trong 1 chunk."""
        return int(self.sample_rate * self.chunk_ms / 1000)

    @property
    def min_speech_chunks(self) -> int:
        """Số chunk tối thiểu cần có giọng nói để tính là speech."""
        return max(1, self.min_speech_ms // self.chunk_ms)

    @property
    def max_silence_chunks(self) -> int:
        """Số chunk im lặng liên tục để kết thúc ghi."""
        return max(1, self.max_silence_ms // self.chunk_ms)


def compute_rms(audio_chunk: np.ndarray) -> float:
    """Tính RMS (Root Mean Square) energy của 1 chunk audio.

    Args:
        audio_chunk: numpy array, int16 hoặc float32

    Returns:
        Giá trị RMS (float)
    """
    if audio_chunk.size == 0:
        return 0.0
    # Chuyển sang float để tránh overflow khi tính bình phương
    data = audio_chunk.astype(np.float64)
    return float(np.sqrt(np.mean(data ** 2)))


class AudioGate:
    """
    Audio gate dựa trên mức năng lượng (RMS) để phát hiện khi nào
    người dùng bắt đầu và kết thúc nói.

    Sử dụng:
        gate = AudioGate(config)
        # Feed từng chunk audio vào
        gate.feed(chunk)
        if gate.is_speech_complete():
            audio = gate.get_audio()
    """

    def __init__(self, config: AudioGateConfig | None = None):
        self.config = config or AudioGateConfig()
        self._buffer: list[np.ndarray] = []
        self._preroll: list[np.ndarray] = []
        self._state = "waiting"  # "waiting" | "recording" | "complete"
        self._speech_chunk_count = 0
        self._silence_chunk_count = 0

    def reset(self):
        """Reset gate về trạng thái ban đầu, sẵn sàng cho câu nói tiếp theo."""
        self._buffer.clear()
        self._preroll.clear()
        self._state = "waiting"
        self._speech_chunk_count = 0
        self._silence_chunk_count = 0

    def feed(self, chunk: np.ndarray) -> None:
        """Đưa 1 chunk audio vào gate để xử lý.

        Args:
            chunk: numpy array (int16 hoặc float32), mono, 16kHz
        """
        if self._state == "complete":
            return

        rms = compute_rms(chunk)
        is_speech = rms >= self.config.rms_threshold

        if self._state == "waiting":
            # Luôn giữ 400ms gần nhất (13 chunks)
            self._preroll.append(chunk.copy())
            if len(self._preroll) > 13:
                self._preroll.pop(0)

            if is_speech:
                self._speech_chunk_count += 1
                if self._speech_chunk_count >= self.config.min_speech_chunks:
                    self._state = "recording"
                    self._silence_chunk_count = 0
                    # Toàn bộ 400ms quá khứ (bao gồm cả khúc min_speech_chunks vừa rồi) trở thành buffer
                    self._buffer = self._preroll.copy()
                    self._preroll.clear()
            else:
                self._speech_chunk_count = 0

        elif self._state == "recording":
            self._buffer.append(chunk.copy())
            if is_speech:
                self._silence_chunk_count = 0
            else:
                self._silence_chunk_count += 1
                if self._silence_chunk_count >= self.config.max_silence_chunks:
                    self._state = "complete"

    def is_speech_complete(self) -> bool:
        """Kiểm tra đã phát hiện 1 câu nói hoàn chỉnh chưa."""
        return self._state == "complete"

    def is_recording(self) -> bool:
        """Kiểm tra đang trong quá trình ghi âm."""
        return self._state == "recording"

    def get_audio(self) -> np.ndarray | None:
        """Lấy audio buffer đã ghi xong.

        Returns:
            numpy array (int16, mono, 16kHz) hoặc None nếu chưa hoàn thành.
            Phần im lặng cuối (max_silence_chunks) được cắt bớt.
        """
        if self._state != "complete" or not self._buffer:
            return None

        # Cắt bỏ phần im lặng cuối
        trim_count = self.config.max_silence_chunks
        if trim_count < len(self._buffer):
            audio = np.concatenate(self._buffer[:-trim_count])
        else:
            audio = np.concatenate(self._buffer)

        return audio

    @property
    def state(self) -> str:
        """Trạng thái hiện tại: 'waiting', 'recording', hoặc 'complete'."""
        return self._state
