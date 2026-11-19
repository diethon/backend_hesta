"""
HESTA Voice Service — Offline Matrix TTS Cache Generator.

Script tiện ích giúp tự động sinh âm thanh MP3 (giọng Microsoft Hoài My)
cho các câu phản hồi và lưu vĩnh viễn vào bộ nhớ đệm (.cache/tts hoặc assets/tts_cache).

Chạy theo đúng thiết bị & phòng THỰC TẾ từ Backend (khuyên dùng cho Orange Pi):
    python scripts/generate_tts_matrix.py --from-backend

Hoặc sinh theo danh sách phòng cố định:
    python scripts/generate_tts_matrix.py --rooms "phòng khách" "phòng ngủ master"
"""

import argparse
import asyncio
import logging
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

# Thêm voice-service root vào sys.path
BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))

from app.matrix_generator import generate_matrix_phrases, generate_phrases_for_backend_devices
from app.tts_manager import clean_tts_text, TTSManager

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s]: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("generate_tts_matrix")


def _fetch_devices_from_backend(backend_url: str, api_key: str) -> list[dict]:
    import httpx

    url = f"{backend_url.rstrip('/')}/internal/devices"
    headers = {"X-Internal-Api-Key": api_key} if api_key else {}
    logger.info("Đang lấy danh sách thiết bị thực tế từ %s ...", url)
    resp = httpx.get(url, headers=headers, timeout=10.0)
    resp.raise_for_status()
    data = resp.json()
    if isinstance(data, dict) and "result" in data:
        data = data["result"]
    if not isinstance(data, list):
        raise ValueError(f"Định dạng phản hồi /internal/devices không hợp lệ: {type(data)}")
    return data


async def main():
    load_dotenv(BASE_DIR / ".env")

    parser = argparse.ArgumentParser(description="HESTA Voice AI — Matrix TTS Generator")
    parser.add_argument(
        "--from-backend",
        action="store_true",
        help="Tự động lấy đúng phòng & thiết bị thực tế từ Backend (/internal/devices)",
    )
    parser.add_argument(
        "--rooms",
        nargs="*",
        default=[],
        help="Danh sách các phòng bổ sung (vd: 'phòng của đức' 'phòng gaming')",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default=None,
        help="Thư mục lưu cache MP3 (mặc định: .cache/tts khi dùng --from-backend, ngược lại assets/tts_cache)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.25,
        help="Thời gian nghỉ giữa mỗi lần gọi Edge-TTS (giây) để tránh rate-limit",
    )
    args = parser.parse_args()

    out_rel = args.output_dir or (".cache/tts" if args.from_backend else "assets/tts_cache")
    out_dir = BASE_DIR / out_rel
    out_dir.mkdir(parents=True, exist_ok=True)

    tts = TTSManager(cache_dir=out_dir, bundled_dir=BASE_DIR / "assets" / "tts_cache")

    if args.from_backend:
        backend_url = os.environ.get("BACKEND_BASE_URL", "http://localhost:8080")
        api_key = os.environ.get("INTERNAL_API_KEY", "")
        devices = _fetch_devices_from_backend(backend_url, api_key)
        logger.info("Đã nhận %d thiết bị từ Backend.", len(devices))
        phrases = generate_phrases_for_backend_devices(devices)
    else:
        phrases = generate_matrix_phrases(rooms=args.rooms, include_compounds=True)

    logger.info("=== HESTA Matrix TTS Generator ===")
    logger.info("Tổng số câu mục tiêu: %d", len(phrases))
    logger.info("Thư mục lưu: %s", out_dir)

    to_generate = []
    for p in phrases:
        clean = clean_tts_text(p)
        if clean and not tts.get_cached_audio(clean):
            to_generate.append(clean)

    logger.info("Số câu đã có sẵn trong cache: %d", len(phrases) - len(to_generate))
    logger.info("Số câu cần sinh mới: %d", len(to_generate))

    if not to_generate:
        logger.info("✅ Tất cả các câu đều đã có sẵn trong cache!")
        return

    success_count = 0
    fail_count = 0

    for idx, phrase in enumerate(to_generate, 1):
        print(f"[{idx}/{len(to_generate)}] Đang sinh: '{phrase}' ...", end="", flush=True)
        try:
            audio = await tts.synthesize(phrase, timeout_sec=8.0, allow_fallback=False)
            if audio:
                success_count += 1
                print(" OK")
            else:
                fail_count += 1
                print(" FAILED")
        except Exception as e:
            fail_count += 1
            print(f" ERROR ({e})")

        await asyncio.sleep(args.delay)

    logger.info("=== Hoàn tất! Thành công: %d, Lỗi: %d ===", success_count, fail_count)


if __name__ == "__main__":
    asyncio.run(main())
