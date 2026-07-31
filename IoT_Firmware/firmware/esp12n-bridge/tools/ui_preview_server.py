#!/usr/bin/env python3
"""Preview the ESP8266 configuration UI with mock device API responses."""

from __future__ import annotations

import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs


ROOT = Path(__file__).resolve().parents[1]
UI_HEADER = ROOT / "src" / "web_ui.h"


def load_ui() -> bytes:
    source = UI_HEADER.read_text(encoding="utf-8")
    match = re.search(r'R"HTML\((.*)\)HTML";', source, re.DOTALL)
    if not match:
        raise RuntimeError(f"HTML raw string not found in {UI_HEADER}")
    return match.group(1).encode("utf-8")


class PreviewHandler(BaseHTTPRequestHandler):
    ui = load_ui()
    config = {
        "ssid": "Galaxy Hotspot",
        "password_set": True,
        "jetson_host": "192.168.45.120",
        "jetson_port": 9000,
        "device_hostname": "iotcam-bridge",
    }

    def send_json(self, value: object, status: int = 200) -> None:
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path == "/preview/mobile":
            page = (
                b"<!doctype html><meta charset=utf-8><title>Mobile preview</title>"
                b"<style>html,body{margin:0;background:#555}iframe{display:block;"
                b"width:390px;height:844px;border:0}</style>"
                b"<iframe src=/></iframe>"
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)
        elif self.path in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(self.ui)))
            self.end_headers()
            self.wfile.write(self.ui)
        elif self.path == "/api/config":
            self.send_json(self.config)
        elif self.path == "/api/status":
            self.send_json({
                "wifi_connected": True,
                "tcp_connected": False,
                "wifi_ssid": self.config["ssid"],
                "ip": "192.168.45.37",
                "rssi_dbm": -51,
                "uart_bytes_received": 1823744,
                "wifi_bytes_forwarded": 1819051,
                "dropped_bytes": 4693,
                "ap_active": True,
                "ap_ssid": "IoTCam-Setup-B8B3A6",
                "free_heap": 32768,
            })
        elif self.path == "/api/scan":
            self.send_json({"networks": [
                {"ssid": "Galaxy Hotspot", "rssi": -43, "channel": 6, "secure": True},
                {"ssid": "Home-2.4G", "rssi": -66, "channel": 11, "secure": True},
                {"ssid": "OpenLab", "rssi": -79, "channel": 1, "secure": False},
            ]})
        else:
            self.send_json({"error": "not found"}, 404)

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        if self.path == "/api/save":
            form = parse_qs(body)
            self.config["ssid"] = form.get("ssid", [self.config["ssid"]])[0]
            self.config["jetson_host"] = form.get("jetson_host", [self.config["jetson_host"]])[0]
            self.config["jetson_port"] = int(form.get("jetson_port", [self.config["jetson_port"]])[0])
            self.send_json({"ok": True, "preview": True})
        elif self.path in ("/api/restart", "/api/reset"):
            self.send_json({"ok": True, "preview": True})
        else:
            self.send_json({"error": "not found"}, 404)

    def log_message(self, format: str, *args: object) -> None:
        return


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8765, type=int)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), PreviewHandler)
    print(f"UI preview: http://{args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
