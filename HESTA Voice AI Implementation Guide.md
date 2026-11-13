# HESTA — Voice AI Implementation Guide (v2)

### (Local Vietnamese Voice Control — FE-02 / UC: Voice Device Control & Multi-turn Voice Interaction)

**Mục đích tài liệu:** Nguồn sự thật duy nhất (single source of truth) cho module `voice-service`, độc lập với phần Java/Spring Boot. Bản v2 này chốt dứt khoát mọi quyết định kiến trúc còn bỏ ngỏ ở bản v1 (tích hợp backend, VAD, device resolution, auth nội bộ) — không còn phương án A/B để lựa chọn, chỉ còn 1 đường đi duy nhất. Ở cuối tài liệu có **AI Agent Prompt** để dán vào Claude Code/Cursor.

**Những gì đã thay đổi so với v1:** bỏ Silero-VAD/torch như 1 module riêng (dùng VAD tích hợp sẵn trong faster-Whisper), bỏ hẳn MQTT khỏi phạm vi `voice-service` (chốt tích hợp qua REST nội bộ), thêm cơ chế device resolution (trước đây bị thiếu), thêm cơ chế xác thực cho endpoint nội bộ, thêm ngân sách latency theo từng bước, nhúng thẳng schema config mẫu vào tài liệu.

---

## 1. Tổng quan phạm vi

|  |  |
| --- | --- |
| Tính năng gốc | FE-02: Local Vietnamese AI Voice Control (Report 1) |
| Use case liên quan | Voice Device Control (đơn lệnh), Multi-turn Voice Interaction (đa lượt) |
| Package trong kiến trúc tổng | `com.hesta.voice` (voice-service) gọi vào `com.hesta.device` qua REST nội bộ |
| Nguyên tắc bắt buộc | Xử lý **hoàn toàn cục bộ** trên Raspberry Pi — không gửi audio/transcript lên cloud khi vận hành |
| Ngôn ngữ code | Python (tách biệt khỏi Java/Spring Boot) |

**Thứ tự ưu tiên khi quyết định kỹ thuật:** (1) đạt ngưỡng SRS (latency ≤1.5s, WER ≤15%), (2) chạy được trên phần cứng thật (Raspberry Pi), (3) đơn giản/ít dependency hơn là "đầy đủ tính năng AI".

---

## 2. Kiến trúc & luồng hoạt động

### 2.1 Pipeline xử lý 1 lệnh đơn (single-turn) — đã chốt

```
Người dùng nói
      │
      ▼
[1] Audio gate (năng lượng âm thanh, không cần model)
      ── phát hiện có người bắt đầu nói để kích hoạt ghi âm, dừng ghi khi im lặng kéo dài
      │
      ▼
[2] STT (faster-Whisper, vad_filter=True, language="vi")
      ── Silero-VAD đã được tích hợp sẵn bên trong faster-Whisper để dọn khoảng lặng trong
         đoạn ghi, không cần cài model VAD riêng; ép cứng language="vi" để bỏ qua bước
         auto-detect ngôn ngữ (tiết kiệm latency)
      │
      ▼
[3] Device resolution ── map (room, device_type) nói trong câu sang device_id thật, dùng
      cache cục bộ (xem Mục 2.4)
      │
      ▼
[4] Intent mapping ── ánh xạ phần còn lại của câu (action, params) + device_id đã resolve
      │
      ▼
[5] Dispatch (REST nội bộ) ── gọi API Spring Boot để thực thi, Spring Boot lo phần MQTT
      xuống ESP32 (voice-service không đụng tới MQTT)
```

