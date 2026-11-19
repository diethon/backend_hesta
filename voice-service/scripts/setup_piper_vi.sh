#!/usr/bin/env bash
set -euo pipefail

# Cài đặt Piper Offline Neural TTS tiếng Việt (vi_VN-vais1000-medium) cho Orange Pi 5 Plus (ARM64)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PIPER_DIR="$ROOT_DIR/assets/piper"

echo "=== 1. Cài đặt piper-tts vào virtualenv hiện tại ==="
pip install --upgrade piper-tts

echo "=== 2. Tải model giọng tiếng Việt (vi_VN-vais1000-medium, ~63MB) ==="
mkdir -p "$PIPER_DIR"
BASE_URL="https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/vi/vi_VN/vais1000/medium"

if [ ! -f "$PIPER_DIR/vi_VN-vais1000-medium.onnx" ]; then
  curl -L --progress-bar "$BASE_URL/vi_VN-vais1000-medium.onnx" -o "$PIPER_DIR/vi_VN-vais1000-medium.onnx"
else
  echo "Đã có sẵn vi_VN-vais1000-medium.onnx"
fi

if [ ! -f "$PIPER_DIR/vi_VN-vais1000-medium.onnx.json" ]; then
  curl -L -sS "$BASE_URL/vi_VN-vais1000-medium.onnx.json" -o "$PIPER_DIR/vi_VN-vais1000-medium.onnx.json"
else
  echo "Đã có sẵn vi_VN-vais1000-medium.onnx.json"
fi

echo "=== 3. Kiểm thử tốc độ tổng hợp offline trên Orange Pi ==="
cd "$ROOT_DIR"
python -c "
import asyncio, time
from app.tts_manager import TTSManager
async def test():
    mgr = TTSManager()
    assert mgr.is_piper_ready, 'Piper chưa sẵn sàng!'
    t0 = time.perf_counter()
    data = await mgr.synthesize_piper('Đã bật đèn phòng khách và đóng rèm phòng ngủ.')
    dt = (time.perf_counter() - t0) * 1000
    print(f'✅ Piper Offline OK: {len(data)} bytes WAV trong {dt:.0f} ms')
asyncio.run(test())
"
