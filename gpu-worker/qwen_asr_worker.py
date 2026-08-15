"""Persistent WSL/CUDA Qwen3-ASR worker for stateful phone and desktop dictation."""

from __future__ import annotations

import ctypes
import gc
import hashlib
import json
import os
import struct
import sys
import time
from pathlib import Path

import numpy


PROTOCOL_MAGIC = 0x43445141
PROTOCOL_VERSION = 3
REQUEST_BEGIN = 1
REQUEST_AUDIO = 2
REQUEST_FINISH = 3
REQUEST_CANCEL = 4
REQUEST_SHUTDOWN = 5
REQUEST_WARM_UP = 6
RESPONSE_READY = 1
RESPONSE_SESSION_STARTED = 2
RESPONSE_AUDIO_ACCEPTED = 3
RESPONSE_TRANSCRIPT = 4
RESPONSE_CANCELLED = 5
RESPONSE_WARMED = 6
RESPONSE_ERROR = 7
MAXIMUM_REQUEST_BYTES = 100 * 1024 * 1024
CAPTURED_AUDIO_MAGIC = 0x43444155
CAPTURED_AUDIO_VERSION = 1
FLOAT32_SAMPLE_FORMAT = 1
SAMPLE_RATE_HERTZ = 16_000
STREAM_CHUNK_SECONDS = 2.0
GPU_MEMORY_UTILIZATION = 0.45
MAXIMUM_MODEL_LENGTH = 30_000
MAXIMUM_NEW_TOKENS = 256
MODEL_DIRECTORY = Path.home() / ".local/share/cleardictate/models/qwen3-asr-1.7b"
RUNTIME_DIRECTORY = Path(sys.prefix).resolve()
os.environ["VLLM_ENABLE_V1_MULTIPROCESSING"] = "0"


def read_exact(input_stream, byte_count: int) -> bytes:
    """Reads one complete private-pipe field and treats an early close as session failure."""
    payload = input_stream.read(byte_count)
    if len(payload) != byte_count:
        raise EOFError("Private request pipe closed.")
    return payload


def read_frame(input_stream) -> tuple[int, bytearray]:
    """Reads one bounded request frame from the Windows host."""
    magic, version, message_type, payload_length = struct.unpack(">IHBI", read_exact(input_stream, 11))
    if magic != PROTOCOL_MAGIC or version != PROTOCOL_VERSION:
        raise ValueError("Invalid private request frame.")
    if payload_length > MAXIMUM_REQUEST_BYTES:
        raise ValueError("Private request payload is too large.")
    return message_type, bytearray(read_exact(input_stream, payload_length))


def write_frame(output_stream, message_type: int, payload: bytes = b"") -> None:
    """Writes one response only to the duplicated protocol descriptor, never to library stdout."""
    output_stream.write(struct.pack(">IHBI", PROTOCOL_MAGIC, PROTOCOL_VERSION, message_type, len(payload)))
    output_stream.write(payload)
    output_stream.flush()


def decode_captured_audio(payload: bytearray) -> tuple[numpy.ndarray, int]:
    """Decodes one mono float32 audio fragment and copies it away from the scrubbed frame buffer."""
    if len(payload) < 16:
        raise ValueError("Captured audio is truncated.")
    magic, version, sample_format, sample_rate, sample_count = struct.unpack_from(">IHHII", payload)
    if magic != CAPTURED_AUDIO_MAGIC or version != CAPTURED_AUDIO_VERSION or sample_format != FLOAT32_SAMPLE_FORMAT:
        raise ValueError("Captured audio header is invalid.")
    if sample_rate != SAMPLE_RATE_HERTZ or len(payload) != 16 + sample_count * 4:
        raise ValueError("Captured audio dimensions are invalid.")
    return numpy.frombuffer(payload, dtype=">f4", offset=16, count=sample_count).astype(numpy.float32), sample_rate


def verify_model(model_directory: Path, lock_path: Path) -> None:
    """Rejects missing or changed model files before Qwen or vLLM parses them."""
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


def release_inactive_cpu_pages() -> None:
    """Returns allocator arenas that are no longer needed after loading or finishing a stream."""
    gc.collect()
    ctypes.CDLL("libc.so.6").malloc_trim(0)


def release_clean_runtime_file_cache() -> None:
    """Hints that clean model and dependency pages may be reclaimed after CUDA has loaded them."""
    for root in (MODEL_DIRECTORY, RUNTIME_DIRECTORY):
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            try:
                descriptor = os.open(path, os.O_RDONLY)
                try:
                    os.posix_fadvise(descriptor, 0, 0, os.POSIX_FADV_DONTNEED)
                finally:
                    os.close(descriptor)
            except OSError:
                continue


