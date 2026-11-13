"""
Unit tests cho module matrix_generator và tính năng Dynamic TTS Warmup.
"""

import pytest
from app.matrix_generator import generate_phrases_for_room, generate_matrix_phrases
from app.tts_manager import TTSManager


class TestMatrixGenerator:
    """Kiểm thử sinh ma trận câu phản hồi."""

    def test_generate_phrases_for_room_standard(self):
        phrases = generate_phrases_for_room("phòng ngủ", ["đèn", "quạt"])
        assert "Đã bật đèn phòng ngủ" in phrases
        assert "Đã tắt quạt phòng ngủ" in phrases
        assert "Không thể bật đèn phòng ngủ — tất cả thiết bị đều lỗi" in phrases
        assert "Không tìm thấy thiết bị nào ở phòng ngủ." in phrases

    def test_generate_phrases_for_custom_room(self):
        # Phòng tùy chỉnh do người dùng tự đặt
        phrases = generate_phrases_for_room("phòng của đức")
        assert any("phòng của đức" in p for p in phrases)
        assert "Đã bật đèn phòng của đức" in phrases
        assert "Không thể tắt quạt phòng của đức — tất cả thiết bị đều lỗi" in phrases

    def test_generate_matrix_phrases_includes_compounds(self):
        phrases = generate_matrix_phrases(
            rooms=["phòng khách", "phòng ngủ"],
            include_compounds=True,
        )
        assert len(phrases) > 20
        # Đảm bảo có câu lệnh kép giữa 2 phòng
        assert any("và" in p and "phòng khách" in p and "phòng ngủ" in p for p in phrases)
        # Đảm bảo không có phần tử trùng lặp
        assert len(phrases) == len(set(phrases))


class TestTTSManagerDynamicWarmup:
    """Kiểm thử cơ chế nạp trước động theo phòng."""

    def test_warmup_room_adds_to_pending(self, tmp_path):
        manager = TTSManager(cache_dir=tmp_path, bundled_dir=tmp_path)
        # Ban đầu chưa có câu nào trong pending
        assert not hasattr(manager, "_pending_warmup") or len(manager._pending_warmup) == 0

        # Kích hoạt warmup cho phòng gaming
        manager.warmup_room("phòng gaming", ["đèn", "quạt"])
        assert hasattr(manager, "_pending_warmup")
        assert len(manager._pending_warmup) > 0
        # Các câu phải chứa 'phòng gaming'
        assert any("phòng gaming" in p for p in manager._pending_warmup)
