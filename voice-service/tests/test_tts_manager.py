"""
Unit tests cho Hybrid 3-Tier TTS Manager và Backend-driven Matrix Generator.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest

from app.matrix_generator import generate_phrases_for_backend_devices
from app.tts_manager import TTSManager, clean_tts_text, detect_audio_mime


class TestAudioHelpers:
    def test_detect_audio_mime_wav_and_mp3(self):
        assert detect_audio_mime(b"RIFF\x24\x00\x00\x00WAVEfmt ") == "audio/wav"
        assert detect_audio_mime(b"\xff\xfb\x90\x00fake-mp3") == "audio/mpeg"
        assert detect_audio_mime(b"") == "audio/mpeg"

    def test_clean_tts_text_strips_icons_and_markdown(self):
        assert clean_tts_text("✅ **Đã bật** đèn — phòng khách") == "Đã bật đèn, phòng khách"


class TestHybridTTSManager:
    def test_tier1_ram_cache_hit_skips_all_engines(self, tmp_path: Path):
        mgr = TTSManager(cache_dir=tmp_path / "cache", bundled_dir=tmp_path / "bundled")
        mgr.save_to_cache("Đã bật đèn phòng khách", mgr.default_voice, b"\xff\xfbcached-mp3")

        with patch.object(mgr, "_synthesize_edge", new_callable=AsyncMock) as mock_edge, \
             patch.object(mgr, "synthesize_piper", new_callable=AsyncMock) as mock_piper:
            # Kiểm tra khớp không phân biệt chữ hoa/thường tên phòng
            data = asyncio.run(mgr.synthesize("Đã bật đèn Phòng khách", fast_mode=True))
            assert data == b"\xff\xfbcached-mp3"
            mock_edge.assert_not_called()
            mock_piper.assert_not_called()

    def test_tier2_fast_mode_uses_piper_offline_immediately(self, tmp_path: Path):
        mgr = TTSManager(cache_dir=tmp_path / "cache", bundled_dir=tmp_path / "bundled")
        fake_wav = b"RIFF\x24\x00\x00\x00WAVEfmt fake-piper"

        with patch.object(type(mgr), "is_piper_ready", new_callable=lambda: property(lambda self: True)), \
             patch.object(mgr, "synthesize_piper", new_callable=AsyncMock, return_value=fake_wav) as mock_piper, \
             patch.object(mgr, "_synthesize_edge", new_callable=AsyncMock) as mock_edge, \
             patch.object(mgr, "_schedule_edge_upgrade") as mock_upgrade:
            data = asyncio.run(mgr.synthesize("Đèn Phòng khách hiện đang ngoại tuyến.", fast_mode=True))
            assert data == fake_wav
            assert detect_audio_mime(data) == "audio/wav"
            mock_piper.assert_awaited_once()
            mock_edge.assert_not_called()
            mock_upgrade.assert_called_once()
            # Lần 2 trúng ngay RAM cache (0ms), không gọi lại Piper
            data2 = asyncio.run(mgr.synthesize("Đèn Phòng khách hiện đang ngoại tuyến.", fast_mode=True))
            assert data2 == fake_wav
            assert mock_piper.await_count == 1

    def test_tier2_falls_back_to_gtts_when_edge_fails_and_no_piper(self, tmp_path: Path):
        mgr = TTSManager(cache_dir=tmp_path / "cache", bundled_dir=tmp_path / "bundled")
        fake_gtts_mp3 = b"\xff\xfbfake-gtts-mp3"

        with patch.object(type(mgr), "is_piper_ready", new_callable=lambda: property(lambda self: False)), \
             patch.object(mgr, "_synthesize_edge", new_callable=AsyncMock, return_value=b"") as mock_edge, \
             patch.object(mgr, "synthesize_gtts", new_callable=AsyncMock, return_value=fake_gtts_mp3) as mock_gtts, \
             patch.object(mgr, "_schedule_edge_upgrade") as mock_upgrade:
            data = asyncio.run(mgr.synthesize("Câu chưa có trong cache", fast_mode=True))
            assert data == fake_gtts_mp3
            mock_edge.assert_awaited_once()
            mock_gtts.assert_awaited_once()
            mock_upgrade.assert_called_once()

    def test_allow_fallback_false_only_uses_edge(self, tmp_path: Path):
        mgr = TTSManager(cache_dir=tmp_path / "cache", bundled_dir=tmp_path / "bundled")

        with patch.object(mgr, "_synthesize_edge", new_callable=AsyncMock, return_value=b"") as mock_edge, \
             patch.object(mgr, "synthesize_gtts", new_callable=AsyncMock) as mock_gtts:
            data = asyncio.run(mgr.synthesize("Test matrix", allow_fallback=False))
            assert data == b""
            mock_edge.assert_awaited_once()
            mock_gtts.assert_not_called()


class TestBackendDeviceMatrixGenerator:
    def test_generates_exact_phrases_for_real_home_devices(self):
        devices = [
            {"name": "Đèn phòng khách", "deviceType": "LIGHT", "roomName": "Phòng khách"},
            {"name": "Đèn chiếu TV", "deviceType": "LIGHT", "roomName": "Phòng khách"},
            {"name": "Rèm phòng ngủ", "deviceType": "CURTAIN", "roomName": "Phòng ngủ master"},
            {"name": "Điều hòa DAIKIN", "deviceType": "AIR_CONDITIONER", "roomName": "Phòng ngủ 2 giường"},
            {"name": "Cảm biến nhiệt ẩm", "deviceType": "TEMP_HUMID_SENSOR", "roomName": "Phòng khách"},
        ]

        phrases = generate_phrases_for_backend_devices(devices)
        lowered = {p.lower() for p in phrases}

        # Phải có câu cho 2 đèn Phòng khách và báo lỗi offline
        assert "đã bật 2 đèn phòng khách" in lowered
        assert "đã tắt 2 đèn phòng khách" in lowered
        assert "đèn phòng khách hiện đang ngoại tuyến hoặc chưa kết nối." in lowered

        # Phải có câu mở/đóng/bật/tắt/dừng cho rèm Phòng ngủ master
        assert "đã mở rèm phòng ngủ master" in lowered
        assert "đã đóng rèm cửa phòng ngủ master" in lowered
        assert "đã bật rèm phòng ngủ master" in lowered
        assert "đã dừng rèm cửa phòng ngủ master" in lowered

        # Phải có câu nhiệt độ điều hòa Phòng ngủ 2 giường
        assert "đã đặt nhiệt độ điều hòa phòng ngủ 2 giường về 24 độ" in lowered

        # Không sinh câu cho cảm biến
        assert not any("cảm biến" in p for p in lowered)