**Quyết định đã chốt — vì sao bỏ Silero-VAD như 1 module riêng:** `faster-whisper` từ bản 0.4.0 trở đi đã tích hợp sẵn Silero-VAD (chạy qua `onnxruntime`) thông qua tham số `vad_filter=True` khi gọi `model.transcribe(...)`. Dùng thẳng tham số này loại bỏ hoàn toàn nhu cầu cài đặt `torch` (rất nặng, dễ gặp vấn đề tương thích ARM64) và code `vad.py` riêng — giảm số dependency, giảm RAM sử dụng, và tránh được đúng mâu thuẫn "torch vs onnxruntime" của bản v1. Bước `[1] Audio gate` vẫn cần giữ lại nhưng chỉ là 1 thuật toán đơn giản dựa trên mức năng lượng âm thanh (RMS), không cần model — chức năng của nó khác với VAD: đây là bước quyết định **khi nào bắt đầu/kết thúc ghi âm** (vì hệ thống lắng nghe liên tục), còn VAD tích hợp của Whisper chỉ dọn khoảng lặng **bên trong** đoạn đã ghi.

### 2.2 Luồng đa lượt (multi-turn)

Không đổi so với v1: dialogue state tạm thời (`last_device_id`, `last_room`, `expires_at`, TTL mặc định 45 giây), lưu trong bộ nhớ (dict Python), tham chiếu ở bước \[4\] Intent mapping khi câu nói thiếu chủ ngữ.

### 2.3 Tích hợp với hệ thống Java — đã chốt Phương án duy nhất

`voice-service` gọi REST API nội bộ vào Spring Boot để thực thi hành động, **không** publish MQTT trực tiếp. Lý do chốt dứt khoát phương án này (không còn để ngỏ phương án B): tận dụng toàn bộ logic kiểm tra quyền/ghi log/validate đã có ở `device`/`automation`, giữ 1 điểm kiểm soát duy nhất cho audit trail — ưu tiên đúng thứ tự (2) "đơn giản/dễ bảo trì" đã đặt ra ở Mục 1. Nếu benchmark thực tế sau này cho thấy latency vượt ngưỡng vì thêm 1 hop HTTP, đây là quyết định duy nhất trong tài liệu được phép xem xét lại — mọi quyết định khác coi là cố định.

**Endpoint:** `POST /internal/devices/{deviceId}/actions`

**Cơ chế xác thực (mới, bổ sung — v1 bỏ sót điểm này):** `voice-service` không phải là 1 user session bình thường (không có JWT của ai), nên dùng cơ chế riêng cho giao tiếp service-to-service, áp dụng cả 2 lớp:

1. **Shared secret API key** — header `X-Internal-Api-Key`, giá trị lấy từ biến môi trường `INTERNAL_API_KEY`, giống nhau ở cả `voice-service` và cấu hình Spring Boot (`application.yml`). Spring Boot có 1 filter riêng (không qua `AuthenticationManager` thông thường) chỉ check đúng giá trị key này cho prefix `/internal/**`.
2. **Giới hạn mạng** (defense-in-depth) — endpoint `/internal/**` chỉ bind/cho phép truy cập từ `localhost`/mạng LAN nội bộ (không expose ra Internet qua reverse proxy công khai), vì `voice-service` và Spring Boot chạy trên cùng Raspberry Pi hoặc cùng mạng LAN.

Lý do chọn API key đơn giản thay vì mTLS/OAuth2 client credentials: ở quy mô đồ án, 2 service chạy cùng 1 thiết bị vật lý, mTLS là over-engineering không cần thiết — ưu tiên (3) "đơn giản" theo đúng nguyên tắc Mục 1.

### 2.4 Device resolution (mới — bù đắp thiếu sót của bản v1)

Bước bắt buộc phải có giữa STT và Intent mapping: câu nói chỉ chứa **tên phòng + loại thiết bị** ("đèn phòng khách"), không chứa `device_id` (UUID). Cơ chế:

