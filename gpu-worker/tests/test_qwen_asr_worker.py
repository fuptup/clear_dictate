"""Regression tests for the persistent Qwen3-ASR worker's resource contract."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


WORKER_PATH = Path(__file__).resolve().parents[1] / "qwen_asr_worker.py"
WORKER_SPEC = importlib.util.spec_from_file_location("clear_dictate_qwen_asr_worker", WORKER_PATH)
assert WORKER_SPEC is not None and WORKER_SPEC.loader is not None
WORKER = importlib.util.module_from_spec(WORKER_SPEC)
WORKER_SPEC.loader.exec_module(WORKER)


class RecordingQwenAsrModel:
    """Captures the exact vLLM arguments supplied by the production engine constructor."""

    arguments: dict[str, object] | None = None

    @classmethod
    def LLM(cls, **arguments):
        """Records constructor arguments without loading a model or allocating CUDA memory."""
        cls.arguments = arguments
        return object()


class QwenAsrWorkerMemoryConfigurationTest(unittest.TestCase):
    """Locks the warm worker to the one-stream, 6,000-token memory requirement."""

    def test_engine_uses_bounded_single_stream_memory_configuration(self) -> None:
        """Prevents vLLM from reserving caches or compiled graphs beyond ClearDictate's one-stream contract."""
        RecordingQwenAsrModel.arguments = None
        fake_qwen_asr = SimpleNamespace(Qwen3ASRModel=RecordingQwenAsrModel)
        with patch.dict(sys.modules, {"qwen_asr": fake_qwen_asr}), patch.object(WORKER, "release_inactive_cpu_pages"):
            WORKER.QwenAsrEngine(Path("unused-model"))

        self.assertIsNotNone(RecordingQwenAsrModel.arguments)
        arguments = RecordingQwenAsrModel.arguments or {}
        self.assertNotIn("gpu_memory_utilization", arguments)
        self.assertEqual(688_128_000, arguments["kv_cache_memory_bytes"])
        self.assertEqual(0, arguments["mm_processor_cache_gb"])
        self.assertIs(True, arguments["enforce_eager"])
        self.assertEqual(6_000, arguments["max_model_len"])
        self.assertEqual(1, arguments["max_num_seqs"])


if __name__ == "__main__":
    unittest.main()
