# ==============================================================================
# HESTA SMART HOME - EDGE AI CONFIGURATION
# ==============================================================================
# File cấu hình tập trung cho toàn bộ hệ thống Thị giác AI HESTA.
# Cho phép chuyển đổi linh hoạt giữa Webcam Laptop và Camera IP thật ngoài đời.
# ==============================================================================

import os

# Tự động nạp biến môi trường từ file .env cùng thư mục (nếu có)
_env_path = os.path.join(os.path.dirname(__file__), ".env")
if os.path.exists(_env_path):
    with open(_env_path, "r", encoding="utf-8") as _f:
        for _line in _f:
            _line = _line.strip()
            if _line and not _line.startswith("#") and "=" in _line:
                _k, _v = _line.split("=", 1)
                os.environ.setdefault(_k.strip(), _v.strip().strip('"').strip("'"))

# ==============================================================================
# 1. CẤU HÌNH NGUỒN CAMERA (CAMERA SOURCE SELECTION)
# ==============================================================================
# HƯỚNG DẪN DÀNH CHO BẠN:
# - Hiện tại chưa có cam IP: Để nguyên CAMERA_MODE = "WEBCAM"
# - Tuần sau Camera IP Imou/Ezviz về: Đổi thành CAMERA_MODE = "IP_CAMERA"
# ==============================================================================

# LỰA CHỌN: "WEBCAM" (Camera laptop) HOẶC "IP_CAMERA" (Camera IP ngoài đời)
CAMERA_MODE = os.getenv("HESTA_CAMERA_MODE", "WEBCAM")

# ------------------------------------------------------------------------------
# [LỰA CHỌN 1]: CẤU HÌNH WEBCAM LAPTOP / MÁY TÍNH
# ------------------------------------------------------------------------------
# 0: Webcam tích hợp mặc định trên laptop
# 1: Camera USB cắm ngoài (UGREEN Camera 4K / USB Camera)
WEBCAM_INDEX = int(os.getenv("HESTA_WEBCAM_INDEX", "1"))

# ------------------------------------------------------------------------------
# [LỰA CHỌN 2]: CẤU HÌNH CAMERA IP THẬT (RTSP STREAM KHI CAM VỀ TUẦN SAU)
# ------------------------------------------------------------------------------
# Cú pháp chuẩn RTSP của các dòng camera phổ biến tại Việt Nam:
#
# 1. IMOU RANGER 2 / CUE 2C / CRUISER (Khuyên dùng):
#    "rtsp://admin:<PASSWORD_DUOI_DICH_CAM>@<IP_CAMERA>:554/cam/realmonitor?channel=1&subtype=0"
#    Ví dụ: "rtsp://admin:L2A8E1D9@192.168.1.168:554/cam/realmonitor?channel=1&subtype=0"
#
# 2. EZVIZ H6C / C6N / C3W:
#    "rtsp://admin:<VERIFICATION_CODE_6_KY_TU>@<IP_CAMERA>:554/h264Preview_01_main"
#    Ví dụ: "rtsp://admin:ABCXYZ@192.168.1.180:554/h264Preview_01_main"
#
# 3. HIKVISION / DAHUA:
#    "rtsp://admin:<PASSWORD>@<IP_CAMERA>:554/Streaming/Channels/101"
# ------------------------------------------------------------------------------
RTSP_URL = os.getenv(
    "HESTA_RTSP_URL",
    "rtsp://admin:CHANGE_ME@192.168.1.168:554/cam/realmonitor?channel=1&subtype=0"
)
# ⚠️ RTSP credentials PHẢI được cấu hình qua biến môi trường HESTA_RTSP_URL, KHÔNG hardcode password thật

# Kích thước frame xử lý AI (Resize để đạt FPS tối đa trên Orange Pi 5 Plus)
INFERENCE_WIDTH = 640
INFERENCE_HEIGHT = 480

# ==============================================================================
# 2. CẤU HÌNH MQTT BROKER (KẾT NỐI HESTA BACKEND & ESP32)
# ==============================================================================
MQTT_HOST = os.getenv("HESTA_MQTT_HOST", "192.168.88.120")
MQTT_PORT = int(os.getenv("HESTA_MQTT_PORT", "1883"))
MQTT_TOPIC_FALL_EVENT = "hesta/sensor/fall-detection/{deviceId}/event"
MQTT_TOPIC_SIREN_CMD = "hesta/actuators/siren"

# ==============================================================================
# 3. CẤU HÌNH THIẾT BỊ, MÔ HÌNH & NPU HARDWARE
# ==============================================================================
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DEVICE_ID = os.getenv("HESTA_DEVICE_ID", "cam_livingroom_01")

# Backend AI: AUTO (Tự động phát hiện NPU/CPU) | PT (PyTorch Laptop) | RKNN (Orange Pi 5 Plus NPU)
AI_BACKEND = os.getenv("HESTA_AI_BACKEND", "AUTO")
YOLO_MODEL_PATH = os.path.join(BASE_DIR, "yolov8n-pose.pt")
RKNN_MODEL_PATH = os.path.join(BASE_DIR, "yolov8n-pose.rknn")

CONFIDENCE_THRESHOLD = 0.25
AUTO_RECORD_SNAPSHOT = True
SNAPSHOT_DIR = os.path.join(BASE_DIR, "snapshots")

# ==============================================================================
# 4. BẢO MẬT EDGE AI SERVER
# ==============================================================================
# API Key bảo vệ các endpoint nhạy cảm (trigger_demo_fall, dismiss).
# Nếu để trống (""), các endpoint sẽ KHÔNG yêu cầu API key (chế độ dev).
EDGE_API_KEY = os.getenv("HESTA_EDGE_API_KEY", "")
# CORS origins cho phép truy cập Edge AI REST API (phân tách bằng dấu phẩy)
CORS_ORIGINS = os.getenv("HESTA_CORS_ORIGINS", "http://localhost:5173,http://localhost:3000").split(",")
# API Key cho internal service-to-service (AI Edge → Spring Boot Backend)
INTERNAL_SERVICE_API_KEY = os.getenv("HESTA_INTERNAL_API_KEY", "")
