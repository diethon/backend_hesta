"""
HESTA Voice Service — Benchmark script cho faster-Whisper
Giai đoạn 1 (Mục 10.1.2): Đo latency STT với các model size (tiny/base/small)

Chạy: python benchmark_stt.py [--audio path/to/audio.wav]
Nếu không có file audio, tạo 1 file giả (sine wave) để đo latency model load + transcribe.

⚠️  Nếu chạy trên máy dev (không phải Pi), số liệu chỉ mang tính THAM KHẢO.
"""

import argparse
import os
import platform
import sys
import time
import tempfile

import numpy as np


def is_raspberry_pi() -> bool:
    """Kiểm tra có đang chạy trên Raspberry Pi không."""
    try:
        with open("/proc/device-tree/model", "r") as f:
            return "raspberry pi" in f.read().lower()
    except (FileNotFoundError, PermissionError):
        return False


def generate_test_audio(duration_seconds: float = 3.0, sample_rate: int = 16000) -> str:
    """Tạo file WAV test với tiếng nói giả (sine wave) để đo latency.
    Trả về path tới file tạm."""
    import wave

    filepath = os.path.join(tempfile.gettempdir(), "hesta_benchmark_test.wav")
    samples = int(duration_seconds * sample_rate)
    # Tạo sine wave đơn giản ở 440Hz
    t = np.linspace(0, duration_seconds, samples, dtype=np.float32)
    audio_data = (np.sin(2 * np.pi * 440 * t) * 0.5 * 32767).astype(np.int16)

    with wave.open(filepath, "w") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(audio_data.tobytes())

    return filepath


def benchmark_model(model_size: str, audio_path: str, compute_type: str = "int8") -> dict:
    """Benchmark 1 model size cụ thể.
    Trả về dict với thông tin: model_size, load_time_ms, transcribe_time_ms, transcript, total_ms.
    """
    from faster_whisper import WhisperModel

    # Đo thời gian load model
    t0 = time.perf_counter()
    model = WhisperModel(model_size, device="cpu", compute_type=compute_type)
    load_time_ms = (time.perf_counter() - t0) * 1000

    # Warm up (chạy 1 lần để loại bỏ overhead khởi tạo)
    segments, _ = model.transcribe(audio_path, language="vi", vad_filter=True)
    _ = list(segments)

    import math

    # Đo thời gian transcribe thật (trung bình 15 lần)
    transcribe_times = []
    transcript = ""
    for _ in range(15):
        t1 = time.perf_counter()
        segments, info = model.transcribe(audio_path, language="vi", vad_filter=True)
        result_segments = list(segments)
        t2 = time.perf_counter()
        transcribe_times.append((t2 - t1) * 1000)
        transcript = " ".join(seg.text.strip() for seg in result_segments)

    avg_transcribe_ms = sum(transcribe_times) / len(transcribe_times)
    std_transcribe_ms = math.sqrt(sum((t - avg_transcribe_ms) ** 2 for t in transcribe_times) / len(transcribe_times))

    return {
        "model_size": model_size,
        "compute_type": compute_type,
        "load_time_ms": round(load_time_ms, 1),
        "transcribe_times_ms": [round(t, 1) for t in transcribe_times],
        "avg_transcribe_ms": round(avg_transcribe_ms, 1),
        "std_transcribe_ms": round(std_transcribe_ms, 1),
        "transcript": transcript,
    }


