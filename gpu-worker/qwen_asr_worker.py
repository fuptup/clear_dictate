"""Persistent private-pipe Qwen3-ASR worker for release-triggered local dictation."""

from __future__ import annotations

import struct
import sys
import hashlib
import json
from pathlib import Path

import numpy
import torch
from transformers import AutoModelForMultimodalLM, AutoProcessor


PROTOCOL_MAGIC = 0x43445141
PROTOCOL_VERSION = 1
REQUEST_TRANSCRIBE = 1
REQUEST_SHUTDOWN = 2
RESPONSE_READY = 1
RESPONSE_TRANSCRIPT = 2
RESPONSE_ERROR = 3
MAXIMUM_REQUEST_BYTES = 100 * 1024 * 1024
CAPTURED_AUDIO_MAGIC = 0x43444155
CAPTURED_AUDIO_VERSION = 1
FLOAT32_SAMPLE_FORMAT = 1


def read_exact(input_stream, byte_count: int) -> bytes:
    payload = input_stream.read(byte_count)
    if len(payload) != byte_count:
        raise EOFError("Private request pipe closed.")
    return payload


def read_frame(input_stream) -> tuple[int, bytearray]:
    magic, version, message_type, payload_length = struct.unpack(">IHBI", read_exact(input_stream, 11))
    if magic != PROTOCOL_MAGIC or version != PROTOCOL_VERSION:
        raise ValueError("Invalid private request frame.")
    if payload_length > MAXIMUM_REQUEST_BYTES:
        raise ValueError("Private request payload is too large.")
    return message_type, bytearray(read_exact(input_stream, payload_length))


def write_frame(output_stream, message_type: int, payload: bytes = b"") -> None:
    output_stream.write(struct.pack(">IHBI", PROTOCOL_MAGIC, PROTOCOL_VERSION, message_type, len(payload)))
    output_stream.write(payload)
    output_stream.flush()


def decode_captured_audio(payload: bytearray) -> tuple[numpy.ndarray, int]:
    if len(payload) < 16:
        raise ValueError("Captured audio is truncated.")
    magic, version, sample_format, sample_rate, sample_count = struct.unpack_from(">IHHII", payload)
    if magic != CAPTURED_AUDIO_MAGIC or version != CAPTURED_AUDIO_VERSION or sample_format != FLOAT32_SAMPLE_FORMAT:
        raise ValueError("Captured audio header is invalid.")
    if sample_rate <= 0 or len(payload) != 16 + sample_count * 4:
        raise ValueError("Captured audio dimensions are invalid.")
    samples = numpy.frombuffer(payload, dtype=">f4", offset=16, count=sample_count).astype(numpy.float32)
    return samples, sample_rate


class QwenAsrEngine:
    """Loads the pinned local model once and performs deterministic English transcription."""

    def __init__(self, model_directory: Path) -> None:
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA is unavailable.")
        self.processor = AutoProcessor.from_pretrained(model_directory, local_files_only=True)
        self.model = AutoModelForMultimodalLM.from_pretrained(
            model_directory,
            local_files_only=True,
            dtype=torch.bfloat16,
            device_map="cuda",
            attn_implementation="sdpa",
        ).eval()

    def transcribe(self, samples: numpy.ndarray, sample_rate: int) -> str:
        """Returns one final English transcript without sampling or partial output."""
        inputs = self.processor.apply_transcription_request(
            audio=samples,
            language="English",
            processor_kwargs={"audio_kwargs": {"sampling_rate": sample_rate}},
        ).to(self.model.device, self.model.dtype)
        with torch.inference_mode():
            output_ids = self.model.generate(**inputs, max_new_tokens=256, do_sample=False)
        generated_ids = output_ids[:, inputs["input_ids"].shape[1]:]
        return self.processor.decode(generated_ids, return_format="transcription_only")[0]


def verify_model(model_directory: Path, lock_path: Path) -> None:
    """Rejects missing or changed model files before Transformers parses them."""
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    for relative_path, expectation in lock["files"].items():
        model_file = model_directory / relative_path
        if not model_file.is_file() or model_file.stat().st_size != expectation["size"]:
            raise RuntimeError("Pinned Qwen3-ASR model file is missing or has the wrong size.")
        digest = hashlib.sha256()
        with model_file.open("rb") as input_file:
            while chunk := input_file.read(8 * 1024 * 1024):
                digest.update(chunk)
        if digest.hexdigest() != expectation["sha256"]:
            raise RuntimeError("Pinned Qwen3-ASR model file failed SHA-256 verification.")


def run_worker(model_directory: Path, lock_path: Path) -> int:
    """Loads the model once, then serves serialized private-pipe requests until shutdown."""
    verify_model(model_directory, lock_path)
    engine = QwenAsrEngine(model_directory)
    input_stream = sys.stdin.buffer
    output_stream = sys.stdout.buffer
    write_frame(output_stream, RESPONSE_READY)

    while True:
        message_type, payload = read_frame(input_stream)
        try:
            if message_type == REQUEST_SHUTDOWN:
                return 0
            if message_type != REQUEST_TRANSCRIBE:
                raise ValueError("Unknown private request type.")
            samples, sample_rate = decode_captured_audio(payload)
            transcript = engine.transcribe(samples, sample_rate)
            write_frame(output_stream, RESPONSE_TRANSCRIPT, transcript.encode("utf-8"))
        except Exception:
            write_frame(output_stream, RESPONSE_ERROR)
        finally:
            payload[:] = b"\x00" * len(payload)


def main() -> int:
    if len(sys.argv) != 3:
        return 2
    return run_worker(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())


if __name__ == "__main__":
    raise SystemExit(main())
