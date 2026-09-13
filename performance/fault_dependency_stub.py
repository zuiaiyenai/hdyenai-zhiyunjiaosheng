import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


WAV_HEADER = (
    b"RIFF\x24\x00\x00\x00WAVEfmt \x10\x00\x00\x00"
    b"\x01\x00\x01\x00\x40\x1f\x00\x00\x40\x1f\x00\x00"
    b"\x01\x00\x08\x00data\x00\x00\x00\x00"
)


class DependencyHandler(BaseHTTPRequestHandler):
    server_version = "FcttsFaultStub/1.0"

    def log_message(self, message, *args):
        print(f"{self.log_date_time_string()} {message % args}", flush=True)

    def do_HEAD(self):
        if self.server.mode == "tts" and self.path == "/tts":
            self.send_response(200)
            self.end_headers()
            return
        self.send_error(404)

    def do_GET(self):
        if self.server.mode == "tts" and self.path == "/tts":
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.server.mode == "asr" and self.path == "/health":
            self._json(200, {"status": "UP"})
            return
        self.send_error(404)

    def do_POST(self):
        expected = "/tts" if self.server.mode == "tts" else "/asr"
        if self.path != expected:
            self.send_error(404)
            return
        self._drain_request_body()
        self._delay_once()
        if self.server.mode == "tts":
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(WAV_HEADER)))
            self.end_headers()
            self.wfile.write(WAV_HEADER)
        else:
            self._json(200, {"text": "phase14 recovery", "segments": []})

    def _drain_request_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";", 1)[0], 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    return
                self.rfile.read(size)
                self.rfile.read(2)
        length = int(self.headers.get("Content-Length", "0"))
        if length:
            self.rfile.read(length)

    def _delay_once(self):
        delay_file = self.server.delay_file
        if delay_file is None or not delay_file.exists():
            return
        try:
            seconds = float(delay_file.read_text(encoding="utf-8").strip())
            delay_file.unlink(missing_ok=True)
            if self.server.active_file is not None:
                self.server.active_file.write_text("active", encoding="utf-8")
            time.sleep(seconds)
        finally:
            if self.server.active_file is not None:
                self.server.active_file.unlink(missing_ok=True)

    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser(description="Loopback dependency stub for Phase 14")
    parser.add_argument("--mode", choices=("tts", "asr"), required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--delay-file", type=Path)
    parser.add_argument("--active-file", type=Path)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), DependencyHandler)
    server.mode = args.mode
    server.delay_file = args.delay_file
    server.active_file = args.active_file
    print(f"READY mode={args.mode} port={args.port}", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