1. `voice-service` giữ 1 **cache cục bộ trong bộ nhớ** ánh xạ `(home_id, room_name, device_type) → [device_id, ...]`.
2. Cache được làm mới bằng cách gọi `GET /internal/devices?homeId=...` **định kỳ mỗi 60 giây** chạy nền (background thread), và **làm mới ngay lập tức** nếu tra cache bị miss (phòng ngừa trường hợp thiết bị vừa được thêm/đổi tên mà cache chưa kịp cập nhật).
3. **Xử lý trường hợp nhiều thiết bị cùng loại trong 1 phòng** (ví dụ phòng khách có 2 đèn): mặc định áp dụng hành động cho **tất cả** thiết bị khớp điều kiện, không tự chọn 1 cái hay hỏi ngược lại người dùng — vì hỏi ngược lại phá vỡ trải nghiệm "single-turn" đơn giản của MVP, trong khi áp dụng cho tất cả là hành vi an toàn và dễ đoán (tắt/bật tất cả đèn phòng khách là điều người dùng thường mong đợi khi nói chung chung như vậy). Phản hồi xác nhận nên nêu rõ số lượng: *"Đã bật 2 đèn phòng khách"*.
4. Nếu không khớp bất kỳ thiết bị nào → trả lỗi rõ ràng cho người dùng ("Không tìm thấy đèn nào ở phòng khách"), không suy luận hay tự ý chọn thiết bị khác.

### 2.5 Ngân sách latency theo từng bước (mới)

Thay vì chỉ có 1 ngưỡng tổng "≤1.5s" (khó debug khi đo thực tế), chia ngân sách như sau — dùng làm mốc khi benchmark từng bước ở Giai đoạn 1:

| Bước | Ngân sách đề xuất | Ghi chú |
| --- | --- | --- |
| Audio gate (phát hiện bắt đầu/kết thúc nói) | \~50ms | Thuật toán energy-based, gần như tức thời |
| STT (faster-Whisper, model `base`, int8) | \~700-900ms | Phần chiếm nhiều thời gian nhất, cần benchmark kỹ ở Giai đoạn 1 |
| Device resolution (cache hit) | \~5-10ms | Tra cache trong bộ nhớ, không gọi mạng |
| Device resolution (cache miss, phải gọi API) | \~100-200ms | Trường hợp hiếm, chỉ khi thiết bị mới |
| Intent mapping (rule-based) | \~10-20ms | Regex/fuzzy matching, rất nhanh |
| Dispatch (gọi REST nội bộ) | \~100-200ms | Phụ thuộc network LAN + xử lý của Spring Boot |
| **Tổng (trường hợp thường — cache hit)** | **\~900ms-1.2s** | Còn dư khoảng 300-600ms buffer so với ngưỡng 1.5s |

Nếu đo thực tế vượt ngưỡng, ưu tiên tối ưu đúng thứ tự: STT trước (chiếm tỷ trọng lớn nhất) → dispatch (xem lại có cần giữ REST hay chuyển logic) → các bước còn lại.

---

## 3. Công nghệ sử dụng

| Thành phần | Công nghệ | Ghi chú |
| --- | --- | --- |
| Ngôn ngữ | Python 3.11+ | Venv riêng cho `voice-service` |
| Audio gate | Tự viết (RMS/energy threshold qua `numpy`) | Không cần model, không thêm dependency |
| STT + VAD tích hợp | `faster-whisper` (CTranslate2) với `vad_filter=True`, `language="vi"` | Hỗ trợ chính thức ARM64; VAD dùng `onnxruntime` nội bộ, không cần cài `torch` |
| STT dự phòng | `whisper.cpp` | Chỉ dùng nếu benchmark faster-Whisper không đạt ngưỡng; cùng model weight |
| Device resolution | Cache in-memory + `httpx` gọi `GET /internal/devices` | Xem Mục 2.4 |
| Intent mapping | Rule-based (regex + `rapidfuzz`) | Không dùng model ML riêng ở MVP |
| Tích hợp backend | `httpx` gọi REST nội bộ, header `X-Internal-Api-Key` | Không dùng MQTT trong `voice-service` — đó là trách nhiệm của Spring Boot `device`/`iot` |
| Testing | `pytest`, `jiwer` (đo WER) |  |
| Quản lý tiến trình trên Pi | `systemd` service | Tự khởi động lại khi Pi reboot/crash |
| Container hóa (tùy chọn) | Docker (arm64 base image) | Đồng bộ với docker-compose phần Java nếu cần |

