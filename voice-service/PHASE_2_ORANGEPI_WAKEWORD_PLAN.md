# BẢN KẾ HOẠCH TRIỂN KHAI GIAI ĐOẠN 2: AI VOICE ĐỘC LẬP TRÊN ORANGE PI 5
> **Dự án**: HESTA Smart Home — AI Voice Assistant  
> **Phần cứng mục tiêu**: Orange Pi 5 (8GB RAM, Chip 8 nhân Rockchip RK3588, 6 TOPS NPU)  
> **Mục tiêu**: Người dùng đứng gần Orange Pi nói *"Hesta ơi"* hoặc *"Hesta"*, hệ thống phát loa *"Hesta nghe"* / *"Tôi đây"*, thu nhận lệnh điều khiển (VD: *"Đóng cửa chính"*), thực thi qua Spring Boot/MQTT và phát giọng đọc xác nhận *"Đã đóng cửa chính"*.  
> **Nguyên tắc**: Xử lý 100% Cục bộ (Local/Offline-first), bảo mật quyền riêng tư, phản hồi tức thì dưới 1.5 giây.

---

## 1. Kiến trúc Tổng thể (Two-Turn State Machine)

Hệ thống hoạt động theo máy trạng thái 5 bước khép kín nhằm tối ưu hóa CPU và loại bỏ độ trễ:

```
[ BƯỚC 1: IDLE - ALWAYS LISTENING ]
  │  Micro USB thu âm buffer 80ms liên tục (16kHz, mono, int16)
  │  Module Wake-Word Detector chạy lượng tử hóa ONNX (tiêu thụ < 3% CPU)
  ▼
[ BƯỚC 2: PHÁT HIỆN "Hesta ơi" / "Hesta" ]
  │  Độ trễ nhận diện < 80ms
  │  Loa Orange Pi phát âm thanh tức thì: "Hesta nghe!" hoặc "Tôi đây!" + Chime sound
  │  (Sử dụng file âm thanh thu sẵn .wav, không chờ AI tổng hợp để đạt độ trễ < 50ms)
  ▼
[ BƯỚC 3: ACTIVE LISTENING - THU ÂM CÂU LỆNH ]
  │  Audio Gate kích hoạt lắng nghe câu lệnh tiếp theo
  │  Tự động cắt câu lệnh khi phát hiện khoảng lặng 600ms (max_silence_ms)
  ▼
[ BƯỚC 4: PIPELINE THỰC THI (ĐÃ CÓ SẴN) ]
  │  Faster-Whisper (int8, model base/small): chuyển âm thanh → văn bản (~600-800ms)
  │  Intent Mapper + Device Directory: xác định thiết bị & hành động (~20ms)
  │  Dispatcher REST (Fast-return 250ms): gửi Spring Boot → publish MQTT Broker
  ▼
[ BƯỚC 5: LOCAL TTS - PHÁT GIỌNG ĐỌC KẾT QUẢ ]
  │  Loa Orange Pi cất giọng: "Đã đóng cổng chính" (Piper TTS hoặc Audio Cache)
  │  Thời gian tổng hợp & phát ra loa: ~150-250ms
  ▼
[ QUAY LẠI BƯỚC 1: IDLE CHỜ TỪ KHÓA MỚI ]
```

---

## 2. Yêu cầu Phần cứng & Cấu hình Âm thanh (Orange Pi 5)

### 2.1. Thiết bị Ngoại vi:
1. **Microphone**:
   - USB Microphone đa hướng hoặc Loa hội nghị USB có chống ồn/khử vọng (VD: eMeet Luna, Anker PowerConf, hoặc USB Mic Boya/Generic).
2. **Loa (Speaker)**:
   - Cắm loa ngoài qua cổng 3.5mm Audio Jack của Orange Pi 5, hoặc loa tích hợp của USB Speakerphone.

### 2.2. Chuẩn bị Hệ điều hành (Armbian/Debian Linux ARM64):
```bash
# 1. Cài đặt các thư viện lõi ALSA & PortAudio
sudo apt update
sudo apt install -y portaudio19-dev python3-dev python3-pyaudio alsa-utils libasound2-plugins
sudo apt install -y sox libsox-fmt-all

# 2. Kiểm tra danh sách thiết bị âm thanh
arecord -l   # Xem ID Microphone (card X, device Y)
aplay -l     # Xem ID Loa ngoài (card A, device B)

# 3. Test thu âm thử từ Micro (5 giây):
arecord -D hw:1,0 -d 5 -f S16_LE -c 1 -r 16000 test_mic.wav

# 4. Test phát âm thanh ra Loa:
aplay -D hw:0,0 test_mic.wav
```

