import asyncio
import logging
import json
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response, StreamingResponse

from .action_tools import ActionExecutor, detect_user_action, extract_action
from .audio import (
    AudioRecorder,
    AutomaticVoiceDetector,
    WhisperTranscriber,
    list_audio_devices,
)
from .config import settings
from .database import ConversationStore
from .health_proactive import HealthProactiveService
from .llm import LlamaClient
from .proactive_questions import FALLBACK_QUESTION, OpeningQuestion
from .proactive_inbox import ProactiveMessageInbox
from .schemas import (
    ChatRequest,
    DatasetSelectionRequest,
    ProactiveMessageAck,
    ProactiveMessageRequest,
    ProactiveTestRequest,
    SessionResponse,
    TranscriptionResponse,
    TtsRequest,
    TurnResponse,
    VoiceStartRequest,
    VoiceStartResponse,
    VoiceStopRequest,
)
from .tts import OnMomTts
from .vision_status import read_vision_status, write_session_marker
from .voice_emotion import (
    CounselingSignalsStore,
    VoiceEmotionAnalyzer,
    VoiceTurnProcessor,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("onmom.gateway")

store = ConversationStore(settings.database_path)
recorder = AudioRecorder(settings)
transcriber = WhisperTranscriber(settings)
voice_emotion = VoiceEmotionAnalyzer(settings)
signals = CounselingSignalsStore(
    settings.counseling_signals_log,
    settings.counseling_signals_latest,
    vision_reader=read_vision_status,
    prompt_min_confidence=settings.voice_emotion_prompt_min_confidence,
    arousal_settings=settings,
)
voice_turns = VoiceTurnProcessor(settings, transcriber, voice_emotion, signals)
automatic_voice = AutomaticVoiceDetector(settings, voice_turns)
llm = LlamaClient(settings, store)
tts = OnMomTts(settings)
actions = ActionExecutor(settings)


async def _handle_counseling_signal(event: dict) -> None:
    assessment = event.get("arousal_assessment")
    if not getattr(settings, "automatic_guardian_alert_enabled", True):
        return
    if not isinstance(assessment, dict):
        return
    if not assessment.get("automatic_guardian_alert"):
        return
    result = await actions.execute_automatic("SEND_HELP_ALERT")
    logger.warning(
        "automatic_guardian_alert session=%s result=%s",
        event.get("session_id"),
        result,
    )


voice_turns.signal_handler = _handle_counseling_signal
proactive_inbox = ProactiveMessageInbox(settings.proactive_messages_log)
health_proactive = HealthProactiveService(
    settings,
    llm,
    store,
    proactive_inbox,
    actions,
)


@asynccontextmanager
async def lifespan(_: FastAPI):
    store.initialize()
    health_proactive.initialize()
    latest_session_id = store.latest_session_id()
    if latest_session_id:
        proactive_inbox.set_active_session(latest_session_id)
    settings.recordings_dir.mkdir(parents=True, exist_ok=True)
    try:
        settings.speech_priority_marker.unlink(missing_ok=True)
    except OSError:
        logger.exception("stale_speech_priority_marker_cleanup_failed")
    try:
        await tts.warmup()
    except Exception:
        logger.exception("tts_warmup_failed piper_fallback_remains_available")
    await health_proactive.start()
    yield
    await health_proactive.stop()
    voice_emotion.close()


app = FastAPI(title="OnMom Home Jetson Gateway", version="0.2.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


@app.get("/health")
async def health() -> dict:
    llm_ready = await llm.health()
    vision = read_vision_status()
    return {
        "status": "ready" if llm_ready else "degraded",
        "llm": llm_ready,
        "llm_runtime": llm.runtime_status(),
        "microphone_device": settings.alsa_device,
        "whisper_model": settings.whisper_model.exists(),
        "silero_vad": settings.silero_vad_model.exists(),
        "tts": tts.available,
        "tts_backend": tts.backend,
        "tts_model": tts.model_name,
        "tts_voice": settings.supertonic_voice,
        "tts_quality_steps": settings.supertonic_steps,
        "tts_threads": settings.supertonic_threads,
        "tts_sample_rate": 44_100,
        "stt_model": settings.whisper_model.name,
        "stt_fallback_model": (
            settings.whisper_fallback_model.name
            if settings.whisper_fallback_model.exists()
            else None
        ),
        "stt_last_model": transcriber.last_model,
        "stt_fallback_count": transcriber.fallback_count,
        "speech_priority": {
            "enabled": True,
            "active": settings.speech_priority_marker.exists(),
            "settle_ms": settings.speech_priority_settle_ms,
        },
        "voice_emotion": voice_emotion.health(),
        "counseling_vision": {
            "available": vision["available"],
            "stale": vision["stale"],
            "reason": vision["reason"],
        },
        "proactive_question": {
            "available": health_proactive.status()["latest"] is not None,
            "source": "health_monitor",
        },
        "health_proactive": health_proactive.status(),
    }


@app.get("/vision/latest")
async def vision_latest() -> dict:
    """Return derived face/emotion/rPPG values; never return frames or crops."""

    return read_vision_status()


@app.get("/counseling/signals/latest")
async def counseling_signals_latest(session_id: str | None = None) -> dict:
    event = signals.latest(session_id)
    return {
        "available": event is not None,
        "session_id": session_id,
        "event": event,
        "raw_media_stored": False,
    }


@app.get("/counseling/signals/timeline")
async def counseling_signals_timeline(
    session_id: str,
    limit: int = 200,
) -> dict:
    events = signals.timeline(session_id, limit)
    return {
        "session_id": session_id,
        "count": len(events),
        "events": events,
        "raw_media_stored": False,
    }


def _turn_system_prompt(request: ChatRequest) -> str | None:
    parts = []
    daily_policy = health_proactive.daily_system_prompt()
    if daily_policy:
        parts.append(daily_policy)
    if request.system_prompt and request.system_prompt.strip():
        parts.append(request.system_prompt.strip())
    health_context = store.active_session_context(request.session_id)
    if health_context:
        parts.append(health_context)
    signal_context = signals.voice_prompt_context(request.session_id, request.request_id)
    if signal_context:
        parts.append(signal_context)
    return "\n\n".join(parts) if parts else None


@app.post("/sessions", response_model=SessionResponse)
async def create_session() -> SessionResponse:
    session_id = str(uuid.uuid4())
    store.create_session(session_id)
    proactive_inbox.set_active_session(session_id)
    try:
        opening = await health_proactive.opening_for_session(session_id)
    except Exception:
        logger.exception("health_proactive_session_opening_failed")
        opening = OpeningQuestion(
            FALLBACK_QUESTION,
            "fallback",
            "GENERAL_CHECK_IN",
        )
    store.add_message(
        session_id,
        "assistant",
        opening.text,
        f"proactive:{opening.source}:{opening.topic}",
    )
    try:
        write_session_marker(session_id)
    except OSError:
        logger.exception("counseling_session_marker_write_failed session=%s", session_id)
    return SessionResponse(
        session_id=session_id,
        opening_question=opening.text,
        opening_source=opening.source,
        opening_topic=opening.topic,
    )


@app.get("/health-monitor/status")
async def health_monitor_status() -> dict:
    return health_proactive.status()


@app.get("/health-monitor/datasets")
async def health_monitor_datasets() -> dict:
    return health_proactive.datasets()


@app.post("/health-monitor/datasets/select")
async def select_health_monitor_dataset(request: DatasetSelectionRequest) -> dict:
    try:
        return await health_proactive.select_dataset(request.dataset)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except (OSError, RuntimeError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/health-monitor/run")
async def run_health_monitor(deliver: bool = False) -> dict:
    try:
        return await health_proactive.run_once(deliver=deliver)
    except (OSError, RuntimeError, ValueError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.get("/health-monitor/policy")
async def health_monitor_policy() -> dict:
    policy = health_proactive.current_daily_policy()
    if policy is None:
        try:
            policy = await health_proactive.refresh_daily_policy()
        except (OSError, RuntimeError, ValueError) as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {
        "available": policy is not None,
        "policy": policy,
        "test_scenarios": health_proactive.test_scenarios(),
    }


@app.post("/health-monitor/policy/refresh")
async def refresh_health_monitor_policy() -> dict:
    try:
        policy = await health_proactive.refresh_daily_policy()
    except (OSError, RuntimeError, ValueError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {"available": True, "policy": policy}


@app.get("/health-monitor/test-scenarios")
async def health_monitor_test_scenarios() -> dict:
    return {"scenarios": health_proactive.test_scenarios()}


@app.post("/health-monitor/test")
async def test_health_monitor(request: ProactiveTestRequest) -> dict:
    try:
        return await health_proactive.test_scenario(
            request.scenario,
            request.session_id,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except (OSError, RuntimeError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.get("/llm/status")
async def llm_status() -> dict:
    return {
        "available": await llm.health(),
        **llm.runtime_status(),
    }


@app.get("/monitor/conversation")
async def monitor_conversation(after_id: int = 0, limit: int = 100) -> dict:
    """Read-only state for observers that must never compete for the microphone."""

    session_id = proactive_inbox.active_session_id or store.latest_session_id()
    safe_after_id = max(0, after_id)
    safe_limit = max(1, min(limit, 200))
    messages = (
        store.message_events(session_id, safe_after_id, safe_limit)
        if session_id
        else []
    )
    # ponytail: polling is enough for one monitor; switch to SSE only for many viewers.
    return {
        "available": session_id is not None,
        "observer_only": True,
        "session_id": session_id,
        "messages": messages,
        "llm": llm.runtime_status(),
    }


@app.post("/llm/wake")
async def wake_llm() -> dict[str, int | bool]:
    try:
        wake_ms = await llm.wake()
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {"ready": True, "wake_ms": wake_ms}


@app.post("/llm/sleep")
async def sleep_llm() -> dict[str, int | bool | str]:
    try:
        sleep_ms = await llm.sleep()
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {"sleeping": True, "state": "sleeping", "sleep_ms": sleep_ms}


@app.post("/proactive/messages")
async def submit_proactive_message(
    request: ProactiveMessageRequest,
) -> dict:
    try:
        event, created = proactive_inbox.submit(request)
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return {
        "accepted": True,
        "created": created,
        "event": event,
    }


@app.get("/proactive/messages/next")
async def next_proactive_message(
    session_id: str,
    after_event_id: str | None = None,
) -> dict:
    event = proactive_inbox.next_event(session_id, after_event_id)
    return {
        "available": event is not None,
        "event": event,
    }


@app.post("/proactive/messages/{event_id}/ack")
async def acknowledge_proactive_message(
    event_id: str,
    request: ProactiveMessageAck,
) -> dict:
    event, first_ack = proactive_inbox.acknowledge(
        request.session_id,
        event_id,
    )
    if event is None:
        raise HTTPException(status_code=404, detail="능동 메시지를 찾지 못했습니다.")
    if first_ack:
        store.add_message(
            request.session_id,
            "assistant",
            str(event["text"]),
            f"proactive:{event['source']}:{event['topic']}",
        )
    return {
        "acknowledged": True,
        "first_ack": first_ack,
        "event_id": event_id,
    }


@app.post("/chat", response_model=TurnResponse)
async def chat(request: ChatRequest) -> TurnResponse:
    try:
        proactive_inbox.set_active_session(request.session_id)
        direct_action = detect_user_action(request.text)
        if direct_action:
            answer = await actions.execute(direct_action, request.text.strip())
            store.add_message(request.session_id, "user", request.text.strip(), "text")
            store.add_message(request.session_id, "assistant", answer, "action")
            return TurnResponse(
                session_id=request.session_id,
                transcript=request.text.strip(),
                assistant=answer,
                llm_ms=0,
            )
        answer, llm_ms = await llm.complete(
            request.session_id,
            request.text.strip(),
            "text",
            _turn_system_prompt(request),
        )
        answer, action = extract_action(answer)
        if action:
            answer = f"{answer} {await actions.execute(action, request.text.strip())}".strip()
        return TurnResponse(
            session_id=request.session_id,
            transcript=request.text.strip(),
            assistant=answer,

            llm_ms=llm_ms,
        )
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/chat/stream")
async def chat_stream(request: ChatRequest) -> StreamingResponse:
    proactive_inbox.set_active_session(request.session_id)

    direct_action = detect_user_action(request.text)
    if direct_action:
        async def direct_action_stream():
            answer = await actions.execute(direct_action, request.text.strip())
            store.add_message(request.session_id, "user", request.text.strip(), request.source)
            store.add_message(request.session_id, "assistant", answer, "action")
            yield "event: meta\ndata: {\"profile\":\"action\"}\n\n"
            data = json.dumps({"text": answer}, ensure_ascii=False)
            yield f"event: token\ndata: {data}\n\n"
            done = json.dumps(
                {"text": answer, "llm_ms": 0, "profile": "action"},
                ensure_ascii=False,
            )
            yield f"event: done\ndata: {done}\n\n"

        return StreamingResponse(
            direct_action_stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    async def event_stream():
        try:
            async for event, payload in llm.stream_complete(
                request.session_id,
                request.text.strip(),
                request.source,
                _turn_system_prompt(request),
                request.request_id,
            ):
                data = json.dumps(payload, ensure_ascii=False)
                yield f"event: {event}\ndata: {data}\n\n"
        except RuntimeError as exc:
            data = json.dumps({"message": str(exc)}, ensure_ascii=False)
            yield f"event: error\ndata: {data}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.get("/audio/devices")
async def audio_devices() -> dict[str, str]:
    return {"selected": settings.alsa_device, "arecord": await list_audio_devices()}


@app.get("/voice/auto/events")
async def automatic_voice_events(
    request: Request,
    session_id: str,
    mode: str = "normal",
) -> StreamingResponse:
    store.create_session(session_id)
    proactive_inbox.set_active_session(session_id)
    barge_in = mode == "barge_in"

    async def event_stream():
        events: asyncio.Queue[tuple[str, dict] | None] = asyncio.Queue()

        async def produce_events() -> None:
            try:
                async for event, payload in automatic_voice.events(
                    session_id,
                    barge_in=barge_in,
                ):
                    await events.put((event, payload))
            except RuntimeError as exc:
                await events.put(("error", {"message": str(exc)}))
            finally:
                events.put_nowait(None)

        producer = asyncio.create_task(produce_events())
        try:
            while True:
                if await request.is_disconnected():
                    logger.info(
                        "automatic_voice_client_disconnected session=%s mode=%s",
                        session_id,
                        mode,
                    )
                    break
                try:
                    item = await asyncio.wait_for(events.get(), timeout=0.25)
                except asyncio.TimeoutError:
                    continue
                if item is None:
                    break
                event, payload = item
                data = json.dumps(payload, ensure_ascii=False)
                yield f"event: {event}\ndata: {data}\n\n"
        finally:
            if not producer.done():
                producer.cancel()
            await asyncio.gather(producer, return_exceptions=True)

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.post("/voice/start", response_model=VoiceStartResponse)
async def voice_start(request: VoiceStartRequest) -> VoiceStartResponse:
    try:
        store.create_session(request.session_id)
        proactive_inbox.set_active_session(request.session_id)
        await recorder.start(request.session_id)
        return VoiceStartResponse(session_id=request.session_id, recording=True)
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@app.post("/voice/stop", response_model=TranscriptionResponse)
async def voice_stop(request: VoiceStopRequest) -> TranscriptionResponse:
    try:
        path = await recorder.stop(request.session_id)
        transcript, stt_ms, emotion, utterance_id = await voice_turns.process(
            request.session_id,
            path,
        )
        logger.info("voice_transcribed session=%s stt_ms=%d", request.session_id, stt_ms)
        return TranscriptionResponse(
            session_id=request.session_id,
            utterance_id=utterance_id,
            transcript=transcript,
            stt_ms=stt_ms,
            voice_emotion=emotion,
        )
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/tts")
async def synthesize_tts(request: TtsRequest) -> Response:
    try:
        audio = await tts.synthesize(
            request.text.strip(),
            speech_rate=request.speech_rate,
        )
        return Response(content=audio, media_type="audio/wav")
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