---

## 4. Thiết bị phần cứng cần thiết

| Thiết bị | Mục đích | Ghi chú |
| --- | --- | --- |
| Raspberry Pi 4/5 (RAM ≥ 4GB) | Compute cạnh chạy toàn bộ pipeline | Whisper `base` cần \~1GB; không còn cần thêm RAM cho torch vì đã loại bỏ |
| Micro USB hoặc micro array (khuyến nghị ReSpeaker 2-Mic/4-Mic HAT) | Thu âm | Micro array khử ồn tốt hơn, ưu tiên nếu ngân sách cho phép |
| Loa nhỏ (tùy chọn) | Phản hồi giọng nói (TTS) | Ngoài phạm vi FE-02 hiện tại |
| ESP32 node đã pairing | Nhận lệnh từ Spring Boot (qua MQTT, không phải từ voice-service) | Đã có sẵn từ UC05 |
| Mạng LAN/Wi-Fi nội bộ | Kết nối Raspberry Pi ↔ Spring Boot backend ↔ ESP32 | Không cần Internet khi vận hành |

---

## 5. Model AI sử dụng

**Không tự huấn luyện từ đầu:**

- **faster-Whisper**: bắt đầu với size `base` (74M tham số). VAD đã tích hợp sẵn (không còn là model riêng cần quản lý/cập nhật). Luôn ép `language="vi"` khi gọi transcribe.
- Nếu độ chính xác không đạt ngưỡng 15% WER, thử `small`; nếu tốc độ không đạt, thử `tiny`.

**Fine-tune chỉ là phương án dự phòng**, không phải kế hoạch mặc định — chỉ thực hiện nếu đã thử hết các size model mà vẫn không đạt WER.

---

## 6. Chính sách gọi API ngoài

| Thời điểm | Có gọi ra ngoài không? |
| --- | --- |
| Runtime | **Không** — toàn bộ xử lý cục bộ, bao gồm cả bước dispatch (REST nội bộ trong cùng mạng LAN, không ra Internet) |
| Setup lần đầu | **Có, một lần** — `faster-whisper` tải model weight từ Hugging Face Hub, cache local sau đó |

---

## 7. Yêu cầu hoàn thành (Definition of Done)

| Tiêu chí | Ngưỡng |
| --- | --- |
| Độ trễ phản hồi (cache hit, trường hợp thường) | ≤ 1.5 giây, tham chiếu ngân sách ở Mục 2.5 |
| Độ chính xác (WER) | ≤ 15% trên tập lệnh test tiếng Việt, ưu tiên giọng Đà Nẵng |
| Device resolution | Map đúng room+type sang device_id thật ≥95% trên tập test; xử lý đúng trường hợp nhiều thiết bị cùng loại (áp dụng cho tất cả, không đoán bừa 1 cái) |
| Hoạt động khi mất Internet | Bắt buộc — vì xử lý cục bộ, chỉ cần mạng LAN nội bộ giữa Pi và backend, không cần Internet |
| Fallback khi confidence thấp | Không thực thi hành động đoán bừa; trả lỗi rõ ràng |
| Đa lượt (multi-turn) | Giữ đúng ngữ cảnh trong TTL, không suy luận sai sau khi hết hạn |
| Bảo mật endpoint nội bộ | Endpoint `/internal/**` từ chối request thiếu/sai `X-Internal-Api-Key`, không truy cập được từ ngoài mạng LAN |

---

## 8. Môi trường cần setup

### 8.1 Yêu cầu hệ thống

- Raspberry Pi OS 64-bit
- Python 3.11+, `pip`, `venv`
- **Không cần cài Mosquitto trên voice-service** (MQTT là trách nhiệm của Spring Boot, không phải của module này)

### 8.2 requirements.txt (đã tối giản so với v1)

