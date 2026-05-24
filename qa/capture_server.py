from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import sys


class CaptureHandler(BaseHTTPRequestHandler):
    output_path: Path

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        captured = [
            f"{self.command} {self.path} {self.request_version}",
            *[f"{key}: {value}" for key, value in self.headers.items()],
            "",
            body.decode("utf-8", errors="replace"),
        ]
        self.output_path.write_text("\n".join(captured), encoding="utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"OK")

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    CaptureHandler.output_path = Path(sys.argv[1])
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
    server = HTTPServer(("0.0.0.0", port), CaptureHandler)
    server.handle_request()
