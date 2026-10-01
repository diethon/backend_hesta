# HESTA Voice Service

Module điều khiển giọng nói tiếng Việt cục bộ cho hệ thống smart home HESTA.

## Tổng quan

- **Chức năng:** Nhận lệnh giọng nói tiếng Việt → nhận dạng → xác định thiết bị → gửi lệnh tới backend Spring Boot
- **Xử lý hoàn toàn cục bộ:** Không gửi audio/transcript lên cloud (ngoài lần đầu tải model)
- **Tích hợp qua REST nội bộ:** Gọi API Spring Boot với `X-Internal-Api-Key`, không dùng MQTT trực tiếp
- **Hỗ trợ đa lượt:** Giữ context hội thoại 45 giây cho lệnh liên tiếp

## Kiến trúc Pipeline

```
Micro → [Audio Gate (RMS)] → [STT (faster-Whisper)] → [Intent Mapping (regex+fuzzy)]
         → [Device Resolution (cache)] → [Dispatch (REST)] → Spring Boot → ESP32
```

## Yêu cầu

- Python 3.11+
- Raspberry Pi 4/5 (RAM ≥ 4GB) — hoặc máy dev để phát triển/test
- Micro USB hoặc micro array

## Cài đặt

```bash
cd voice-service
python -m venv venv
source venv/bin/activate       # Linux/Pi
# hoặc: .\venv\Scripts\activate   # Windows

pip install -r requirements.txt

# Cấu hình
cp .env.example .env
# Sửa .env: điền INTERNAL_API_KEY và BACKEND_BASE_URL
```

## Cấu hình

### File `.env`
```
INTERNAL_API_KEY=<giá trị bí mật, giống bên Spring Boot>
BACKEND_BASE_URL=http://localhost:8080
```

### File `config/voice_config.yaml`
- `stt.model_size`: tiny/base/small — chọn theo kết quả benchmark
- `audio_gate.rms_threshold`: hiệu chỉnh theo môi trường thật
- `device_directory.refresh_interval_seconds`: tần suất refresh cache thiết bị

### File `config/intents_vi.json`
- Danh sách intent và patterns tiếng Việt
- Mở rộng thêm lệnh bằng cách thêm entry theo cùng cấu trúc

## Chạy service

### Chế độ Text (test không cần micro)
```bash
python run.py --text-mode --config config/voice_config.yaml
```

### Chế độ Audio (cần micro vật lý)
```bash
python run.py --config config/voice_config.yaml --home-id <home-id>
```

### Benchmark STT
```bash
python benchmark_stt.py --audio path/to/test.wav --models tiny,base,small
```

## Chạy tests

```bash
python -m pytest tests/ -v
```

## Kết quả benchmark STT

> ⚠️ Chưa benchmark trên Pi thật. Số liệu dưới đây cần được cập nhật sau khi chạy trên Raspberry Pi.

| Model | Load (ms) | STT avg (ms) | Trạng thái |
|-------|-----------|---------------|------------|
| tiny  | TBD       | TBD           | TBD        |
| base  | TBD       | TBD           | TBD        |
| small | TBD       | TBD           | TBD        |

**Ngân sách latency (Mục 2.5):** STT ≤ 900ms, tổng pipeline ≤ 1.5s

## Việc còn cần làm thủ công

| # | Việc | Ai cần làm | Khi nào |
|---|------|-----------|---------|
| 1 | Tạo endpoint `GET /internal/devices?homeId=...` trong Spring Boot | Người phụ trách Java | Trước khi test device resolution thật |
| 2 | Tạo endpoint `POST /internal/devices/{deviceId}/actions` trong Spring Boot | Người phụ trách Java | Trước khi test dispatch thật |
| 3 | Tạo filter xác thực `X-Internal-Api-Key` cho prefix `/internal/**` | Người phụ trách Java | Trước khi test auth thật |
| 4 | Thống nhất giá trị `INTERNAL_API_KEY` | Cả 2 bên | Trước khi tích hợp |
| 5 | Cắm micro vào Pi, xác nhận `arecord -l` | Bạn | Trước khi test audio thật |
| 6 | Đo nhiễu nền, hiệu chỉnh `rms_threshold` | Bạn | Trước khi deploy |
| 7 | Benchmark STT trên Pi thật | Bạn | Quyết định model size |
| 8 | Thu thập bộ test WER (giọng Đà Nẵng, ≥ vài chục câu) | Bạn + nhóm | Đánh giá chất lượng |
| 9 | Cấu hình systemd service | Bạn | Khi deploy lên Pi |
| 10 | Cung cấp file audio mẫu tiếng Việt cho unit test STT | Bạn | Cải thiện test coverage |

## Cấu trúc thư mục

```
voice-service/
├── app/
│   ├── __init__.py
│   ├── audio_gate.py          # RMS-based audio gate
│   ├── stt.py                 # faster-Whisper wrapper
│   ├── device_directory.py    # Device cache + resolution
│   ├── intent.py              # Rule-based intent mapping
│   ├── dispatcher.py          # REST dispatch tới Spring Boot
│   ├── dialogue_state.py      # Multi-turn context (TTL)
│   └── pipeline.py            # Orchestrator
├── config/
│   ├── voice_config.yaml
│   └── intents_vi.json
├── tests/
│   ├── test_audio_gate.py
│   ├── test_stt_wer.py
│   ├── test_device_directory.py
│   ├── test_intent.py
│   ├── test_dialogue_state.py
│   ├── test_dispatcher.py
│   └── test_pipeline_integration.py
├── benchmark_stt.py
├── requirements.txt
├── .env.example
├── README.md
└── run.py
```

## Đề xuất Docker Compose

Thêm vào `docker-compose.yml` gốc (chưa tự sửa, cần xác nhận trước):

```yaml
  voice-service:
    build:
      context: ./voice-service
      dockerfile: Dockerfile
    depends_on:
      - backend
    environment:
      - INTERNAL_API_KEY=${INTERNAL_API_KEY}
      - BACKEND_BASE_URL=http://backend:8080
    devices:
      - /dev/snd:/dev/snd    # Truy cập audio device
    restart: unless-stopped
    network_mode: host        # Hoặc dùng internal network
```

## Đề xuất Dockerfile

```dockerfile
FROM python:3.11-slim-bookworm

# Cho ARM64 (Pi)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libasound2-dev portaudio19-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

CMD ["python", "run.py", "--config", "config/voice_config.yaml"]
```

## Đề xuất systemd service

```ini
# /etc/systemd/system/hesta-voice.service
[Unit]
Description=HESTA Voice Service
After=network.target

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi/hesta/voice-service
ExecStart=/home/pi/hesta/voice-service/venv/bin/python run.py --config config/voice_config.yaml
Restart=always
RestartSec=5
Environment=INTERNAL_API_KEY=<value>
Environment=BACKEND_BASE_URL=http://localhost:8080

[Install]
WantedBy=multi-user.target
```