```
faster-whisper>=1.0.0
onnxruntime          # cần cho vad_filter nội bộ của faster-whisper
rapidfuzz
pyyaml
pytest
jiwer
httpx                # gọi REST nội bộ tới Spring Boot
sounddevice          # capture audio từ micro
numpy                # audio gate (RMS) + xử lý mảng audio
python-dotenv        # load INTERNAL_API_KEY từ file .env
```

**Đã loại bỏ so với v1:** `torch` (không cần nữa vì VAD dùng onnxruntime tích hợp sẵn trong faster-whisper), `paho-mqtt` (không cần vì voice-service không publish MQTT trực tiếp).

### 8.3 Biến môi trường cần thiết

```
INTERNAL_API_KEY=<giá trị bí mật, giống hệt cấu hình bên Spring Boot>
BACKEND_BASE_URL=http://localhost:8080   # hoặc địa chỉ LAN của Spring Boot nếu chạy khác máy
```

### 8.4 Các bước setup thủ công cần con người thực hiện (agent KHÔNG tự làm được)

1. Cắm micro vật lý vào Raspberry Pi, xác nhận hệ điều hành nhận diện được (`arecord -l`).
2. Kiểm tra quyền truy cập audio device (thêm user vào group `audio`).
3. Đo thử mức âm lượng/nhiễu nền thực tế tại vị trí lắp đặt — dùng để hiệu chỉnh ngưỡng RMS của audio gate (Mục 2.1 bước 1).
4. Thống nhất giá trị `INTERNAL_API_KEY` với người phụ trách phần Java, cấu hình giống nhau ở cả 2 bên.
5. Xác nhận endpoint `/internal/devices/...` đã tồn tại và trả về đúng định dạng JSON trước khi code `device resolution`/`dispatcher`.
6. Nếu dùng Docker: xác nhận image hỗ trợ arm64, cấu hình quyền truy cập audio vào container (`--device /dev/snd`).

---

## 9. Cấu trúc thư mục đề xuất

```
hesta-project/
├── src/                           ← Spring Boot, KHÔNG được sửa
├── pom.xml
├── voice-service/
│   ├── app/
│   │   ├── __init__.py
│   │   ├── audio_gate.py          # RMS-based: quyết định khi nào bắt đầu/dừng ghi âm
│   │   ├── stt.py                 # wrapper faster-Whisper (vad_filter=True, language="vi")
│   │   ├── device_directory.py    # cache + lookup (room, type) → device_id (Mục 2.4)
│   │   ├── dialogue_state.py      # quản lý context multi-turn (TTL)
│   │   ├── intent.py              # rule-based intent mapping
│   │   ├── dispatcher.py          # gọi REST nội bộ (httpx + X-Internal-Api-Key)
│   │   └── pipeline.py            # orchestrator
│   ├── config/
│   │   ├── voice_config.yaml
│   │   └── intents_vi.json
│   ├── tests/
│   │   ├── test_audio_gate.py
│   │   ├── test_stt_wer.py
│   │   ├── test_device_directory.py
│   │   ├── test_intent.py
│   │   ├── test_dialogue_state.py
│   │   └── test_pipeline_integration.py
│   ├── requirements.txt
│   ├── .env.example
│   ├── README.md
│   └── run.py
├── docker-compose.yml
└── HESTA_DB_DESIGN.md
```

---

## 10. Quy trình triển khai theo giai đoạn

### Giai đoạn 1 — Xác thực khả thi

| Bước | Nội dung | Rủi ro / lưu ý |
| --- | --- | --- |
| 1.1 | Setup môi trường Python trên Pi, cài `requirements.txt` đã tối giản | Không còn rủi ro torch/ARM64 như bản v1 |
| 1.2 | Benchmark faster-Whisper (`tiny`/`base`/`small`) với `vad_filter=True, language="vi"` trên chính Pi | Đo đúng theo ngân sách Mục 2.5; đây là bước quyết định toàn bộ kế hoạch phía sau |
| 1.3 | Hiệu chỉnh ngưỡng RMS của audio gate theo môi trường thật | Phụ thuộc vị trí lắp đặt, cần đo thử nhiều lần |

