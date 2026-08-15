"""Measures stateful Qwen3-ASR streaming against a local mono 16 kHz WAV fixture."""

from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

import numpy
import soundfile
from qwen_asr import Qwen3ASRModel


GPU_MEMORY_UTILIZATION = 0.30
MAXIMUM_NEW_TOKENS = 256
MAXIMUM_MODEL_LENGTH = 6_000
MAXIMUM_CONCURRENT_SEQUENCES = 1
MAXIMUM_BATCHED_TOKENS = 1_024


def parse_arguments() -> argparse.Namespace:
    """Reads the pinned model and private local fixture paths supplied by the verification caller."""
    parser = argparse.ArgumentParser()
    parser.add_argument("model_directory", type=Path)
    parser.add_argument("wav_path", type=Path)
    return parser.parse_args()


def benchmark_streaming(asr: Qwen3ASRModel, samples: numpy.ndarray, sample_rate: int, chunk_seconds: float) -> dict[str, object]:
    """Runs one stateful session and separates work completed before release from the final tail flush."""
    state = asr.init_streaming_state(language="English", chunk_size_sec=chunk_seconds)
    chunk_sample_count = round(chunk_seconds * sample_rate)
    full_chunk_sample_count = len(samples) // chunk_sample_count * chunk_sample_count
    chunk_durations: list[float] = []

    for chunk_start in range(0, full_chunk_sample_count, chunk_sample_count):
        started = time.perf_counter()
        asr.streaming_transcribe(samples[chunk_start : chunk_start + chunk_sample_count], state)
        chunk_durations.append(time.perf_counter() - started)

    asr.streaming_transcribe(samples[full_chunk_sample_count:], state)
    release_started = time.perf_counter()
    asr.finish_streaming_transcribe(state)
    release_seconds = time.perf_counter() - release_started

    return {
        "chunk_seconds": chunk_seconds,
        "audio_seconds": len(samples) / sample_rate,
        "pre_release_compute_seconds": sum(chunk_durations),
        "max_chunk_compute_seconds": max(chunk_durations),
        "release_asr_seconds": release_seconds,
        "kept_up_with_realtime": max(chunk_durations) < chunk_seconds,
        "transcript_length": len(state.text),
        "transcript_sha256": hashlib.sha256(state.text.encode("utf-8")).hexdigest(),
    }


def main() -> int:
    """Loads once, warms the actual CUDA path, and compares the official one- and two-second streaming boundaries."""
    arguments = parse_arguments()
    samples, sample_rate = soundfile.read(arguments.wav_path, dtype="float32", always_2d=False)
    if samples.ndim != 1 or sample_rate != 16_000:
        raise ValueError("The streaming fixture must be mono 16 kHz audio.")

    loading_started = time.perf_counter()
    asr = Qwen3ASRModel.LLM(
        model=str(arguments.model_directory.resolve()),
        gpu_memory_utilization=GPU_MEMORY_UTILIZATION,
        max_model_len=MAXIMUM_MODEL_LENGTH,
        max_num_batched_tokens=MAXIMUM_BATCHED_TOKENS,
        max_num_seqs=MAXIMUM_CONCURRENT_SEQUENCES,
        max_new_tokens=MAXIMUM_NEW_TOKENS,
    )
    loading_seconds = time.perf_counter() - loading_started

    warm_up_state = asr.init_streaming_state(language="English", chunk_size_sec=1.0)
    asr.streaming_transcribe(numpy.zeros(16_000, dtype=numpy.float32), warm_up_state)
    asr.finish_streaming_transcribe(warm_up_state)

    report = {
        "loading_seconds": loading_seconds,
        "results": [
            benchmark_streaming(asr, samples, sample_rate, chunk_seconds=1.0),
            benchmark_streaming(asr, samples, sample_rate, chunk_seconds=2.0),
        ],
    }
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
