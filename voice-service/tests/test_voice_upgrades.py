"""
Tests cho các nâng cấp Voice AI:
1. TTS Semantic RAM Audio Fallback (không bao giờ im lặng khi thiết bị offline/lỗi hoặc Edge-TTS timeout).
2. Guardrails chống đoán bừa Intent cho câu vô nghĩa, thiết bị lạ, nhiễu.
3. Điều khiển Kịch bản (Scenes) theo số thứ tự, theo tên, tắt kịch bản, ngữ cảnh 45s.
4. Xử lý xung đột kịch bản: trùng trạng thái, ghi đè kịch bản cũ, xung đột nội bộ, và lệnh kép (Specific Overrides General).
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from unittest.mock import MagicMock, patch

import httpx
import pytest

from app.audio_gate import AudioGate, AudioGateConfig
from app.device_directory import DeviceDirectory, DeviceDirectoryConfig
from app.dialogue_state import DialogueState
from app.dispatcher import DispatchConfig, Dispatcher, SceneDispatchResult
from app.intent import IntentConfig, IntentMapper
from app.pipeline import VoicePipeline
from app.scene_directory import SceneDirectory, SceneDirectoryConfig
from app.stt import SpeechToText
from app.tts_manager import TTSManager


REAL_INTENTS_PATH = os.path.join(os.path.dirname(__file__), "..", "config", "intents_vi.json")


class TestTTSSemanticFallback:
    def test_semantic_ram_fallback_hits_cached_error_audio(self, tmp_path: Path):
        mgr = TTSManager(cache_dir=tmp_path / "cache", bundled_dir=tmp_path / "bundled")
        offline_mp3 = b"\xff\xf3\x64\xc4offline-hoaimy"
        unknown_mp3 = b"\xff\xf3\x64\xc4unknown-hoaimy"
        denied_mp3 = b"\xff\xf3\x64\xc4denied-hoaimy"

        mgr.save_to_cache("Thiết bị hiện đang ngoại tuyến hoặc chưa kết nối.", mgr.default_voice, offline_mp3)
        mgr.save_to_cache("Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé.", mgr.default_voice, unknown_mp3)
        mgr.save_to_cache("Bạn không có quyền điều khiển thiết bị này.", mgr.default_voice, denied_mp3)

        assert mgr.get_fallback_cached_audio(error_code="DEVICE_OFFLINE", message="Đèn chiếu TV hiện đang ngoại tuyến.") == offline_mp3
        assert mgr.get_fallback_cached_audio(error_code="INTENT_NOT_FOUND", message="Không hiểu") == unknown_mp3
        assert mgr.get_fallback_cached_audio(error_code="ACCESS_DENIED", message="Không có quyền") == denied_mp3


class TestStrictIntentGuardrails:
    @pytest.fixture
    def real_mapper(self):
        m = IntentMapper(IntentConfig(min_confidence=68.0))
        m.load_intents(REAL_INTENTS_PATH)
        return m

    @pytest.mark.parametrize("nonsense", [
        "alo alo 1 2 3",
        "hôm nay ăn gì",
        "xin chào bạn khỏe không",
        "trời hôm nay đẹp quá",
        "bật tủ lạnh",
        "tắt máy giặt",
        "mở nhạc lên đi",
        "đóng cuốn sách",
        "bật cái máy bay lên",
        "tắt cái gì đó không biết",
        "cảm ơn nhé",
        "ok được rồi",
        "mấy giờ rồi",
        "bật máy xay",
        "tắt điện thoại đi",
    ])
    def test_nonsense_and_unsupported_commands_return_none(self, real_mapper, nonsense):
        res = real_mapper.map(nonsense)
        assert res is None, f"Should reject '{nonsense}', but mapped to {res}"

    @pytest.mark.parametrize("phrase,expected_action,expected_query", [
        ("bật kịch bản 1", "ACTIVATE_SCENE", "1"),
        ("chạy kịch bản số 2", "ACTIVATE_SCENE", "2"),
        ("bật kịch bản một", "ACTIVATE_SCENE", "1"),
        ("bật kết bạn một", "ACTIVATE_SCENE", "1"),
        ("bật kết bạn chào buổi sáng", "ACTIVATE_SCENE", "chào buổi sáng"),
        ("bật kích bản thứ hai", "ACTIVATE_SCENE", "2"),
        ("bật kịch bảng 1", "ACTIVATE_SCENE", "1"),
        ("bật kịch bản chào buổi sáng", "ACTIVATE_SCENE", "chào buổi sáng"),
        ("kích hoạt kịch bản xem phim", "ACTIVATE_SCENE", "xem phim"),
        ("tắt kịch bản nghỉ trưa", "DEACTIVATE_SCENE", "nghỉ trưa"),
        ("tắt kết bạn nghỉ trưa", "DEACTIVATE_SCENE", "nghỉ trưa"),
        ("dừng kịch bản 1", "DEACTIVATE_SCENE", "1"),
        ("hủy kịch bản đi ngủ", "DEACTIVATE_SCENE", "đi ngủ"),
    ])
    def test_scene_intents_parsed_accurately(self, real_mapper, phrase, expected_action, expected_query):
        res = real_mapper.map(phrase)
        assert res is not None, f"Failed to map scene command: {phrase}"
        assert res.device_type == "scene"
        assert res.action == expected_action
        assert res.scene_query == expected_query


class TestScenePipelineAndConflicts:
    @staticmethod
    def _build_pipeline_with_scenes(scene_exec_override=None):
        devices = [
            {"id": "l-pk-1", "name": "Đèn phòng khách", "deviceType": "LIGHT", "roomName": "Phòng khách", "homeId": "h-1"},
            {"id": "l-ms-1", "name": "Đèn ngủ master", "deviceType": "LIGHT", "roomName": "Phòng ngủ master", "homeId": "h-1"},
        ]
        scenes = [
            {"id": "sc-1", "homeId": "h-1", "name": "Chào buổi sáng", "enabled": True, "index": 1, "actions": []},
            {"id": "sc-2", "homeId": "h-1", "name": "Nghỉ trưa", "enabled": True, "index": 2, "actions": []},
            {"id": "sc-3", "homeId": "h-1", "name": "Đi ngủ", "enabled": True, "index": 3, "actions": []},
        ]
        call_order: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            path = request.url.path
            if path == "/internal/devices" and request.method == "GET":
                return httpx.Response(200, json=devices)
            if path == "/internal/scenes" and request.method == "GET":
                return httpx.Response(200, json=scenes)
            if path.startswith("/internal/scenes/") and request.method == "POST":
                call_order.append(path)
                if scene_exec_override:
                    return scene_exec_override(path)
                sid = path.split("/")[3]
                mode = "DEACTIVATE" if path.endswith("/deactivate") else "ACTIVATE"
                sname = next((s["name"] for s in scenes if s["id"] == sid), "Unknown")
                return httpx.Response(200, json={
                    "code": 1000,
                    "result": {
                        "sceneId": sid,
                        "sceneName": sname,
                        "mode": mode,
                        "status": "SUCCESS",
                        "success": True,
                        "totalDevices": 2,
                        "executedCount": 2,
                        "alreadyInStateCount": 0,
                        "conflictResolvedCount": 0,
                        "overriddenCount": 0,
                        "offlineCount": 0,
                        "failedCount": 0,
                    },
                })
            if path.startswith("/internal/devices/") and path.endswith("/actions") and request.method == "POST":
                call_order.append(path)
                return httpx.Response(200, json={"code": 1000, "result": {"success": True, "status": "SUCCESS"}})
            return httpx.Response(404, json={"message": "Not found"})

        client = httpx.Client(base_url="http://localhost:8080", transport=httpx.MockTransport(handler))
        dd = DeviceDirectory(DeviceDirectoryConfig(base_url="http://localhost:8080"), http_client=client)
        dd.refresh(home_id="h-1")
        sd = SceneDirectory(SceneDirectoryConfig(base_url="http://localhost:8080"), http_client=client)
        sd.refresh(home_id="h-1")

        mapper = IntentMapper(IntentConfig(min_confidence=68.0))
        mapper.load_intents(REAL_INTENTS_PATH)

        pipeline = VoicePipeline(
            audio_gate=AudioGate(AudioGateConfig()),
            stt=MagicMock(spec=SpeechToText),
            device_directory=dd,
            scene_directory=sd,
            intent_mapper=mapper,
            dispatcher=Dispatcher(DispatchConfig(base_url="http://localhost:8080"), http_client=client),
            dialogue_state=DialogueState(ttl_seconds=45),
            home_id="h-1",
        )
        return pipeline, call_order

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_activate_by_index_and_name_and_45s_context_deactivate(self):
        p, calls = self._build_pipeline_with_scenes()

        # 1. Bật theo số thứ tự: kịch bản 1 -> Chào buổi sáng (sc-1)
        r1 = p.process_text("bật kịch bản 1")
        assert r1.success is True
        assert "Chào buổi sáng" in r1.message
        assert calls[-1] == "/internal/scenes/sc-1/execute"

        # 2. Bật theo tên: Nghỉ trưa (sc-2)
        r2 = p.process_text("bật kịch bản nghỉ trưa")
        assert r2.success is True
        assert "Nghỉ trưa" in r2.message
        assert calls[-1] == "/internal/scenes/sc-2/execute"

        # 3. Ngữ cảnh 45s: 'tắt đi' phải tắt kịch bản Nghỉ trưa (sc-2)
        r3 = p.process_text("tắt đi")
        assert r3.success is True
        assert "Nghỉ trưa" in r3.message
        assert calls[-1] == "/internal/scenes/sc-2/deactivate"

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_compound_scene_and_specific_device_runs_scene_first_then_device(self):
        """Lệnh kép 'bật đèn phòng khách và bật kịch bản đi ngủ' phải chạy kịch bản TRƯỚC, thiết bị cụ thể SAU."""
        p, calls = self._build_pipeline_with_scenes()

        res = p.process_text("bật đèn phòng khách và bật kịch bản đi ngủ")
        assert res.success is True
        assert len(res.sub_results) == 2
        # Quy tắc Specific Overrides General: kịch bản Đi ngủ (sc-3) chạy trước, đèn phòng khách (l-pk-1) chạy sau!
        assert calls == [
            "/internal/scenes/sc-3/execute",
            "/internal/devices/l-pk-1/actions",
        ]

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_nonsense_command_returns_polite_cached_message(self):
        p, calls = self._build_pipeline_with_scenes()

        res = p.process_text("hôm nay trời mưa quá ăn gì bây giờ")
        assert res.success is False
        assert res.error_code == "INTENT_NOT_FOUND"
        assert res.message == "Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé."
        assert calls == []

    def test_scene_summary_messages_for_conflict_and_overlap_scenarios(self):
        # 1. Trùng trạng thái 100%
        r_same = SceneDispatchResult(
            scene_id="1", scene_name="Chào buổi sáng", mode="ACTIVATE",
            success=True, status="ALREADY_IN_STATE", total_devices=3, already_in_state_count=3,
        )
        assert "đúng trạng thái" in r_same.summary_message().lower()

        # 2. Ghi đè kịch bản cũ + 1 thiết bị đã đúng trạng thái + 1 xung đột nội bộ
        r_override = SceneDispatchResult(
            scene_id="3", scene_name="Đi ngủ", mode="ACTIVATE",
            success=True, status="SUCCESS", total_devices=3,
            executed_count=2, already_in_state_count=1,
            conflict_resolved_count=1, overridden_count=2, overridden_scene_name="Chào buổi sáng",
        )
        msg = r_override.summary_message()
        assert "Chào buổi sáng" in msg and "Đi ngủ" in msg
        assert "điều chỉnh 2 thiết bị" in msg
        assert "ưu tiên trạng thái sau cùng" in msg

        # 3. Một phần thiết bị ngoại tuyến
        r_partial = SceneDispatchResult(
            scene_id="1", scene_name="Chào buổi sáng", mode="ACTIVATE",
            success=True, status="PARTIAL", total_devices=4,
            executed_count=3, offline_count=1,
        )
        assert "3 thiết bị" in r_partial.summary_message()
        assert "1 thiết bị đang ngoại tuyến" in r_partial.summary_message()


class TestNaturalConversationalAndAntiHallucinationAudit:
    """Bộ kiểm thử toàn diện khóa chặt 7 lỗi ngớ ngẩn đã phát hiện + NLU đời thường + transcript polishing."""

    @pytest.fixture
    def real_mapper(self):
        m = IntentMapper(IntentConfig(min_confidence=68.0))
        m.load_intents(REAL_INTENTS_PATH)
        return m

    def test_polish_transcript_fixes_stt_misrecognitions(self):
        from app.text_normalize import polish_transcript

        assert polish_transcript("bật kết bạn Chào buổi sáng 1") == "bật kịch bản Chào buổi sáng 1"
        assert polish_transcript("tắt kết bạn nghỉ trưa") == "tắt kịch bản nghỉ trưa"
        assert polish_transcript("bật kịch bảng 1") == "bật kịch bản 1"
        assert polish_transcript("bật đèn phòng ngủ mắt tơ") == "bật đèn phòng ngủ master"
        assert polish_transcript("tắc đèn chiếu tivi") == "tắt đèn chiếu tivi"
        assert polish_transcript("mở cửa cuống") == "mở cửa cuốn"
        assert polish_transcript("bật đèn lét phòng khách") == "bật đèn LED phòng khách"
        # Câu xã giao có chữ "kết bạn" KHÔNG đứng sau động từ bật/tắt phải giữ nguyên
        assert polish_transcript("kết bạn với tôi đi") == "kết bạn với tôi đi"

    @pytest.mark.parametrize("phrase,expected_scene", [
        ("tôi muốn đi ngủ", "Đi ngủ"),
        ("chúc ngủ ngon", "Đi ngủ"),
        ("đi ngủ thôi", "Đi ngủ"),
        ("buồn ngủ quá", "Đi ngủ"),
        ("chào buổi sáng", "Chào buổi sáng"),
        ("sáng rồi", "Chào buổi sáng"),
        ("tôi muốn nghỉ trưa", "Nghỉ trưa"),
        ("tôi muốn xem phim", "Xem phim"),
        ("tôi về nhà rồi", "Về nhà"),
        ("tôi đi ra ngoài", "Ra ngoài"),
        ("đi làm đây", "Ra ngoài"),
    ])
    def test_natural_conversational_triggers_map_to_scene_never_to_wrong_device(self, real_mapper, phrase, expected_scene):
        res = real_mapper.map(phrase)
        assert res is not None, f"Expected scene trigger for '{phrase}'"
        assert res.device_type == "scene", f"'{phrase}' wrongly mapped to {res.device_type} ({res.matched_pattern})"
        assert res.action == "ACTIVATE_SCENE"
        assert res.scene_query == expected_scene

    @pytest.mark.parametrize("phrase,expected_action,expected_type", [
        ("lạnh quá", "INCREASE_TEMPERATURE", "air_conditioner"),
        ("phòng ngủ lạnh quá", "INCREASE_TEMPERATURE", "air_conditioner"),
        ("nóng quá", "DECREASE_TEMPERATURE", "air_conditioner"),
        ("phòng khách nóng quá", "DECREASE_TEMPERATURE", "air_conditioner"),
        ("tối quá", "TURN_ON", "light"),
        ("phòng khách tối quá", "TURN_ON", "light"),
        ("đèn sáng quá", "DECREASE_BRIGHTNESS", "light"),
        ("chói quá", "DECREASE_BRIGHTNESS", "light"),
        ("quạt mạnh quá", "TURN_OFF", "fan"),
    ])
    def test_implicit_comfort_intents_never_invert_direction(self, real_mapper, phrase, expected_action, expected_type):
        res = real_mapper.map(phrase)
        assert res is not None, f"Failed comfort intent for '{phrase}'"
        assert res.action == expected_action
        assert res.device_type == expected_type

    @pytest.mark.parametrize("nonsense", [
        "kết bạn với tôi đi",
        "tôi đi ăn cơm",
        "tôi đi chợ",
        "tôi đi học rồi",
        "tôi về quê",
        "hôm nay trời đẹp quá",
        "món này ngon quá",
        "bài này hay quá",
        "xin chào trợ lý",
        "alo có ai ở đó không",
    ])
    def test_pronoun_toi_and_social_phrases_never_hallucinate(self, real_mapper, nonsense):
        res = real_mapper.map(nonsense)
        assert res is None, f"Hallucinated on '{nonsense}' -> {res}"

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_natural_sleep_prioritizes_enabled_scene_and_list_scenes_works(self):
        devices = [
            {"id": "l-1", "name": "Đèn ngủ master", "deviceType": "LIGHT", "roomName": "Phòng ngủ master", "homeId": "h-1"},
            {"id": "l-2", "name": "Đèn ngủ", "deviceType": "LIGHT", "roomName": "Phòng ngủ của con", "homeId": "h-1"},
        ]
        # Mô phỏng đúng DB thực tế: "Chúc ngủ ngon" đang disabled, "Đi ngủ" đang enabled!
        scenes = [
            {"id": "sc-1", "homeId": "h-1", "name": "Chúc ngủ ngon", "enabled": False, "index": 1, "actions": []},
            {"id": "sc-2", "homeId": "h-1", "name": "Đi ngủ", "enabled": True, "index": 2, "actions": []},
            {"id": "sc-3", "homeId": "h-1", "name": "Chào buổi sáng", "enabled": False, "index": 3, "actions": []},
            {"id": "sc-4", "homeId": "h-1", "name": "Chào buổi sáng 1", "enabled": True, "index": 4, "actions": []},
        ]
        called_paths: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            path = request.url.path
            if path == "/internal/devices":
                return httpx.Response(200, json=devices)
            if path == "/internal/scenes":
                return httpx.Response(200, json=scenes)
            if path.startswith("/internal/scenes/"):
                called_paths.append(path)
                sid = path.split("/")[3]
                sname = next(s["name"] for s in scenes if s["id"] == sid)
                return httpx.Response(200, json={
                    "code": 1000,
                    "result": {
                        "sceneId": sid, "sceneName": sname, "mode": "ACTIVATE",
                        "status": "SUCCESS", "success": True, "totalDevices": 2, "executedCount": 2,
                    },
                })
            return httpx.Response(404)

        client = httpx.Client(base_url="http://localhost:8080", transport=httpx.MockTransport(handler))
        dd = DeviceDirectory(DeviceDirectoryConfig(base_url="http://localhost:8080"), http_client=client)
        dd.refresh(home_id="h-1")
        sd = SceneDirectory(SceneDirectoryConfig(base_url="http://localhost:8080"), http_client=client)
        sd.refresh(home_id="h-1")
        mapper = IntentMapper(IntentConfig(min_confidence=68.0))
        mapper.load_intents(REAL_INTENTS_PATH)

        p = VoicePipeline(
            audio_gate=AudioGate(AudioGateConfig()),
            stt=MagicMock(spec=SpeechToText),
            device_directory=dd,
            scene_directory=sd,
            intent_mapper=mapper,
            dispatcher=Dispatcher(DispatchConfig(base_url="http://localhost:8080"), http_client=client),
            dialogue_state=DialogueState(ttl_seconds=45),
            home_id="h-1",
        )

        # 1. "Tôi muốn đi ngủ" -> phải bật kịch bản Đi ngủ (sc-2 đang enabled), KHÔNG được báo "Nhà có 2 đèn ngủ"!
        r1 = p.process_text("Tôi muốn đi ngủ")
        assert r1.success is True
        assert "Đi ngủ" in r1.message
        assert called_paths[-1] == "/internal/scenes/sc-2/execute"

        # 2. "bật kết bạn Chào buổi sáng 1" -> transcript trả về được sửa thành "bật kịch bản Chào buổi sáng 1"!
        r2 = p.process_text("bật kết bạn Chào buổi sáng 1")
        assert r2.success is True
        assert r2.transcript == "bật kịch bản Chào buổi sáng 1"
        assert called_paths[-1] == "/internal/scenes/sc-4/execute"

        # 3. "nhà có những kịch bản nào" -> liệt kê danh sách kịch bản đang bật
        r3 = p.process_text("nhà có những kịch bản nào")
        assert r3.success is True
        assert "Đi ngủ" in r3.message and "Chào buổi sáng 1" in r3.message

        # 4. "tôi về nhà rồi" (nhà chưa có kịch bản Về nhà) -> báo chưa có kịch bản Về nhà, không đoán bừa!
        r4 = p.process_text("tôi về nhà rồi")
        assert r4.success is False
        assert r4.error_code == "SCENE_NOT_FOUND"
        assert "chưa có kịch bản Về nhà" in r4.message

        # 5. "hủy lệnh" -> xóa ngữ cảnh và phản hồi thân thiện
        r5 = p.process_text("hủy lệnh")
        assert r5.success is True
        assert "hủy" in r5.message.lower()

        # 6. Lệnh có từ đệm lịch sự tiếng Việt: "Bật cho tôi cái đi ngủ đi", "bật dùm cái kịch bản đi ngủ"
        r6 = p.process_text("Bật cho tôi cái đi ngủ đi")
        assert r6.success is True
        assert "Đi ngủ" in r6.message
        assert called_paths[-1] == "/internal/scenes/sc-2/execute"

        r7 = p.process_text("bật dùm cái kịch bản đi ngủ")
        assert r7.success is True
        assert "Đi ngủ" in r7.message

        # 7. Idempotency: polish_transcript chạy nhiều lần trên "bật kịch bản chào buổi sáng" không bao giờ thêm chữ n
        from app.text_normalize import polish_transcript
        s = "bật kịch bản chào buổi sáng"
        assert polish_transcript(polish_transcript(s)) == s

    def test_polite_fillers_on_device_commands(self, real_mapper):
        r1 = real_mapper.map("bật dùm tôi cái đèn phòng khách")
        assert r1 is not None and r1.action == "TURN_ON" and r1.device_type == "light" and r1.room == "phòng khách"

        r2 = real_mapper.map("tắt hộ anh cái điều hòa phòng ngủ")
        assert r2 is not None and r2.action == "TURN_OFF" and r2.device_type == "air_conditioner" and r2.room == "phòng ngủ"

        r3 = real_mapper.map("mở giúp mình cái cổng đi")
        assert r3 is not None and r3.action == "OPEN" and r3.device_type == "gate"

    @pytest.mark.parametrize("phrase,expected_action", [
        ("nhà hiện tại có mấy kịch bản", "LIST_SCENES"),
        ("nhà có bao nhiêu kịch bản", "LIST_SCENES"),
        ("kể tên các kịch bản trong nhà", "LIST_SCENES"),
        ("nhà mình hiện có mấy phòng", "LIST_ROOMS"),
        ("nhà có bao nhiêu thiết bị", "LIST_DEVICES"),
        ("phòng khách có mấy thiết bị", "LIST_DEVICES"),
    ])
    def test_flexible_discovery_queries(self, real_mapper, phrase, expected_action):
        res = real_mapper.map(phrase)
        assert res is not None, f"Failed to map discovery query: '{phrase}'"
        assert res.device_type == "system"
        assert res.action == expected_action

    def test_mode_phrase_asr_typo_and_no_device_type_unknown_leak(self, real_mapper):
        # Ảnh 2: Web Speech nghe nhầm "chào" thành "cho" -> "bật chế độ cho buổi sáng"
        custom_scenes = ["Chào buổi sáng", "Chào buổi sáng 1", "Đi ngủ", "Buổi tối xem phim"]
        r1 = real_mapper.map("bật chế độ cho buổi sáng", custom_scenes=custom_scenes)
        assert r1 is not None
        assert r1.device_type == "scene"
        assert r1.action == "ACTIVATE_SCENE"
        assert r1.scene_query == "Chào buổi sáng"

        # Không tồn tại chế độ/kịch bản nào trùng -> phải trả về None (INTENT_NOT_FOUND),
        # tuyệt đối KHÔNG rơi sang device_type=None ("Không xác định được loại thiết bị.")
        r2 = real_mapper.map("bật chế độ linh tinh không tồn tại", custom_scenes=custom_scenes)
        assert r2 is None or r2.device_type is not None

    def test_conversational_lead_in_stripping_and_negation_guard(self, real_mapper):
        custom_scenes = ["Chào buổi sáng", "Đi ngủ"]
        # Ảnh 3: Câu mở đầu "Trời sáng rồi" trước mệnh lệnh kịch bản
        r1 = real_mapper.map("Trời sáng rồi bật cho tôi kịch bản buổi sáng đi", custom_scenes=custom_scenes)
        assert r1 is not None
        assert r1.device_type == "scene"
        assert r1.action == "ACTIVATE_SCENE"
        assert r1.scene_query in ("buổi sáng", "Chào buổi sáng")

        # Câu mở đầu "Hesta ơi", "Tối rồi" trước lệnh thiết bị
        r2 = real_mapper.map("Hesta ơi bật đèn phòng khách đi")
        assert r2 is not None and r2.action == "TURN_ON" and r2.device_type == "light" and r2.room == "phòng khách"

        r3 = real_mapper.map("Tối rồi tắt điều hòa phòng ngủ giúp mình")
        assert r3 is not None and r3.action == "TURN_OFF" and r3.device_type == "air_conditioner" and r3.room == "phòng ngủ"

        # Câu phủ định KHÔNG được cắt bỏ "đừng có" để biến thành lệnh bật!
        r4 = real_mapper.map("đừng có bật đèn")
        assert r4 is None or r4.action != "TURN_ON"

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_three_screenshot_bugs_end_to_end_pipeline(self):
        p, calls = TestScenePipelineAndConflicts._build_pipeline_with_scenes()

        # Screenshot 1: "nhà hiện tại có mấy kịch bản"
        r1 = p.process_text("nhà hiện tại có mấy kịch bản")
        assert r1.success is True
        assert "Chào buổi sáng" in r1.message and "Đi ngủ" in r1.message

        # Screenshot 2: "bật chế độ cho buổi sáng"
        r2 = p.process_text("bật chế độ cho buổi sáng")
        assert r2.success is True
        assert "Chào buổi sáng" in r2.message
        assert calls[-1] == "/internal/scenes/sc-1/execute"

        # Screenshot 3: "Trời sáng rồi bật cho tôi kịch bản buổi sáng đi"
        r3 = p.process_text("Trời sáng rồi bật cho tôi kịch bản buổi sáng đi")
        assert r3.success is True
        assert "Chào buổi sáng" in r3.message
        assert calls[-1] == "/internal/scenes/sc-1/execute"

        # Bonus: "phòng khách có mấy thiết bị"
        r4 = p.process_text("phòng khách có mấy thiết bị")
        assert r4.success is True
        assert "Đèn phòng khách" in r4.message

    def test_wide_audit_bug_regressions(self, real_mapper):
        # Bug 1: "bật đèn led màu đỏ" không được khớp nhầm sang mood "Đọc sách" ("do" in "doc sach")
        dd = DeviceDirectory(DeviceDirectoryConfig())
        mode_red = dd.find_light_mode("đỏ")
        assert mode_red is not None and mode_red["name"] == "đỏ" and mode_red["r"] == 255 and mode_red["g"] == 0

        # Bug 2: "tăng nhiệt độ điều hòa lên 2 độ" phải là INCREASE_TEMPERATURE(amount=2), KHÔNG phải SET_TEMPERATURE(2°C)
        r_ac = real_mapper.map("tăng nhiệt độ điều hòa lên 2 độ")
        assert r_ac is not None
        assert r_ac.action == "INCREASE_TEMPERATURE"
        assert r_ac.parameters.get("amount") == 2
        assert "temperature" not in r_ac.parameters

        # Bug 3: Scene tên ngắn ("bep", "nhà") không được cướp lệnh thiết bị/câu ngoài phạm vi
        short_scenes = ["nhà", "bep", "Chào buổi sáng", "Đi ngủ"]
        r_all = real_mapper.map("bật tất cả thiết bị phòng bếp", custom_rooms=["Phòng bếp"], custom_scenes=short_scenes)
        assert r_all is not None and r_all.device_type == "all" and r_all.room == "Phòng bếp"
        assert real_mapper.map("mở nhạc lên đi", custom_scenes=short_scenes) is None

        # Bug 4: Bilingual room aliases (bếp -> Phòng bếp, sân vườn -> Garden, nhà xe/gara -> Garage)
        r_bep = real_mapper.map("tắt đèn bếp", custom_rooms=["Phòng bếp", "Phòng ngủ master"])
        assert r_bep is not None and r_bep.room == "Phòng bếp"
        r_garden = real_mapper.map("bật bơm nước sân vườn", custom_rooms=["Garden", "Garage"])
        assert r_garden is not None and r_garden.room == "Garden" and r_garden.device_type == "water_pump"
        r_gara = real_mapper.map("bật đèn nhà xe", custom_rooms=["Garden", "Garage"])
        assert r_gara is not None and r_gara.room == "Garage" and r_gara.device_type == "light"

        # Bug 5: 45s follow-up parameter commands (temperature & color)
        r_t1 = real_mapper.map("đặt 24 độ")
        assert r_t1 is not None and r_t1.action == "SET_TEMPERATURE" and r_t1.parameters == {"temperature": 24}
        r_t2 = real_mapper.map("tăng thêm 2 độ")
        assert r_t2 is not None and r_t2.action == "INCREASE_TEMPERATURE" and r_t2.parameters == {"amount": 2}
        r_t3 = real_mapper.map("giảm đi 1 độ")
        assert r_t3 is not None and r_t3.action == "DECREASE_TEMPERATURE" and r_t3.parameters == {"amount": 1}
        r_col = real_mapper.map("đổi sang màu xanh dương")
        assert r_col is not None and r_col.action == "SET_COLOR" and r_col.device_type == "led_rgb" and r_col.parameters["b"] == 255

        # Bug 12: Số ghép tiếng Việt (11-99) không bị cắt mất hàng đơn vị
        r_26 = real_mapper.map("mở điều hòa hai mươi sáu độ")
        assert r_26 is not None and r_26.action == "SET_TEMPERATURE" and r_26.parameters.get("temperature") == 26
        r_25 = real_mapper.map("đặt máy lạnh hai lăm độ")
        assert r_25 is not None and r_25.action == "SET_TEMPERATURE" and r_25.parameters.get("temperature") == 25

    def test_scene_cache_miss_triggers_on_demand_refresh(self):
        """Khi người dùng vừa tạo kịch bản mới trong khi cache vẫn chứa kịch bản cũ, tự động refresh on-demand."""
        from app.scene_directory import SceneDirectory, SceneDirectoryConfig

        call_count = {"get": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            call_count["get"] += 1
            if call_count["get"] == 1:
                return httpx.Response(200, json=[
                    {"id": "old-morning-id", "name": "Chào buổi sáng", "homeId": "h1", "enabled": True, "index": 1}
                ])
            return httpx.Response(200, json=[
                {"id": "new-morning-id", "name": "Chào buổi sáng", "homeId": "h1", "enabled": True, "index": 1},
                {"id": "new-sleep-id", "name": "Chúc ngủ ngon", "homeId": "h1", "enabled": True, "index": 2},
            ])

        client = httpx.Client(transport=httpx.MockTransport(handler), base_url="http://mock")
        sdir = SceneDirectory(SceneDirectoryConfig(base_url="http://mock"), http_client=client)
        assert sdir.refresh(home_id="h1") is True
        # Giả lập đã qua > 2s kể từ lần refresh trước
        sdir._last_refresh_time -= 5.0

        found = sdir.resolve_scene("Chúc ngủ ngon", home_id="h1")
        assert found is not None
        assert found.scene_id == "new-sleep-id"
        assert call_count["get"] == 2

    def test_scene_404_stale_id_auto_refreshes_and_retries(self, real_mapper):
        """Khi kịch bản bị xóa & tạo lại (scene_id cũ trả 404), pipeline tự refresh cache và gọi lại với scene_id mới."""
        from app.audio_gate import AudioGate
        from app.device_directory import DeviceDirectory
        from app.dialogue_state import DialogueState
        from app.dispatcher import DispatchConfig, Dispatcher
        from app.pipeline import VoicePipeline
        from app.scene_directory import SceneDirectory, SceneDirectoryConfig
        from app.stt import SpeechToText

        state = {"scenes_get": 0, "exec_ids": []}

        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == "/internal/scenes" and request.method == "GET":
                state["scenes_get"] += 1
                if state["scenes_get"] == 1:
                    return httpx.Response(200, json=[
                        {"id": "stale-id-1", "name": "Chào buổi sáng", "homeId": "h1", "enabled": True, "index": 1}
                    ])
                return httpx.Response(200, json=[
                    {"id": "fresh-id-2", "name": "Chào buổi sáng", "homeId": "h1", "enabled": True, "index": 1}
                ])
            if request.url.path == "/internal/scenes/stale-id-1/execute":
                state["exec_ids"].append("stale-id-1")
                return httpx.Response(404, json={"message": "Scene not found"})
            if request.url.path == "/internal/scenes/fresh-id-2/execute":
                state["exec_ids"].append("fresh-id-2")
                return httpx.Response(200, json={
                    "code": 1000,
                    "result": {
                        "sceneId": "fresh-id-2",
                        "sceneName": "Chào buổi sáng",
                        "status": "SUCCESS",
                        "totalDevices": 2,
                        "executedCount": 2,
                    },
                })
            return httpx.Response(200, json=[])

        client = httpx.Client(transport=httpx.MockTransport(handler), base_url="http://mock")
        sdir = SceneDirectory(SceneDirectoryConfig(base_url="http://mock"), http_client=client)
        sdir.refresh(home_id="h1")

        pipeline = VoicePipeline(
            audio_gate=AudioGate(),
            stt=SpeechToText(),
            device_directory=DeviceDirectory(http_client=client),
            scene_directory=sdir,
            intent_mapper=real_mapper,
            dispatcher=Dispatcher(DispatchConfig(base_url="http://mock"), http_client=client),
            dialogue_state=DialogueState(),
            home_id="h1",
        )

        res = pipeline.process_text("bật kịch bản chào buổi sáng")
        assert res.success is True
        assert state["exec_ids"] == ["stale-id-1", "fresh-id-2"]
        assert "Chào buổi sáng" in res.message





