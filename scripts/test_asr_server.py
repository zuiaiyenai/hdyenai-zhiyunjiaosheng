import sys
import types
import unittest
from pathlib import Path


funasr = types.ModuleType("funasr")
funasr.AutoModel = object
sys.modules.setdefault("funasr", funasr)
sys.path.insert(0, str(Path(__file__).parent))

import asr_server


class BuildSegmentsTest(unittest.TestCase):
    def test_timestamp_capability_falls_back_for_current_model(self):
        class ModelWithoutTimestamps:
            def __init__(self):
                self.calls = []

            def generate(self, **kwargs):
                self.calls.append(kwargs)
                if kwargs.get("sentence_timestamp"):
                    raise KeyError("timestamp")
                return [{"text": "识别成功"}]

        fake_model = ModelWithoutTimestamps()
        original_model = asr_server.model
        original_timestamp_supported = asr_server.timestamp_supported
        self.addCleanup(
            setattr, asr_server, "timestamp_supported", original_timestamp_supported)
        asr_server.timestamp_supported = None
        self.addCleanup(setattr, asr_server, "model", original_model)
        asr_server.model = fake_model

        result = asr_server.generate_results(Path("audio.wav"))
        cached_result = asr_server.generate_results(Path("audio.wav"))

        self.assertEqual([{"text": "识别成功"}], result)
        self.assertEqual(result, cached_result)
        self.assertEqual(3, len(fake_model.calls))
        self.assertTrue(fake_model.calls[0]["sentence_timestamp"])
        self.assertFalse(fake_model.calls[1]["sentence_timestamp"])
        self.assertFalse(fake_model.calls[2]["sentence_timestamp"])

    def test_sentence_info_uses_millisecond_timestamps(self):
        result = {"sentence_info": [
            {"text": "你好。", "start": 250, "end": 1500},
            {"text": "欢迎使用。", "start": 1800, "end": 3250},
        ]}

        self.assertEqual([
            {"start": 0.25, "end": 1.5, "text": "你好。"},
            {"start": 1.8, "end": 3.25, "text": "欢迎使用。"},
        ], asr_server.build_segments(result))

    def test_stamp_sents_appends_punctuation_and_skips_invalid_items(self):
        result = {"stamp_sents": [
            {"text_seg": "第一句", "punc": "。", "start": 0, "end": 900},
            {"text_seg": "无效", "start": 1000, "end": 1000},
        ]}

        self.assertEqual([
            {"start": 0.0, "end": 0.9, "text": "第一句。"},
        ], asr_server.build_segments(result))


if __name__ == "__main__":
    unittest.main()