---

## 3. Các Module Công nghệ Thành phần

### 3.1. Module Wake-Word (`app/wake_word.py`)
- **Công nghệ lựa chọn**: `openWakeWord` (Mã nguồn mở, hỗ trợ ONNX Runtime tối ưu cho CPU ARM64, không cần internet).
- **Từ khóa**:
  - Huấn luyện custom wake-word: `"hesta"` và `"hesta_oi"`.
  - Hoặc sử dụng Picovoice Porcupine (nếu sử dụng Free AccessKey: tạo file `hesta_vi.ppn`).
- **Buffer audio**: 1280 samples (80ms tại 16000Hz).
- **Tải CPU**: Chỉ chiếm 2% - 3% của 1 core CPU Cortex-A76.

### 3.2. Module Âm thanh Xác nhận Tức thì (`sounds/`)
- Chứa các file âm thanh chất lượng cao 16kHz mono `.wav`:
  - `sounds/chime.wav` (Âm thanh "ding" báo hiệu)
  - `sounds/ready_1.wav` (Giọng nữ nhẹ nhàng: *"Hesta nghe"*)
  - `sounds/ready_2.wav` (Giọng nữ: *"Tôi đây"*)
- Khi wake-word kích hoạt, phát ngẫu nhiên `ready_1` hoặc `ready_2` bằng `sounddevice` hoặc lệnh `aplay`.

### 3.3. Module Text-To-Speech Cục bộ trên Linux (`app/tts.py`)
Orange Pi 5 không có trình duyệt nên không thể dùng Web Speech API. Do đó sử dụng giải pháp lai (Hybrid Local TTS):
1. **Lớp 1 — Audio Prompt Cache (Siêu tốc 0ms)**:
   - Các câu trạng thái cố định được sinh sẵn thành file `.wav` đặt trong `sounds/prompts/`:
     - `da_bat_den.wav`, `da_tat_den.wav`, `da_mo_cong.wav`, `da_dong_cong.wav`, `loi_thuc_thi.wav`,...
     - Khi lệnh khớp câu mẫu, phát trực tiếp file cache (độ trễ 0ms).
2. **Lớp 2 — Piper TTS (Neural Offline TTS)**:
   - Thư viện C++/Python `piper-tts` chạy mô hình ONNX tiếng Việt (`vi_VN-vivos-medium`).
   - Tổng hợp động các câu lệnh có thông số (VD: *"Đã đặt điều hòa phòng ngủ về 24 độ"*) trong ~150ms trên CPU RK3588.
3. **Lớp 3 — Edge-TTS (Dự phòng khi có Internet)**:
   - Thư viện `edge-tts` với giọng `vi-VN-HoaiMyNeural` chất lượng studio.

---

## 4. Cấu trúc Thư mục và File Cần Tạo Mới trong `voice-service`

```
backend_hesta/voice-service/
├── app/
│   ├── wake_word.py        # [MỚI] Bộ nhận diện từ khóa "Hesta ơi" / "Hesta"
│   ├── tts.py              # [MỚI] Bộ phát giọng đọc tiếng Việt trên Orange Pi (Piper/Cache)
│   ├── audio_gate.py       # (Đã có) Thu âm & cắt khoảng lặng
│   ├── stt.py              # (Đã có) Faster-Whisper STT
│   ├── intent.py           # (Đã có) Khớp intent regex & fuzzy
│   ├── device_directory.py # (Đã có) Phân giải thiết bị thông minh
│   ├── dispatcher.py       # (Đã có) Gửi REST Spring Boot song song
│   └── pipeline.py         # (Đã có) Orchestrator kết nối luồng
├── sounds/                 # [MỚI] Thư mục âm thanh phản hồi
│   ├── chime.wav           # Âm báo kích hoạt
│   ├── ready_1.wav         # "Hesta nghe"
│   ├── ready_2.wav         # "Tôi đây"
│   └── prompts/            # File âm thanh mẫu sinh sẵn
├── models/
│   ├── wakeword/           # Model hesta.onnx
│   └── piper/              # Model piper vi_VN-vivos-medium.onnx
├── config/
│   ├── voice_config.yaml   # Bổ sung section wake_word & tts
│   └── intents_vi.json     # (Đã có)
├── run.py                  # Cập nhật vòng lặp 2-turn cho Microphone vật lý
└── server.py               # (Đã có) REST API cho Web/App
```

