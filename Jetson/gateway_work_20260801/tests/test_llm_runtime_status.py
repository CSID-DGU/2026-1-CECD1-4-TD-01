from __future__ import annotations

import asyncio
import time
from types import SimpleNamespace

import pytest

from app.llm import LlamaClient


def make_client() -> LlamaClient:
    settings = SimpleNamespace(
        llama_sleep_idle_seconds=60,
        llama_url="http://llama:8080",
    )
    store = SimpleNamespace()
    return LlamaClient(settings, store)


def test_runtime_status_tracks_sleep_wake_and_active_states() -> None:
    client = make_client()
    client._last_inference_finished = time.monotonic() - 61

    assert client.runtime_status()["state"] == "sleeping"

    waking = client._begin_inference()
    assert waking is True
    assert client.runtime_status()["state"] == "waking"

    client._finish_inference(waking)
    assert client.runtime_status()["state"] == "awake"

    waking = client._begin_inference()
    assert waking is False
    assert client.runtime_status()["state"] == "active"

    client._finish_inference(waking)
    assert client.runtime_status()["inflight"] == 0


def test_forced_sleep_is_cleared_only_when_inference_begins() -> None:
    client = make_client()
    client._last_inference_finished = time.monotonic()
    client._force_sleep_requested = True

    assert client.runtime_status()["state"] == "sleeping"

    waking = client._begin_inference()
    assert waking is True
    assert client._force_sleep_requested is False
    assert client.runtime_status()["state"] == "waking"


@pytest.mark.parametrize(
    ("payload", "expected"),
    [
        ({"data": []}, None),
        (
            {
                "data": [
                    {"id": "other", "status": {"value": "loaded"}},
                    {
                        "id": "onmom-gemma",
                        "status": {"value": "unloaded"},
                    },
                ]
            },
            "unloaded",
        ),
    ],
)
def test_model_state_from_router_payload(payload: dict, expected: str | None) -> None:
    assert LlamaClient._model_state_from_payload(payload) == expected


def test_manual_sleep_defers_proactive_generation() -> None:
    client = make_client()
    client._force_sleep_requested = True

    with pytest.raises(RuntimeError, match="manually asleep"):
        asyncio.run(client.generate_proactive_opening("system", "instruction"))
