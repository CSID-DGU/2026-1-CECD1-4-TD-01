#!/usr/bin/env python3
"""Read-only Windows monitor for Jetson-local OnMom conversations."""

from __future__ import annotations

import json
import os
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
import tkinter as tk
from tkinter import scrolledtext, ttk
from typing import Any


DEFAULT_URL = os.getenv("ONMOM_JETSON_URL", "http://100.93.236.68:8000")
POLL_MS = 750


def normalized_url(value: str) -> str:
    url = value.strip().rstrip("/")
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("http:// 또는 https:// Jetson 주소를 입력하세요.")
    return url


def fetch_snapshot(base_url: str, after_id: int) -> dict[str, Any]:
    query = urllib.parse.urlencode({"after_id": max(0, after_id), "limit": 100})
    request = urllib.request.Request(
        f"{normalized_url(base_url)}/monitor/conversation?{query}",
        headers={"Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=4) as response:
            value = json.loads(response.read().decode("utf-8"))
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise RuntimeError(str(exc)) from exc
    if not isinstance(value, dict) or value.get("observer_only") is not True:
        raise RuntimeError("관제 전용 API 응답이 아닙니다.")
    return value


def llm_label(status: dict[str, Any]) -> str:
    if int(status.get("inflight", 0)) > 0:
        return "생각 중"
    state = str(status.get("state", "unknown"))
    return {"sleeping": "수면", "ready": "대기", "waking": "깨우는 중"}.get(
        state, state
    )


def local_time(value: str) -> str:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone().strftime(
            "%H:%M:%S"
        )
    except ValueError:
        return "--:--:--"


class MonitorApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("OnMom Jetson 관제")
        self.root.geometry("860x620")
        self.url = tk.StringVar(value=DEFAULT_URL)
        self.connection = tk.StringVar(value="연결 대기")
        self.session = tk.StringVar(value="-")
        self.llm = tk.StringVar(value="-")
        self.current_session: str | None = None
        self.after_id = 0
        self.fetching = False
        self.closed = False
        self._build()
        self.root.protocol("WM_DELETE_WINDOW", self._close)
        self.root.after(50, self._poll)

    def _build(self) -> None:
        outer = ttk.Frame(self.root, padding=12)
        outer.pack(fill="both", expand=True)

        connection = ttk.Frame(outer)
        connection.pack(fill="x")
        ttk.Label(connection, text="Jetson").pack(side="left")
        ttk.Entry(connection, textvariable=self.url, width=42).pack(
            side="left", padx=8, fill="x", expand=True
        )
        ttk.Button(connection, text="다시 연결", command=self._reconnect).pack(
            side="left"
        )

        status = ttk.Frame(outer, padding=(0, 10))
        status.pack(fill="x")
        ttk.Label(status, textvariable=self.connection).pack(side="left")
        ttk.Label(status, text="  |  LLM: ").pack(side="left")
        ttk.Label(status, textvariable=self.llm).pack(side="left")
        ttk.Label(status, text="  |  세션: ").pack(side="left")
        ttk.Label(status, textvariable=self.session).pack(side="left")

        self.chat = scrolledtext.ScrolledText(
            outer,
            wrap="word",
            font=("Malgun Gothic", 11),
            state="disabled",
            padx=10,
            pady=10,
        )
        self.chat.pack(fill="both", expand=True)
        self.chat.tag_configure("user", foreground="#1457a6", spacing1=8)
        self.chat.tag_configure("assistant", foreground="#19703b", spacing1=8)
        self.chat.tag_configure("other", foreground="#666666", spacing1=8)

        ttk.Label(
            outer,
            text="조회 전용: 이 프로그램은 마이크·STT·LLM·IoT 명령을 호출하지 않습니다.",
        ).pack(anchor="w", pady=(8, 0))

    def _poll(self) -> None:
        if self.closed:
            return
        if not self.fetching:
            self.fetching = True
            threading.Thread(target=self._fetch, daemon=True).start()
        self.root.after(POLL_MS, self._poll)

    def _fetch(self) -> None:
        try:
            snapshot = fetch_snapshot(self.url.get(), self.after_id)
        except (RuntimeError, ValueError) as exc:
            self.root.after(0, self._show_error, str(exc))
            return
        self.root.after(0, self._apply, snapshot)

    def _apply(self, snapshot: dict[str, Any]) -> None:
        self.fetching = False
        self.connection.set("연결됨 · 관제 전용")
        session_id = snapshot.get("session_id")
        if isinstance(session_id, str) and session_id != self.current_session:
            self.current_session = session_id
            self.after_id = 0
            self._clear_chat()
        self.session.set(str(session_id or "-"))
        status = snapshot.get("llm")
        self.llm.set(llm_label(status if isinstance(status, dict) else {}))
        messages = snapshot.get("messages")
        if isinstance(messages, list):
            for message in messages:
                if not isinstance(message, dict):
                    continue
                message_id = int(message.get("id", 0))
                if message_id <= self.after_id:
                    continue
                self.after_id = message_id
                self._append_message(message)

    def _append_message(self, message: dict[str, Any]) -> None:
        role = str(message.get("role", "other"))
        name = {"user": "사용자", "assistant": "마음이"}.get(role, role)
        tag = role if role in {"user", "assistant"} else "other"
        timestamp = local_time(str(message.get("created_at", "")))
        content = str(message.get("content", "")).strip()
        self.chat.configure(state="normal")
        self.chat.insert("end", f"[{timestamp}] {name}\n{content}\n", tag)
        self.chat.configure(state="disabled")
        self.chat.see("end")

    def _show_error(self, message: str) -> None:
        self.fetching = False
        self.connection.set(f"연결 실패: {message}")
        self.llm.set("-")

    def _reconnect(self) -> None:
        self.current_session = None
        self.after_id = 0
        self.connection.set("다시 연결 중")
        self._clear_chat()

    def _clear_chat(self) -> None:
        self.chat.configure(state="normal")
        self.chat.delete("1.0", "end")
        self.chat.configure(state="disabled")

    def _close(self) -> None:
        self.closed = True
        self.root.destroy()


def self_test() -> None:
    assert normalized_url(" http://100.93.236.68:8000/ ") == "http://100.93.236.68:8000"
    assert llm_label({"state": "sleeping", "inflight": 0}) == "수면"
    assert llm_label({"state": "ready", "inflight": 1}) == "생각 중"
    print("jetson_monitor self-test: OK")


def main() -> None:
    if "--self-test" in sys.argv:
        self_test()
        return
    root = tk.Tk()
    MonitorApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