class QwenAsrEngine:
    """Owns the official vLLM streaming model and exactly one mutable utterance state."""

    def __init__(self, model_directory: Path) -> None:
        from qwen_asr import Qwen3ASRModel

        self.model = Qwen3ASRModel.LLM(
            model=str(model_directory),
            gpu_memory_utilization=GPU_MEMORY_UTILIZATION,
            max_model_len=MAXIMUM_MODEL_LENGTH,
            max_new_tokens=MAXIMUM_NEW_TOKENS,
        )
        self.state = None
        self.processing_nanoseconds = 0
        release_inactive_cpu_pages()

    def begin(self) -> None:
        """Creates one English streaming state and refuses overlapping sessions."""
        if self.state is not None:
            raise RuntimeError("An ASR stream is already active.")
        self.state = self.model.init_streaming_state(language="English", chunk_size_sec=STREAM_CHUNK_SECONDS)
        self.processing_nanoseconds = 0

    def accept(self, samples: numpy.ndarray) -> None:
        """Advances the model with audio already received, allowing inference to overlap speech."""
        if self.state is None:
            raise RuntimeError("No ASR stream is active.")
        started = time.perf_counter_ns()
        self.model.streaming_transcribe(samples, self.state)
        self.processing_nanoseconds += time.perf_counter_ns() - started

    def finish(self) -> tuple[str, int]:
        """Flushes only the remaining tail and returns cumulative model compute time."""
        if self.state is None:
            raise RuntimeError("No ASR stream is active.")
        state = self.state
        started = time.perf_counter_ns()
        self.model.finish_streaming_transcribe(state)
        self.processing_nanoseconds += time.perf_counter_ns() - started
        transcript = state.text
        processing_nanoseconds = self.processing_nanoseconds
        self._discard_state()
        return transcript, processing_nanoseconds

    def cancel(self) -> None:
        """Drops the active utterance without decoding, polishing, or retaining its buffered audio."""
        if self.state is None:
            raise RuntimeError("No ASR stream is active.")
        self._discard_state()

    def warm_up(self) -> None:
        """Runs the exact two-second streaming path used by a real utterance."""
        self.begin()
        samples = numpy.zeros(round(SAMPLE_RATE_HERTZ * STREAM_CHUNK_SECONDS), dtype=numpy.float32)
        try:
            self.accept(samples)
            self.finish()
        finally:
            samples.fill(0.0)
            if self.state is not None:
                self._discard_state()
            release_clean_runtime_file_cache()

    def _discard_state(self) -> None:
        """Scrubs the Qwen package's documented PCM buffers before releasing the state."""
        state = self.state
        if state is not None:
            state.buffer.fill(0.0)
            state.audio_accum.fill(0.0)
        self.state = None
        self.processing_nanoseconds = 0
        release_inactive_cpu_pages()


def run_worker(lock_path: Path, protocol_output) -> int:
    """Loads the pinned model once, then serves one stateful stream at a time."""
    verify_model(MODEL_DIRECTORY, lock_path)
    engine = QwenAsrEngine(MODEL_DIRECTORY)
    input_stream = sys.stdin.buffer
    write_frame(protocol_output, RESPONSE_READY)

    while True:
        message_type, payload = read_frame(input_stream)
        samples = None
        try:
            if message_type == REQUEST_SHUTDOWN:
                if engine.state is not None:
                    engine.cancel()
                return 0
            if message_type == REQUEST_WARM_UP:
                engine.warm_up()
                write_frame(protocol_output, RESPONSE_WARMED)
            elif message_type == REQUEST_BEGIN:
                engine.begin()
                write_frame(protocol_output, RESPONSE_SESSION_STARTED)
            elif message_type == REQUEST_AUDIO:
                samples, _ = decode_captured_audio(payload)
                engine.accept(samples)
                write_frame(protocol_output, RESPONSE_AUDIO_ACCEPTED)
            elif message_type == REQUEST_FINISH:
                transcript, processing_nanoseconds = engine.finish()
                write_frame(protocol_output, RESPONSE_TRANSCRIPT, struct.pack(">Q", processing_nanoseconds) + transcript.encode("utf-8"))
            elif message_type == REQUEST_CANCEL:
                engine.cancel()
                write_frame(protocol_output, RESPONSE_CANCELLED)
            else:
                raise ValueError("Unknown private request type.")
        except Exception:
            if engine.state is not None:
                engine.cancel()
            write_frame(protocol_output, RESPONSE_ERROR)
        finally:
            if samples is not None:
                samples.fill(0.0)
            payload[:] = b"\x00" * len(payload)


def main() -> int:
    """Protects the binary protocol from vLLM logging before importing or loading the model."""
    if len(sys.argv) != 2:
        return 2
    protocol_descriptor = os.dup(sys.stdout.fileno())
    protocol_output = os.fdopen(protocol_descriptor, "wb", buffering=0)
    os.dup2(sys.stderr.fileno(), sys.stdout.fileno())
    with protocol_output:
        return run_worker(Path(sys.argv[1]).resolve(), protocol_output)


if __name__ == "__main__":
    raise SystemExit(main())