**Không tiến sang Giai đoạn 2 nếu latency STT chưa đạt ngân sách ở Mục 2.5.**

### Giai đoạn 2 — Xây dựng pipeline lõi

| Bước | Nội dung | Rủi ro / lưu ý |
| --- | --- | --- |
| 2.1 | Viết `audio_gate.py` + `stt.py`, test transcribe vài câu mẫu | Kiểm tra sample rate micro thật khớp 16kHz |
| 2.2 | Viết `device_directory.py`, test cache hit/miss, test refresh định kỳ | Xác nhận endpoint `GET /internal/devices` trả đúng field `room`, `type`, `id` |
| 2.3 | Viết `intent.py` với `intents_vi.json` ban đầu (10-15 lệnh) | Dùng fuzzy matching, không so khớp chuỗi tuyệt đối |
| 2.4 | Viết `dispatcher.py`, test gọi REST nội bộ với API key thật | Xác nhận endpoint từ chối đúng khi thiếu/sai key |

### Giai đoạn 3 — Ghép nối và đo lường chính thức

| Bước | Nội dung | Rủi ro / lưu ý |
| --- | --- | --- |
| 3.1 | Viết `dialogue_state.py`, tích hợp vào `intent.py` | Chốt TTL hợp lý |
| 3.2 | Ghép vào `pipeline.py`, đo latency tổng thật | So với ngân sách Mục 2.5, xác định bước nào vượt nếu có |
| 3.3 | Thu thập bộ test WER thật (nhiều người nói, giọng Đà Nẵng) | Ít nhất vài chục câu |
| 3.4 | Test ngắt Internet (giữ mạng LAN nội bộ) | Xác nhận toàn bộ vẫn hoạt động vì dispatch chỉ qua LAN, không qua Internet |
| 3.5 | Test trường hợp nhiều thiết bị cùng loại trong 1 phòng | Xác nhận đúng hành vi Mục 2.4 bước 3 |
| 3.6 | Đóng gói `systemd` service | Tự khởi động lại sau reboot |

---

## 11. Cách thức testing

| Loại test | Công cụ | Nội dung |
| --- | --- | --- |
| Unit test Audio gate | `pytest` | File audio mẫu có/không có giọng nói, xác nhận gate kích hoạt đúng thời điểm |
| Unit test STT (WER) | `pytest` + `jiwer` | So transcript với ground-truth, tính WER tự động |
| Unit test Device directory | `pytest` | Cache hit/miss, refresh định kỳ, xử lý nhiều thiết bị cùng loại, xử lý không tìm thấy |
| Unit test Intent mapping | `pytest` | Nhiều biến thể câu nói cho cùng 1 ý định |
| Unit test Dialogue state | `pytest` | TTL hết hạn đúng lúc |
| Integration test Dispatch | `pytest` + mock HTTP server | Đúng header `X-Internal-Api-Key`, đúng payload, test cả trường hợp bị từ chối (401/403) |
| Integration test toàn pipeline | Script thủ công + audio mẫu | Latency tổng theo ngân sách Mục 2.5 |
| Test thực địa | Con người | Giọng nói thật, môi trường ồn thực tế |

---

## 12. Rủi ro tổng hợp

