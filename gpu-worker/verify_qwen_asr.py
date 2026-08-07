"""Runs the pinned Qwen3-ASR model against a deterministic mono PCM WAV fixture."""

from __future__ import annotations

import sys
import wave
from pathlib import Path

import numpy

from qwen_asr_worker import QwenAsrEngine, verify_model


def load_mono_pcm16(wave_path: Path) -> tuple[numpy.ndarray, int]:
    """Decodes the narrow fixture format used by the local integration check."""
    with wave.open(str(wave_path), "rb") as wave_file:
        if wave_file.getnchannels() != 1 or wave_file.getsampwidth() != 2:
            raise RuntimeError("The ASR fixture must be mono 16-bit PCM.")
        sample_rate = wave_file.getframerate()
        samples = numpy.frombuffer(wave_file.readframes(wave_file.getnframes()), dtype="<i2").astype(numpy.float32) / 32768.0
    return samples, sample_rate


def main() -> int:
    """Verifies model provenance, loads it on CUDA, and prints one transcript."""
    if len(sys.argv) != 4:
        return 2
    model_directory = Path(sys.argv[1]).resolve()
    model_lock = Path(sys.argv[2]).resolve()
    samples, sample_rate = load_mono_pcm16(Path(sys.argv[3]).resolve())
    verify_model(model_directory, model_lock)
    print(QwenAsrEngine(model_directory).transcribe(samples, sample_rate))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
