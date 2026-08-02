from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ONMOM_")

    llama_url: str = "http://127.0.0.1:8080"
    whisper_model: Path = Path("/models/whisper/ggml-base.bin")
    whisper_fallback_model: Path = Path(
        "/models/whisper/ggml-small-q5_1.bin"
    )
    whisper_binary: str = "whisper-cli"
    whisper_threads: int = 6
    whisper_prompt: str = (
        "다음은 한국어 일상 및 상담 대화입니다. "
        "자연스러운 한국어 문장으로 정확하게 받아씁니다."
    )
    piper_model: Path = Path("/models/piper/ko_KR-kss-medium.onnx")
    tts_backend: str = "supertonic_int8"
    supertonic_voice: str = "F5"
    supertonic_steps: int = 5
    supertonic_threads: int = 4
    supertonic_int8_model_dir: Path = Path(
        "/models/supertonic_int8"
    )
    silero_vad_model: Path = Path("/models/vad/silero_vad.onnx")
    alsa_device: str = "default"
    max_recording_seconds: int = 30
    min_audio_rms: int = 500
    vad_speech_rms: int = 700
    vad_confidence: float = 0.65
    vad_silence_ms: int = 900
    vad_min_speech_ms: int = 350
    max_tokens: int = 1024
    min_response_chars: int = 60
    llama_sleep_idle_seconds: int = 60
    keep_recordings: bool = False
    database_path: Path = Path("/data/onmom.db")
    recordings_dir: Path = Path("/data/recordings")
    speech_priority_marker: Path = Path("/data/speech_priority.lock")
    speech_priority_settle_ms: int = 800
    voice_emotion_enabled: bool = False
    voice_emotion_model: Path = Path(
        "/models/voice_emotion/voice_emotion_korean_int8.onnx"
    )
    voice_emotion_model_name: str = "korean-wav2vec2-emotion-int8"
    voice_emotion_model_source: str = (
        "local/korean-wav2vec2-emotion"
    )
    voice_emotion_model_revision: str = (
        "onnx-int8-20260731"
    )
    voice_emotion_max_seconds: int = 12
    voice_emotion_threads: int = 2
    voice_emotion_min_confidence: float = 0.35
    voice_emotion_prompt_min_confidence: float = 0.50
    voice_emotion_keep_loaded: bool = False
    voice_emotion_normalize_input: bool = True
    counseling_signals_log: Path = Path("/data/counseling_signals.jsonl")
    counseling_signals_latest: Path = Path(
        "/data/counseling_signals_latest.json"
    )
    derived_insights_path: Path = Path(
        "/insights/latest_derived_insights.json"
    )
    insights_database_path: Path = Path(
        "/onmom-data/insights/onmom_context.db"
    )
    raw_exports_path: Path = Path("/raw-exports")
    proactive_question_max_age_hours: float = 168.0
    proactive_messages_log: Path = Path("/data/proactive_messages.jsonl")
    health_monitor_enabled: bool = True
    health_monitor_database_path: Path = Path(
        "/data/health-proactive.sqlite3"
    )
    health_scenario_dataset_root: Path = Path(
        "/onmom-data/insights/scenarios"
    )
    room_database_path: Path = Path("/room/room-analytics.sqlite3")
    home_assistant_database_path: Path = Path(
        "/home-assistant/onmom-home-assistant-history.sqlite3"
    )
    calendar_events_path: Path = Path("/calendar/events.json")
    health_monitor_timezone: str = "Asia/Seoul"
    health_monitor_interval_seconds: int = 60
    health_monitor_startup_delay_seconds: int = 10
    proactive_max_nonurgent_per_day: int = 4
    health_analysis_window_days: int = 21
    action_gateway_url: str = "http://127.0.0.1:8765"
    action_token_file: Path = Path("/home/iot/.config/onmom/jetson_sync_token")
    bedroom_light_device_id: str = "light_cimsil_esp12n_light_light"
    speaker_bridge_url: str = "http://172.18.0.1:8001"
    sleep_audio_seconds: int = 600
    automatic_guardian_alert_enabled: bool = True
    automatic_guardian_alert_cooldown_seconds: int = 600
    automatic_morning_light_enabled: bool = True
    arousal_face_min_confidence: float = 0.60
    arousal_face_threshold: float = 0.72
    arousal_rppg_min_quality: float = 0.60
    arousal_rppg_bpm: float = 100.0
    arousal_rppg_critical_bpm: float = 125.0
    arousal_voice_min_confidence: float = 0.60



settings = Settings()