1. **faster-Whisper chạy chậm trên Pi** → benchmark sớm, chọn model size phù hợp, dự phòng whisper.cpp.
2. **WER cao với giọng vùng miền/phòng ồn** → thu thập dữ liệu test thật, fallback thủ công khi confidence thấp.
3. **Sai lệch định dạng dữ liệu giữa `GET /internal/devices` và những gì `device_directory.py` kỳ vọng** → thống nhất contract API (field names, format) với người phụ trách `device` package trước khi code, viết test dựa trên contract đó.
4. **Nhầm thiết bị khi nhiều thiết bị cùng loại trong 1 phòng** → đã có hành vi mặc định rõ ràng ở Mục 2.4 (áp dụng cho tất cả), cần test kỹ kịch bản này.
5. **Lộ `INTERNAL_API_KEY`** → không commit file `.env` thật vào git (chỉ commit `.env.example`), rotate key nếu nghi ngờ lộ.
6. **Scope creep** (nhận diện cảm xúc, gesture...) → chỉ làm đúng phạm vi FE-02.
7. **Cache thiết bị bị stale** (thiết bị vừa đổi tên/xóa nhưng cache chưa refresh) → đã có cơ chế refresh định kỳ 60s + fallback khi miss ở Mục 2.4, nhưng vẫn cần test rõ kịch bản race condition này.

---

## 13. Git workflow

- Branch `feature/voice-ai-pipeline` từ `main`.
- Chỉ commit trong `voice-service/` và phần liên quan trong `docker-compose.yml`.
- Không sửa file trong `src/`.
- Merge sớm và thường xuyên — module độc lập.

---

## 14. Cấu hình mẫu (Config Reference)

### 14.1 `voice_config.yaml`

```yaml
stt:
  model_size: base          # tiny/base/small — theo kết quả benchmark Giai đoạn 1
  language: vi
  compute_type: int8
  vad_filter: true
audio_gate:
  rms_threshold: 500         # hiệu chỉnh theo môi trường thật (Mục 8.4 bước 3)
  min_speech_ms: 250
  max_silence_ms: 800        # im lặng liên tục bao lâu thì coi là kết thúc câu nói
backend:
  base_url: "${BACKEND_BASE_URL}"
  internal_api_key_env: "INTERNAL_API_KEY"
  devices_endpoint: "/internal/devices"
  action_endpoint: "/internal/devices/{device_id}/actions"
device_directory:
  refresh_interval_seconds: 60
intent:
  mapping_file: "intents_vi.json"
  context_ttl_seconds: 45
```

### 14.2 `intents_vi.json` (ví dụ 3 lệnh, mở rộng theo cùng cấu trúc)

```json
{
  "intents": [
    {
      "action": "TURN_ON",
      "device_type": "light",
      "patterns": ["bật đèn", "mở đèn"]
    },
    {
      "action": "TURN_OFF",
      "device_type": "light",
      "patterns": ["tắt đèn", "tắt hết đèn"]
    },
    {
      "action": "SET_TEMPERATURE",
      "device_type": "air_conditioner",
      "patterns": ["chỉnh điều hòa", "đặt nhiệt độ"],
      "has_parameter": "temperature"
    }
  ]
}
```

Mỗi `pattern` được so khớp bằng fuzzy matching (`rapidfuzz`) sau khi đã tách riêng phần tên phòng (dùng để tra `device_directory`) ra khỏi câu nói.

---

## 15. AI Agent Prompt — dán vào Claude Code/Cursor để bắt đầu triển khai

