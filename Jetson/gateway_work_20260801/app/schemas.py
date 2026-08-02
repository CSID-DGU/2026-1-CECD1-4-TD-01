from __future__ import annotations

from uuid import uuid4

from pydantic import AliasChoices, BaseModel, Field, model_validator


class SessionResponse(BaseModel):
    session_id: str
    opening_question: str
    opening_source: str
    opening_topic: str


class ProactiveTestRequest(BaseModel):
    scenario: str = Field(min_length=1, max_length=80)
    session_id: str | None = Field(default=None, max_length=200)


class DatasetSelectionRequest(BaseModel):
    dataset: str = Field(
        min_length=1,
        max_length=80,
        pattern="^[a-z0-9_]+$",
        validation_alias=AliasChoices("dataset", "dataset_id"),
    )


class ChatRequest(BaseModel):
    session_id: str
    request_id: str | None = Field(default=None, max_length=200)
    text: str = Field(min_length=1, max_length=12000)
    system_prompt: str | None = Field(default=None, max_length=8000)
    source: str = Field(default="text", pattern="^(text|voice)$")
    activation: str | None = Field(
        default=None,
        pattern=(
            "^(wake_word|wake_followup|proactive|active_session|ui_action)$"
        ),
    )

    @model_validator(mode="after")
    def voice_requires_explicit_activation(self) -> ChatRequest:
        if self.source == "voice" and not self.activation:
            raise ValueError(
                "voice LLM requests require a wake word, proactive turn, "
                "active session, or explicit UI action"
            )
        return self


class TurnResponse(BaseModel):
    session_id: str
    transcript: str
    assistant: str
    stt_ms: int | None = None
    llm_ms: int


class VoiceStartRequest(BaseModel):
    session_id: str


class VoiceStartResponse(BaseModel):
    session_id: str
    recording: bool


class VoiceStopRequest(BaseModel):
    session_id: str


class TranscriptionResponse(BaseModel):
    session_id: str
    utterance_id: str
    transcript: str
    stt_ms: int
    voice_emotion: dict


class TtsRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
    speech_rate: float = Field(default=0.92, ge=0.70, le=1.20)


class ProactiveMessageRequest(BaseModel):
    event_id: str = Field(
        default_factory=lambda: uuid4().hex,
        min_length=1,
        max_length=200,
    )
    session_id: str | None = Field(default=None, max_length=200)
    text: str = Field(min_length=1, max_length=1000)
    source: str = Field(default="external_algorithm", min_length=1, max_length=100)
    topic: str = Field(default="GENERAL_CHECK_IN", min_length=1, max_length=100)
    priority: int = Field(default=50, ge=0, le=100)
    expires_in_seconds: int = Field(default=300, ge=10, le=86_400)
    greeting_pose: bool = True


class ProactiveMessageAck(BaseModel):
    session_id: str = Field(min_length=1, max_length=200)
