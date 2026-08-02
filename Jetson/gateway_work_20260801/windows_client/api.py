from typing import Any

import httpx
from PySide6.QtCore import QThread, Signal


class ApiWorker(QThread):
    succeeded = Signal(str, object)
    failed = Signal(str, str)

    def __init__(
        self,
        operation: str,
        base_url: str,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        timeout: float = 120.0,
    ):
        super().__init__()
        self.operation = operation
        self.base_url = base_url.rstrip("/")
        self.method = method
        self.path = path
        self.payload = payload
        self.timeout = timeout

    def run(self) -> None:
        try:
            with httpx.Client(timeout=self.timeout) as client:
                response = client.request(
                    self.method,
                    f"{self.base_url}{self.path}",
                    json=self.payload,
                )
                response.raise_for_status()
                self.succeeded.emit(self.operation, response.json())
        except httpx.HTTPStatusError as exc:
            try:
                message = exc.response.json().get("detail", str(exc))
            except ValueError:
                message = str(exc)
            self.failed.emit(self.operation, message)
        except Exception as exc:
            self.failed.emit(self.operation, str(exc))

