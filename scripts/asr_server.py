import argparse
import os
import tempfile
from pathlib import Path

import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from funasr import AutoModel


app = FastAPI(title="Zhiyun Local ASR")
model = None


def load_model(model_root: Path) -> None:
    global model
    model = AutoModel(
        model=str(model_root / "speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch"),
        vad_model=str(model_root / "speech_fsmn_vad_zh-cn-16k-common-pytorch"),
        punc_model=str(model_root / "punc_ct-transformer_zh-cn-common-vocab272727-pytorch"),
        disable_update=True,
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP" if model is not None else "STARTING"}


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
        results = model.generate(input=str(temp_path))
        text = results[0].get("text", "").strip() if results else ""
        if not text:
            raise HTTPException(status_code=422, detail="未识别到有效语音")
        return {"text": text, "segments": []}
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