```
Bạn đang làm việc trong repository HESTA (Spring Boot + Flyway ở thư mục src/, KHÔNG được sửa).
Nhiệm vụ: triển khai module voice-service (Python) theo đúng tài liệu
VOICE_AI_IMPLEMENTATION_GUIDE.md (bản v2) ở thư mục gốc repo. Đọc kỹ toàn bộ tài liệu trước khi
code — đây là nguồn sự thật duy nhất, mọi quyết định kiến trúc (tích hợp qua REST, không dùng
MQTT/torch, device resolution qua cache, auth bằng API key) đã được CHỐT, không còn phương án
thay thế để tự chọn.

QUY TẮC BẮT BUỘC:
1. Không sửa bất kỳ file nào trong src/ hay pom.xml.
2. Toàn bộ công việc nằm trong voice-service/ theo đúng cấu trúc Mục 9.
3. Không tự ý đổi công nghệ/kiến trúc đã chốt ở Mục 2 và Mục 3 (không thêm lại MQTT/torch vào
   voice-service, không đổi cơ chế auth) mà không hỏi lại tôi trước.
4. Viết test cho mỗi module ngay sau khi hoàn thành (Mục 11), không dồn lại cuối.
5. Không tiến sang giai đoạn tiếp theo (Mục 10) nếu giai đoạn hiện tại chưa đạt tiêu chí — dừng
   lại và báo cáo nếu benchmark không đạt ngân sách latency ở Mục 2.5.

BƯỚC 0 — Kiểm tra môi trường và việc không tự làm được:
- Kiểm tra đang chạy trên Pi thật hay máy dev mô phỏng.
- Kiểm tra endpoint /internal/devices đã tồn tại bên Spring Boot chưa (gọi thử, nếu 404 thì báo
  cho tôi — có thể cần phối hợp với người phụ trách phần Java trước).
- Nếu thiếu phần cứng thật (micro, Pi vật lý), liệt kê rõ danh sách việc ở Mục 8.4 cần tôi tự
  làm trước khi test thực tế, dù vẫn có thể viết code + unit test không cần phần cứng.
- Kiểm tra biến môi trường INTERNAL_API_KEY đã được cấu hình chưa; nếu chưa, tạo file .env.example
  và báo tôi cần lấy giá trị thật từ người phụ trách Java.

BƯỚC 1 — Khởi tạo cấu trúc và cấu hình:
- Tạo cấu trúc thư mục voice-service/ theo Mục 9.
- Tạo requirements.txt theo Mục 8.2 (đã tối giản — KHÔNG thêm torch hay paho-mqtt).
- Tạo voice_config.yaml và intents_vi.json theo đúng schema ở Mục 14 (có thể mở rộng thêm lệnh
  nhưng giữ đúng cấu trúc field).
- Cài đặt venv, dependencies. Nếu lỗi cài đặt, báo cụ thể, không tự ý đổi sang thư viện khác.

BƯỚC 2 — Giai đoạn 1 (Mục 10): Xác thực khả thi
- Viết script benchmark độc lập cho faster-Whisper (tiny/base/small) với vad_filter=True,
  language="vi", đo đúng theo ngân sách Mục 2.5.
- Nếu chạy trên máy dev thay vì Pi thật, ghi rõ cảnh báo số liệu chỉ tham khảo.
- Báo cáo kết quả, đề xuất model size, chờ xác nhận trước khi tiếp tục.

BƯỚC 3 — Giai đoạn 2 (Mục 10): Pipeline lõi
- Triển khai theo đúng thứ tự 2.1 → 2.4 trong Mục 10, bao gồm device_directory.py (Mục 2.4) —
  đây là module dễ bị bỏ sót nhất, bắt buộc phải có trước khi viết intent.py.
- Viết test ngay sau mỗi file, chạy pytest xác nhận pass.
- Nếu cần dữ liệu thật (audio mẫu, phản hồi API thật) mà không có, tạo mock/fixture hợp lý,
  ghi rõ trong README việc cần tôi cung cấp sau.

BƯỚC 4 — Giai đoạn 3 (Mục 10): Ghép nối và đo lường
- Triển khai dialogue_state.py và pipeline.py.
- Viết integration test theo Mục 11, bao gồm test trường hợp nhiều thiết bị cùng loại (Mục 2.4).
- Không tự kết luận "đạt yêu cầu" WER/latency nếu chưa có dữ liệu test thật từ người dùng thật.

BƯỚC 5 — Hoàn thiện:
- Viết README.md: kết quả benchmark, việc còn cần làm thủ công, hướng dẫn chạy service, giá trị
  mẫu cho .env.example.
- Đề xuất nội dung thêm vào docker-compose.yml gốc (không tự sửa file này nếu chưa xác nhận).

Nếu bất kỳ quyết định nào mâu thuẫn với tài liệu VOICE_AI_IMPLEMENTATION_GUIDE.md, dừng lại và
hỏi tôi thay vì tự quyết định.
```