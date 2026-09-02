import argparse
import os
import tempfile
from pathlib import Path

import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from funasr import AutoModel


app = FastAPI(title="Zhiyun Local ASR")
model = None
timestamp_supported = None


def load_model(model_root: Path) -> None:
    global model, timestamp_supported
    timestamp_supported = None
    model = AutoModel(
        model=str(model_root / "speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch"),
        vad_model=str(model_root / "speech_fsmn_vad_zh-cn-16k-common-pytorch"),
        punc_model=str(model_root / "punc_ct-transformer_zh-cn-common-vocab272727-pytorch"),
        disable_update=True,
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP" if model is not None else "STARTING"}


def build_segments(result: dict[str, object]) -> list[dict[str, object]]:
    raw_segments = result.get("sentence_info") or result.get("stamp_sents") or []
    segments = []
    for item in raw_segments if isinstance(raw_segments, list) else []:
        if not isinstance(item, dict):
            continue
        text = str(item.get("text") or item.get("text_seg") or "").strip()
        punctuation = str(item.get("punc") or "").strip()
        if punctuation and text and not text.endswith(punctuation):
            text += punctuation
        start = item.get("start")
        end = item.get("end")
        if (not text or not isinstance(start, (int, float))
                or not isinstance(end, (int, float)) or start < 0 or end <= start):
            continue
        segments.append({"start": start / 1000, "end": end / 1000, "text": text})
    return segments


def generate_results(audio_path: Path) -> list[dict[str, object]]:
    global timestamp_supported
    if timestamp_supported is False:
        return model.generate(
            input=str(audio_path), sentence_timestamp=False, batch_size_s=300)
    try:
        results = model.generate(
            input=str(audio_path),
            sentence_timestamp=True,
            batch_size_s=300,
        )
        timestamp_supported = True
        return results
    except KeyError as error:
        if error.args != ("timestamp",):
            raise
        timestamp_supported = False
        return model.generate(
            input=str(audio_path), sentence_timestamp=False, batch_size_s=300)


@app.post("/asr")
async def transcribe(file: UploadFile = File(...), language: str = Form("zh")) -> dict[str, object]:
    if language not in {"zh", "zh-CN", "zh_cn"}:
        raise HTTPException(status_code=400, detail="本地 ASR 当前仅支持中文")
    suffix = Path(file.filename or "audio.wav").suffix or ".wav"
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
            temp_path = Path(temp_file.name)
            temp_file.write(await file.read())
        results = generate_results(temp_path)
        result = results[0] if results else {}
        text = result.get("text", "").strip()
        if not text:
            raise HTTPException(status_code=422, detail="未识别到有效语音")
        return {"text": text, "segments": build_segments(result)}
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-root", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9977)
    args = parser.parse_args()
    os.environ.setdefault("MODELSCOPE_CACHE", str(Path(args.model_root).parent))
    load_model(Path(args.model_root))
    uvicorn.run(app, host=args.host, port=args.port)
