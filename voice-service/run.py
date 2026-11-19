"""
HESTA Voice Service — Entry point
Khởi chạy pipeline voice control.

Chạy: python run.py [--config config/voice_config.yaml]

Yêu cầu:
- Micro vật lý đã cắm và được nhận diện
- File .env đã cấu hình (INTERNAL_API_KEY, BACKEND_BASE_URL)
- Spring Boot backend đang chạy
"""

import argparse
import logging
import signal
import sys
from pathlib import Path

import numpy as np

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("voice-service")


def main():
    parser = argparse.ArgumentParser(description="HESTA Voice Service")
    parser.add_argument("--config", type=str, default="config/voice_config.yaml",
                        help="Path tới voice_config.yaml")
    parser.add_argument("--intents", type=str, default=None,
                        help="Path tới intents_vi.json (mặc định: lấy từ config)")
    parser.add_argument("--home-id", type=str, default="",
                        help="Home ID mặc định")
    parser.add_argument("--text-mode", action="store_true",
                        help="Chế độ text (không dùng micro, gõ lệnh bằng bàn phím)")
    args = parser.parse_args()

    # Import pipeline
    from app.pipeline import VoicePipeline

    config_path = Path(args.config)
    if not config_path.exists():
        logger.error("Không tìm thấy file config: %s", config_path)
        sys.exit(1)

    intents_path = Path(args.intents) if args.intents else None

    logger.info("Khởi tạo Voice Pipeline...")
    pipeline = VoicePipeline.from_config(
        config_path=config_path,
        intents_path=intents_path,
        home_id=args.home_id,
    )

    # Graceful shutdown
    def shutdown_handler(signum, frame):
        logger.info("Đang dừng service...")
        pipeline.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown_handler)
    signal.signal(signal.SIGTERM, shutdown_handler)

    logger.info("Loading STT model + device cache...")
    pipeline.start()
    logger.info("✅ Voice Service sẵn sàng!")

    if args.text_mode:
        _run_text_mode(pipeline)
    else:
        _run_audio_mode(pipeline)


def _run_text_mode(pipeline):
    """Chế độ test bằng text (không cần micro)."""
    logger.info("Chế độ TEXT MODE — gõ lệnh tiếng Việt, 'exit' để thoát.")
    print("\n" + "=" * 50)
    print("HESTA Voice Service — Text Mode")
    print("Gõ lệnh tiếng Việt (VD: 'bật đèn phòng khách')")
    print("Gõ 'exit' để thoát.")
    print("=" * 50 + "\n")

    while True:
        try:
            text = input("🎤 > ").strip()
            if text.lower() in ("exit", "quit", "q"):
                break
            if not text:
                continue

            result = pipeline.process_text(text)
            if result.success:
                print(f"✅ {result.message}")
            else:
                print(f"❌ {result.message}")

            if result.step_durations:
                durations = ", ".join(
                    f"{k}={v:.0f}ms" for k, v in result.step_durations.items()
                )
                print(f"   ⏱  {durations}")
            print()

        except EOFError:
            break
        except KeyboardInterrupt:
            break

    pipeline.stop()
    logger.info("Bye!")


def _run_audio_mode(pipeline):
    """Chế độ audio thật (cần micro vật lý)."""
    try:
        import sounddevice as sd
    except Exception as e:
        logger.error("Không thể import sounddevice: %s", e)
        logger.error("Kiểm tra micro đã cắm và driver audio đã cài.")
        sys.exit(1)

    sample_rate = pipeline.audio_gate.config.sample_rate
    chunk_size = pipeline.audio_gate.config.chunk_size

    logger.info("Bắt đầu lắng nghe (sample_rate=%d, chunk=%d)...", sample_rate, chunk_size)
    logger.info("Nói lệnh tiếng Việt. Ctrl+C để dừng.")

    try:
        with sd.InputStream(
            samplerate=sample_rate,
            channels=1,
            dtype="int16",
            blocksize=chunk_size,
        ) as stream:
            while True:
                # Đọc 1 chunk
                data, overflowed = stream.read(chunk_size)
                if overflowed:
                    logger.warning("Audio buffer overflow!")

                chunk = data.flatten()
                pipeline.audio_gate.feed(chunk)

                if pipeline.audio_gate.is_speech_complete():
                    audio = pipeline.audio_gate.get_audio()
                    if audio is not None:
                        logger.info("Phát hiện giọng nói — đang xử lý...")
                        result = pipeline.process_audio(audio)

                        if result.success:
                            print(f"✅ {result.message}")
                        else:
                            print(f"❌ {result.message}")

                        if result.transcript:
                            print(f"   📝 \"{result.transcript}\"")

                    pipeline.audio_gate.reset()

    except KeyboardInterrupt:
        pass
    finally:
        pipeline.stop()
        logger.info("Bye!")


if __name__ == "__main__":
    main()
