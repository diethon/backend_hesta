"""
HESTA Voice Service — Offline Matrix TTS Cache Generator.

Script tiện ích chạy ngầm giúp tự động sinh 100% âm thanh MP3 cho tất cả các câu
phản hồi thông dụng (đèn, quạt, điều hòa, phòng khách, phòng ngủ, phòng gaming,...)
và lưu vĩnh viễn vào thư mục assets/tts_cache/.

Chạy:
    python scripts/generate_tts_matrix.py
    python scripts/generate_tts_matrix.py --rooms "phòng khách" "phòng ngủ" "phòng của đức"
"""

import argparse
import asyncio
import logging
import sys
from pathlib import Path

# Thêm voice-service root vào sys.path
BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))

from app.matrix_generator import generate_matrix_phrases
from app.tts_manager import clean_tts_text, TTSManager

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s]: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("generate_tts_matrix")


async def main():
    parser = argparse.ArgumentParser(description="HESTA Voice AI — Matrix TTS Generator")
    parser.add_argument(
        "--rooms",
        nargs="*",
        default=[],
        help="Danh sách các phòng bổ sung (vd: 'phòng của đức' 'phòng gaming')",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default="assets/tts_cache",
        help="Thư mục lưu trữ cache âm thanh MP3",
    )
    args = parser.parse_args()

    out_dir = BASE_DIR / args.output_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    tts = TTSManager(cache_dir=out_dir, bundled_dir=out_dir)

    phrases = generate_matrix_phrases(rooms=args.rooms, include_compounds=True)
    logger.info("=== HESTA Matrix TTS Generator ===")
    logger.info("Tổng số câu mẫu trong ma trận: %d", len(phrases))
    logger.info("Thư mục lưu: %s", out_dir)

    to_generate = []
    for p in phrases:
        clean = clean_tts_text(p)
        if clean and not tts.get_cached_audio(clean):
            to_generate.append(clean)

    logger.info("Số câu đã có sẵn trong cache: %d", len(phrases) - len(to_generate))
    logger.info("Số câu cần sinh mới: %d", len(to_generate))

    if not to_generate:
        logger.info("✅ Tất cả các câu trong ma trận đều đã có sẵn trong cache!")
        return

    success_count = 0
    fail_count = 0

    for idx, phrase in enumerate(to_generate, 1):
        print(f"[{idx}/{len(to_generate)}] Đang sinh âm thanh: '{phrase}' ...", end="", flush=True)
        try:
            audio = await tts.synthesize(phrase, timeout_sec=8.0)
            if audio:
                success_count += 1
                print(" OK")
            else:
                fail_count += 1
                print(" FAILED (empty audio)")
        except Exception as e:
            fail_count += 1
            print(f" ERROR ({e})")

        # Nghỉ 0.15s để tránh nghẽn server Microsoft
        await asyncio.sleep(0.15)

    logger.info("=== Hoàn tất! Thành công: %d, Lỗi: %d ===", success_count, fail_count)


if __name__ == "__main__":
    asyncio.run(main())
