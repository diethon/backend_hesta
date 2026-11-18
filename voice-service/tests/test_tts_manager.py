"""
Unit tests cho Hybrid 3-Tier TTS Manager, Ghép MP3 Lệnh Kép và Backend-driven Matrix Generator.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.matrix_generator import generate_phrases_for_backend_devices
from app.tts_manager import TTSManager, clean_tts_text, detect_audio_mime, strip_mp3_id3v2


class TestAudioHelpers:
    def test_detect_audio_mime_wav_and_mp3(self):
        assert detect_audio_mime(b"RIFF\x24\x00\x00\x00WAVEfmt ") == "audio/wav"
        assert detect_audio_mime(b"\xff\xf3\x64\xc4fake-mp3") == "audio/mpeg"
        assert detect_audio_mime(b"") == "audio/mpeg"

    def test_clean_tts_text_strips_icons_and_markdown(self):
        assert clean_tts_text("✅ **Đã bật** đèn — phòng khách") == "Đã bật đèn, phòng khách"

    def test_strip_mp3_id3v2(self):
        raw_frames = b"\xff\xf3\x64\xc4frame-data"
        # Fake 10-byte ID3v2 header with 4-byte payload (synchsafe size = 4)
        with_id3 = b"ID3\x03\x00\x00\x00\x00\x00\x04ABCD" + raw_frames
        assert strip_mp3_id3v2(with_id3) == raw_frames
        assert strip_mp3_id3v2(raw_frames) == raw_frames


class TestHybridTTSManager:
    def test_tier1_ram_cache_hit_case_and_period_insensitive(self, tmp_path: Path):
        mgr = TTSManager(cache_dir=tmp_path / "cache", bundled_dir=tmp_path / "bundled")
        mgr.save_to_cache("Đã bật đèn phòng khách", mgr.default_voice, b"\xff\xf3cached-mp3")

        with patch.object(mgr, "_synthesize_edge", new_callable=AsyncMock) as mock_edge, \
             patch.object(mgr, "synthesize_piper", new_callable=AsyncMock) as mock_piper:
            # Khớp cả chữ hoa và dấu chấm cuối câu
            data1 = asyncio.run(mgr.synthesize("Đã bật đèn Phòng khách", fast_mode=True))
            data2 = asyncio.run(mgr.synthesize("Đã bật đèn Phòng khách.", fast_mode=True))
            assert data1 == b"\xff\xf3cached-mp3"
            assert data2 == b"\xff\xf3cached-mp3"
            mock_edge.assert_not_called()
            mock_piper.assert_not_called()

    def test_compound_stitches_cached_hoaimy_mp3_in_ram_with_zero_network(self, tmp_path: Path):
        mgr = TTSManager(cache_dir=tmp_path / "cache", bundled_dir=tmp_path / "bundled")
        part1 = b"\xff\xf3\x64\xc4part1-hoaimy"
        part2 = b"\xff\xf3\x64\xc4part2-hoaimy"
        mgr.save_to_cache("Đèn phòng khách hiện đang ngoại tuyến hoặc chưa kết nối.", mgr.default_voice, part1)
        mgr.save_to_cache("Không thể đóng cổng — thiết bị không phản hồi.", mgr.default_voice, part2)

        combined = "Đèn Phòng khách hiện đang ngoại tuyến hoặc chưa kết nối và không thể đóng cổng — thiết bị không phản hồi."
        with patch.object(mgr, "_synthesize_edge", new_callable=AsyncMock) as mock_edge, \
             patch.object(mgr, "synthesize_gtts", new_callable=AsyncMock) as mock_gtts:
            stitched = asyncio.run(
                mgr.synthesize_compound(
                    [
                        "Đèn Phòng khách hiện đang ngoại tuyến hoặc chưa kết nối.",
                        "Không thể đóng cổng — thiết bị không phản hồi.",
                    ],
                    combined,
                )
            )
            assert stitched == part1 + part2
            assert detect_audio_mime(stitched) == "audio/mpeg"
            mock_edge.assert_not_called()
            mock_gtts.assert_not_called()
            # Câu ghép đã được lưu vào RAM cache cho lần phát lại (Replay)
            assert mgr.get_cached_audio(combined) == part1 + part2

    def test_hoaimy_mode_prefers_edge_before_fallback(self, tmp_path: Path):
        mgr = TTSManager(
            cache_dir=tmp_path / "cache",
            bundled_dir=tmp_path / "bundled",
            primary_engine="hoaimy",
        )
        fake_edge_mp3 = b"\xff\xf3\x64\xc4hoaimy-edge"

        with patch.object(mgr, "_synthesize_edge", new_callable=AsyncMock, return_value=fake_edge_mp3) as mock_edge, \
             patch.object(mgr, "synthesize_gtts", new_callable=AsyncMock) as mock_gtts:
            data = asyncio.run(mgr.synthesize("Đã mở rèm phòng ngủ master", fast_mode=True))
            assert data == fake_edge_mp3
            mock_edge.assert_awaited_once()
            mock_gtts.assert_not_called()

    def test_piper_primary_engine_runs_100_percent_offline(self, tmp_path: Path):
        mgr = TTSManager(
            cache_dir=tmp_path / "cache",
            bundled_dir=tmp_path / "bundled",
            primary_engine="piper",
        )
        fake_wav = b"RIFF\x24\x00\x00\x00WAVEfmt fake-piper"

        with patch.object(type(mgr), "is_piper_ready", new_callable=lambda: property(lambda self: True)), \
             patch.object(mgr, "synthesize_piper", new_callable=AsyncMock, return_value=fake_wav) as mock_piper, \
             patch.object(mgr, "_synthesize_edge", new_callable=AsyncMock) as mock_edge:
            data = asyncio.run(mgr.synthesize("Đèn Phòng khách hiện đang ngoại tuyến.", fast_mode=True))
            assert data == fake_wav
            assert detect_audio_mime(data) == "audio/wav"
            mock_piper.assert_awaited_once()
            mock_edge.assert_not_called()

    def test_falls_back_to_gtts_only_when_edge_fails_and_no_piper(self, tmp_path: Path):
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

        assert "đã bật 2 đèn phòng khách" in lowered
        assert "đã tắt 2 đèn phòng khách" in lowered
        assert "đèn phòng khách hiện đang ngoại tuyến hoặc chưa kết nối." in lowered
        assert "đã mở rèm phòng ngủ master" in lowered
        assert "đã đóng rèm cửa phòng ngủ master" in lowered
        assert "đã bật rèm phòng ngủ master" in lowered
        assert "đã dừng rèm cửa phòng ngủ master" in lowered
        assert "đã đặt nhiệt độ điều hòa phòng ngủ 2 giường về 24 độ" in lowered
        assert not any("cảm biến" in p for p in lowered)