def main():
    parser = argparse.ArgumentParser(description="HESTA STT Benchmark")
    parser.add_argument("--audio", type=str, default=None,
                        help="Path tới file audio WAV (16kHz mono). Nếu không có, dùng audio giả.")
    parser.add_argument("--models", type=str, default="tiny,base,small",
                        help="Các model size cần benchmark (phân cách bằng dấu phẩy)")
    args = parser.parse_args()

    # Kiểm tra môi trường
    on_pi = is_raspberry_pi()
    print("=" * 70)
    print("HESTA Voice Service — STT Benchmark (Giai đoạn 1)")
    print("=" * 70)
    print(f"Platform:      {platform.platform()}")
    print(f"Processor:     {platform.processor()}")
    print(f"Python:        {sys.version}")
    print(f"Raspberry Pi:  {'✅ CÓ' if on_pi else '❌ KHÔNG — số liệu chỉ THAM KHẢO'}")
    print()

    # Chuẩn bị audio
    if args.audio and os.path.isfile(args.audio):
        audio_path = args.audio
        print(f"Audio file:    {audio_path}")
    else:
        if args.audio:
            print(f"⚠️  File '{args.audio}' không tồn tại, dùng audio giả.")
        else:
            print("ℹ️  Không có file audio thật, dùng sine wave giả (chỉ đo latency, transcript sẽ vô nghĩa).")
        audio_path = generate_test_audio()
        print(f"Audio file:    {audio_path} (tạo tự động)")
    print()

    # Ngân sách latency theo Mục 2.5
    print("Ngân sách latency STT (Mục 2.5): ~700-900ms trên Pi")
    print("-" * 70)

    model_sizes = [s.strip() for s in args.models.split(",")]
    results = []

    for model_size in model_sizes:
        print(f"\n🔄 Benchmark model '{model_size}' (compute_type=int8)...")
        try:
            result = benchmark_model(model_size, audio_path, compute_type="int8")
            results.append(result)

            budget_status = "✅ TRONG NGÂN SÁCH" if result["avg_transcribe_ms"] <= 900 else "⚠️  VƯỢT NGÂN SÁCH"
            if not on_pi:
                budget_status += " (tham khảo, không phải Pi)"

            print(f"   Model load:         {result['load_time_ms']:.0f}ms")
            # print(f"   Transcribe (15 lần): {result['transcribe_times_ms']}")
            print(f"   STT latency:        {result['avg_transcribe_ms']:.0f}ms ± {result['std_transcribe_ms']:.0f}ms  {budget_status}")
            print(f"   Transcript:         \"{result['transcript']}\"")

        except Exception as e:
            print(f"   ❌ LỖI: {e}")
            results.append({"model_size": model_size, "error": str(e)})

    # Tổng kết
    print("\n" + "=" * 70)
    print("TỔNG KẾT")
    print("=" * 70)
    print(f"{'Model':<10} {'Load (ms)':<12} {'STT avg (ms)':<15} {'STT std (ms)':<15} {'Trạng thái'}")
    print("-" * 70)
    for r in results:
        if "error" in r:
            print(f"{r['model_size']:<10} {'LỖI':<12} {'':<15} {'':<15} {r['error']}")
        else:
            status = "OK" if r["avg_transcribe_ms"] <= 900 else "CHẬM"
            print(f"{r['model_size']:<10} {r['load_time_ms']:<12.0f} {r['avg_transcribe_ms']:<15.0f} ±{r['std_transcribe_ms']:<14.0f} {status}")

    if not on_pi:
        print()
        print("⚠️  CẢNH BÁO: Đang chạy trên máy dev, KHÔNG phải Raspberry Pi.")
        print("   Số liệu trên chỉ mang tính THAM KHẢO. Latency thật trên Pi sẽ cao hơn đáng kể.")
        print("   Cần benchmark lại trên Pi thật trước khi chốt model size.")

    # Đề xuất
    print()
    ok_models = [r for r in results if "error" not in r and r["avg_transcribe_ms"] <= 900]
    if ok_models:
        print(f"💡 ĐỀ XUẤT: Giữ lại các model đạt ngân sách để test trên Pi thật. KHÔNG vội chốt model trên máy dev.")
    else:
        print("💡 Không có model nào đạt ngân sách 900ms. Cần xem xét whisper.cpp hoặc chỉ dùng 'tiny'.")


if __name__ == "__main__":
    main()