---

## 5. Chi tiết Cấu hình Mở rộng (`voice_config.yaml`)

```yaml
wake_word:
  enabled: true
  engine: "openwakeword"       # "openwakeword" | "porcupine"
  model_path: "models/wakeword/hesta.onnx"
  threshold: 0.6               # Ngưỡng kích hoạt chống nhận diện nhầm
  cooldown_seconds: 2.0        # Thời gian chờ sau mỗi lần kích hoạt

audio_feedback:
  enabled: true
  ready_sounds:
    - "sounds/ready_1.wav"     # "Hesta nghe"
    - "sounds/ready_2.wav"     # "Tôi đây"
  error_sound: "sounds/error.wav"

tts:
  engine: "piper"              # "piper" | "cache_first" | "edge_tts"
  piper_model: "models/piper/vi_VN-vivos-medium.onnx"
  piper_config: "models/piper/vi_VN-vivos-medium.onnx.json"
  sample_rate: 22050
  speaker_device: "default"    # Thiết bị ra loa ALSA
```

---

## 6. Logic Vòng lặp 2-Turn trong `run.py` (Code Mẫu Định hướng)

```python
# Kịch bản thực thi trong run.py khi chạy trên Orange Pi 5:
from app.wake_word import WakeWordDetector
from app.tts import LocalTTS

def run_standalone_voice(pipeline, config):
    detector = WakeWordDetector(config.wake_word)
    tts = LocalTTS(config.tts)

    logger.info("🎤 HESTA Voice đã bật chế độ chờ từ khóa 'Hesta ơi'...")

    while True:
        # Pha 1: Lắng nghe từ khóa "Hesta ơi" (cực nhẹ CPU)
        audio_chunk = read_mic_stream(chunk_size=1280)
        if detector.detect(audio_chunk):
            logger.info("⚡ Wake-word Triggered: 'Hesta ơi'!")
            
            # Pha 2: Loa phản hồi ngay lập tức
            tts.play_sound("sounds/ready_1.wav") # "Hesta nghe"

            # Pha 3: Bật AudioGate thu âm câu lệnh
            pipeline.audio_gate.reset()
            command_audio = listen_for_command(pipeline.audio_gate)

            if command_audio is not None:
                # Pha 4: Xử lý câu lệnh
                result = pipeline.process_audio(command_audio)
                logger.info("Kết quả: %s", result.message)

                # Pha 5: Phát giọng nói phản hồi kết quả
                tts.speak(result.message)
            else:
                tts.play_sound("sounds/timeout.wav")
```

---

## 7. Các Bước Triển Khai Nhanh Khi Bắt Đầu Giai Đoạn 2

Khi người dùng yêu cầu bắt đầu Giai đoạn 2, thực hiện tuần tự 4 bước sau:

1. **Bước 1 — Cài đặt Dependencies**:
   ```bash
   pip install openwakeword piper-tts sounddevice pygame
   ```
2. **Bước 2 — Tải Model & Thu âm File Mẫu**:
   - Tải model `vi_VN-vivos-medium.onnx` cho Piper.
   - Thu âm 2 file `ready_1.wav` (*"Hesta nghe"*) và `ready_2.wav` (*"Tôi đây"*).
3. **Bước 3 — Tạo Module `wake_word.py` & `tts.py`**:
   - Viết wrapper nhận diện `openWakeWord` stream từ `sounddevice`.
   - Viết engine `LocalTTS` phát file cache hoặc sinh Piper TTS.
4. **Bước 4 — Nâng cấp `run.py` và Test Thực tế trên Orange Pi 5**:
   - Chạy lệnh: `python run.py --config config/voice_config.yaml`
   - Đứng cách 1.5m gọi: *"Hesta ơi"* -> Nghe *"Hesta nghe"* -> *"Bật đèn phòng khách"* -> Nghe *"Đã bật đèn phòng khách"*.

---
*(Bản kế hoạch này đã sẵn sàng. Khi cần triển khai, chỉ cần yêu cầu "Bắt đầu triển khai Giai đoạn 2 theo file kế hoạch" là có thể tiến hành ngay lập tức).*
