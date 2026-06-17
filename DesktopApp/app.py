from __future__ import annotations

import importlib
import json
import math
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
import wave
from collections import deque
from dataclasses import dataclass
from importlib.util import find_spec
from pathlib import Path
from typing import Callable

import numpy as np
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

try:
    from PIL import Image, ImageOps, ImageTk
except Exception:  # pragma: no cover - shown in UI
    Image = None
    ImageOps = None
    ImageTk = None

try:
    import onnxruntime as ort
except Exception:  # pragma: no cover - shown in UI
    ort = None

APP_TITLE = "Counseling Desktop"
VOICE_SAMPLE_RATE = 16_000
VOICE_SECONDS = 12
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
MAX_GALLERY_IMAGES = 200


def configure_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass


configure_stdio()


def resource_path(*parts: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
    return base.joinpath(*parts)


def writable_app_dir() -> Path:
    candidates = [
        Path(os.environ["COUNSELING_DESKTOP_DATA_DIR"]).expanduser()
        if os.environ.get("COUNSELING_DESKTOP_DATA_DIR")
        else None,
        Path.cwd() / ".counseling_desktop",
        Path(os.environ.get("APPDATA", "")) / "CounselingDesktop" if os.environ.get("APPDATA") else None,
        Path(os.environ.get("LOCALAPPDATA", "")) / "CounselingDesktop" if os.environ.get("LOCALAPPDATA") else None,
    ]
    for root in candidates:
        if root is None:
            continue
        try:
            root.mkdir(parents=True, exist_ok=True)
            return root
        except OSError:
            continue
    raise RuntimeError("앱 데이터를 저장할 수 있는 위치를 찾지 못했습니다.")


@dataclass
class ChatMessage:
    role: str
    content: str


@dataclass
class VoiceResult:
    label: str
    confidence: float
    probabilities: dict[str, float]
    prompt_context: str


@dataclass
class GallerySummary:
    image_count: int
    dark_count: int
    bright_count: int
    warm_count: int
    cool_count: int
    average_brightness: float
    image_paths: list[Path]

    def to_context(self) -> str:
        if self.image_count == 0:
            return "선택된 이미지가 없습니다."
        return (
            f"선택 이미지 {self.image_count}장 기준 보조 요약입니다.\n"
            f"- 평균 밝기: {self.average_brightness:.1f}/255\n"
            f"- 어두운 이미지: {self.dark_count}장\n"
            f"- 밝은 이미지: {self.bright_count}장\n"
            f"- 따뜻한 색감 우세: {self.warm_count}장\n"
            f"- 차가운 색감 우세: {self.cool_count}장\n"
            "이 요약은 사진의 시각적 특징만 다루며, 사용자의 정신건강 상태를 단정하지 않습니다."
        )


@dataclass
class SavedSession:
    id: str
    title: str
    updated_at: int
    messages: list[ChatMessage]
    memories: list[str]


class VoiceEmotionAnalyzer:
    def __init__(self) -> None:
        self.model_path = resource_path("Analysis", "Voice", "converted", "voice_emotion_static.onnx")
        self.metadata_path = resource_path("Analysis", "Voice", "converted", "voice_emotion_metadata.json")
        self.labels = ["Happy", "Sad", "Angry", "Fearful", "Neutral"]
        self.session = None
        self.status = "음성 모델을 아직 로드하지 않았습니다."
        self._load_metadata()

    def _load_metadata(self) -> None:
        if self.metadata_path.exists():
            data = json.loads(self.metadata_path.read_text(encoding="utf-8"))
            self.labels = data.get("labels", self.labels)

    def available(self) -> bool:
        return ort is not None and self.model_path.exists()

    def load(self) -> str:
        if ort is None:
            self.status = "onnxruntime 패키지를 찾지 못했습니다."
            return self.status
        if not self.model_path.exists():
            self.status = f"음성 ONNX 모델이 없습니다: {self.model_path}"
            return self.status
        if self.session is None:
            self.session = ort.InferenceSession(str(self.model_path), providers=["CPUExecutionProvider"])
        self.status = f"음성 모델 로드 완료: {self.model_path.name}"
        return self.status

    def analyze(self, audio_path: Path) -> VoiceResult:
        if self.session is None:
            self.load()
        if self.session is None:
            raise RuntimeError(self.status)
        samples, sample_rate = read_wav_mono(audio_path)
        samples = convert_sample_rate(samples, sample_rate, VOICE_SAMPLE_RATE)
        target_len = VOICE_SAMPLE_RATE * VOICE_SECONDS
        if len(samples) < target_len:
            samples = np.pad(samples, (0, target_len - len(samples)))
        else:
            samples = samples[:target_len]

        inputs = {self.session.get_inputs()[0].name: samples.reshape(1, -1).astype(np.float32)}
        logits = self.session.run(None, inputs)[0][0]
        probs = softmax(logits)
        best_idx = int(np.argmax(probs))
        label = self.labels[best_idx] if best_idx < len(self.labels) else str(best_idx)
        probability_map = {
            self.labels[i] if i < len(self.labels) else str(i): float(probs[i])
            for i in range(len(probs))
        }
        context = build_voice_prompt_context(label, float(probs[best_idx]), probability_map)
        return VoiceResult(label, float(probs[best_idx]), probability_map, context)


class LlmAdapter:
    def __init__(self) -> None:
        config = load_llm_settings()
        default_model_path = resource_path("LLMmodels", "models", "gemma-4-E4B-it.litertlm")
        configured_model = os.environ.get("COUNSELING_LLM_MODEL") or str(config.get("model_path") or default_model_path)
        self.model_path = Path(configured_model).expanduser()
        self.backend_name = normalize_backend_name(
            os.environ.get("COUNSELING_LLM_BACKEND") or str(config.get("backend") or "cpu")
        )
        self.enable_speculative_decoding = parse_bool(
            os.environ.get("COUNSELING_LLM_SPECULATIVE", str(config.get("enable_speculative_decoding", False)))
        )
        self.external_command = os.environ.get("COUNSELING_LLM_COMMAND", str(config.get("external_command") or "")).strip()
        self.model_info = inspect_litertlm_header(self.model_path)
        self._lock = threading.Lock()
        self._engine = None
        self._engine_context = None
        self._litert_lm = import_litert_lm()
        self.runtime_diagnostics = inspect_llm_runtime()
        self.status = self._detect_status()

    def configure(
        self,
        model_path: Path,
        backend_name: str,
        enable_speculative_decoding: bool,
        external_command: str = "",
    ) -> str:
        self.close()
        self.model_path = model_path.expanduser()
        self.backend_name = normalize_backend_name(backend_name)
        self.enable_speculative_decoding = bool(enable_speculative_decoding)
        self.external_command = external_command.strip()
        save_llm_settings(
            {
                "model_path": str(self.model_path),
                "backend": self.backend_name,
                "enable_speculative_decoding": self.enable_speculative_decoding,
                "external_command": self.external_command,
            }
        )
        self.model_info = inspect_litertlm_header(self.model_path)
        self._litert_lm = import_litert_lm()
        self.runtime_diagnostics = inspect_llm_runtime()
        self.status = self._detect_status()
        return self.status

    def close(self) -> None:
        with self._lock:
            engine = self._engine
            context = self._engine_context
            self._engine = None
            self._engine_context = None
            try:
                if context is not None and hasattr(context, "__exit__"):
                    context.__exit__(None, None, None)
                elif engine is not None and hasattr(engine, "close"):
                    engine.close()
            except Exception:
                pass

    def _detect_status(self) -> str:
        if not self.model_path.exists():
            return f"LLM 모델 파일을 찾지 못했습니다: {self.model_path}"
        model_desc = self.model_path.name
        if self.model_info:
            model_desc = f"{model_desc} ({self.model_info})"
        if self._litert_lm is not None:
            return (
                f"LLM 모델 파일 감지: {model_desc}. "
                f"LiteRT-LM Python API 준비됨, backend={self.backend_name}."
            )
        if self.external_command:
            return (
                f"LLM 모델 파일 감지: {model_desc}. "
                "COUNSELING_LLM_COMMAND/설정의 외부 런타임 명령을 사용합니다."
            )
        cli_path = self.runtime_diagnostics.get("litert_lm_cli")
        if cli_path:
            return (
                f"LLM 모델 파일 감지: {model_desc}. "
                f"LiteRT-LM CLI를 사용합니다: {cli_path}"
            )
        return (
            f"LLM 모델 파일 감지: {model_desc}. "
            "실제 생성에는 litert-lm-api 설치가 필요합니다. 설치 전에는 규칙 기반 fallback으로 동작합니다."
        )

    def generate(
        self,
        messages: list[ChatMessage],
        gallery_context: str | None,
        voice_context: str | None,
        memories: list[str],
        stream_callback: Callable[[str], None] | None = None,
    ) -> str:
        prompt = build_model_prompt(messages, gallery_context, voice_context, memories)
        last_user = next((m.content for m in reversed(messages) if normalize_role(m.role) == "user"), "")

        if self._litert_lm is not None and self.model_path.exists():
            try:
                result = self._generate_with_python_api(prompt, stream_callback)
                if result:
                    return result
            except Exception as error:
                self.status = f"LiteRT-LM Python API 실행 실패: {error}"
                error_reply = (
                    "LLM 실행 중 오류가 났습니다.\n\n"
                    f"오류: {error}\n\n"
                    "확인할 것: litert-lm-api 설치 여부, 모델 경로, backend(cpu/gpu/npu), GPU/Vulkan 드라이버."
                )
                if stream_callback is not None:
                    stream_callback(error_reply)
                return error_reply

        if self.external_command:
            result = run_external_llm_command(self.external_command, self.model_path, prompt, stream_callback)
            if result:
                return result

        cli_path = self.runtime_diagnostics.get("litert_lm_cli")
        if cli_path and self.model_path.exists():
            result = run_litert_lm_cli(
                str(cli_path),
                self.model_path,
                prompt,
                self.backend_name,
                self.enable_speculative_decoding,
                stream_callback,
            )
            if result:
                return result

        fallback = fallback_counseling_reply(last_user, gallery_context, voice_context, memories)
        if stream_callback is not None:
            stream_callback(fallback)
        return fallback

    def _generate_with_python_api(
        self,
        prompt: str,
        stream_callback: Callable[[str], None] | None = None,
    ) -> str | None:
        litert_lm = self._litert_lm
        if litert_lm is None:
            return None
        with self._lock:
            engine = self._ensure_engine(litert_lm)
            create_kwargs = {}
            system_message = create_litert_system_message(litert_lm)
            if system_message is not None:
                create_kwargs["messages"] = [system_message]
            try:
                conversation_context = engine.create_conversation(**create_kwargs)
            except TypeError:
                conversation_context = engine.create_conversation()

            conversation = conversation_context
            entered = False
            if hasattr(conversation_context, "__enter__"):
                conversation = conversation_context.__enter__()
                entered = True
            try:
                if hasattr(conversation, "send_message_async"):
                    parts: list[str] = []
                    for chunk in conversation.send_message_async(prompt):
                        text = extract_litert_text(chunk)
                        if text:
                            parts.append(text)
                            if stream_callback is not None:
                                stream_callback(text)
                    return "".join(parts).strip() or None
                response = conversation.send_message(prompt)
                text = extract_litert_text(response).strip()
                if text and stream_callback is not None:
                    stream_callback(text)
                return text or None
            finally:
                if entered and hasattr(conversation_context, "__exit__"):
                    conversation_context.__exit__(None, None, None)

    def _ensure_engine(self, litert_lm: object) -> object:
        if self._engine is not None:
            return self._engine
        cache_dir = writable_app_dir() / "litert_lm_cache"
        cache_dir.mkdir(parents=True, exist_ok=True)
        kwargs: dict[str, object] = {}
        backend = create_litert_backend(litert_lm, self.backend_name)
        if backend is not None:
            kwargs["backend"] = backend
        kwargs["cache_dir"] = str(cache_dir)
        if self.enable_speculative_decoding:
            kwargs["enable_speculative_decoding"] = True

        try:
            engine_context = litert_lm.Engine(str(self.model_path), **kwargs)
        except TypeError:
            # Older/newer builds may not accept cache_dir or speculative args.
            kwargs.pop("cache_dir", None)
            try:
                engine_context = litert_lm.Engine(str(self.model_path), **kwargs)
            except TypeError:
                kwargs.pop("enable_speculative_decoding", None)
                engine_context = litert_lm.Engine(str(self.model_path), **kwargs)

        engine = engine_context
        if hasattr(engine_context, "__enter__"):
            engine = engine_context.__enter__()
            self._engine_context = engine_context
        self._engine = engine
        self.status = f"LiteRT-LM 엔진 로드 완료: {self.model_path.name}, backend={self.backend_name}"
        return engine

class CounselingDesktopApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("1180x760")
        self.minsize(980, 640)

        self.queue: queue.Queue[Callable[[], None]] = queue.Queue()
        self.session_id = load_active_session_id() or create_session_id()
        saved_session = load_session(self.session_id)
        self.messages: list[ChatMessage] = saved_session.messages if saved_session else []
        self.gallery_summary: GallerySummary | None = None
        self.voice_result: VoiceResult | None = None
        self.memories = saved_session.memories if saved_session and saved_session.memories else load_memories()
        self.gallery_thumbnails: list[ImageTk.PhotoImage] = []
        self.chat_busy = False
        self.chat_stream_chars: deque[str] = deque()
        self.chat_stream_active = False
        self.chat_stream_finish_pending = False

        self.llm = LlmAdapter()
        self.voice = VoiceEmotionAnalyzer()

        self._build_style()
        self._build_layout()
        self._render_loaded_messages()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(80, self._process_queue)

    def _build_style(self) -> None:
        self.configure(bg="#f4f7f6")
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("TNotebook", background="#f4f7f6", borderwidth=0)
        style.configure("TNotebook.Tab", padding=(18, 10), font=("Malgun Gothic", 11))
        style.configure("TButton", font=("Malgun Gothic", 10), padding=(12, 8))
        style.configure("TLabel", font=("Malgun Gothic", 10), background="#f4f7f6")
        style.configure("Header.TLabel", font=("Malgun Gothic", 18, "bold"), foreground="#1f3d3f")

    def _build_layout(self) -> None:
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=14, pady=14)

        self.chat_tab = ttk.Frame(self.notebook)
        self.gallery_tab = ttk.Frame(self.notebook)
        self.voice_tab = ttk.Frame(self.notebook)
        self.memory_tab = ttk.Frame(self.notebook)
        self.prompt_tab = ttk.Frame(self.notebook)
        self.settings_tab = ttk.Frame(self.notebook)
        self.unsupported_tab = ttk.Frame(self.notebook)

        self.notebook.add(self.chat_tab, text="상담")
        self.notebook.add(self.gallery_tab, text="갤러리")
        self.notebook.add(self.voice_tab, text="음성")
        self.notebook.add(self.memory_tab, text="중요 기억")
        self.notebook.add(self.prompt_tab, text="프롬프트")
        self.notebook.add(self.settings_tab, text="설정")
        self.notebook.add(self.unsupported_tab, text="Windows 제한")

        self._build_chat_tab()
        self._build_gallery_tab()
        self._build_voice_tab()
        self._build_memory_tab()
        self._build_prompt_tab()
        self._build_settings_tab()
        self._build_unsupported_tab()

    def _build_chat_tab(self) -> None:
        root = ttk.Frame(self.chat_tab)
        root.pack(fill="both", expand=True, padx=12, pady=12)
        header = ttk.Frame(root)
        header.pack(fill="x")
        ttk.Label(header, text="상담", style="Header.TLabel").pack(side="left", anchor="w")
        ttk.Button(header, text="새 세션", command=self.new_session).pack(side="right", padx=(8, 0))
        ttk.Button(header, text="세션 목록", command=self.show_sessions).pack(side="right", padx=(8, 0))
        ttk.Button(header, text="내보내기", command=self.export_session).pack(side="right", padx=(8, 0))
        ttk.Button(header, text="불러오기", command=self.import_session).pack(side="right")

        self.chat_log = tk.Text(root, wrap="word", height=26, font=("Malgun Gothic", 11), bg="#ffffff", relief="flat")
        self.chat_log.pack(fill="both", expand=True, pady=(12, 8))
        self.chat_log.configure(state="disabled")

        input_row = ttk.Frame(root)
        input_row.pack(fill="x")
        self.chat_input = tk.Text(input_row, height=4, wrap="word", font=("Malgun Gothic", 11))
        self.chat_input.pack(side="left", fill="x", expand=True)
        self.chat_input.bind("<Control-Return>", lambda _event: self.send_chat())
        self.chat_send_button = ttk.Button(input_row, text="전송", command=self.send_chat)
        self.chat_send_button.pack(side="left", padx=(8, 0), fill="y")

        self.chat_status = ttk.Label(root, text=self.llm.status)
        self.chat_status.pack(anchor="w", pady=(8, 0))
        if not self.messages:
            self._append_chat("system", "안녕하세요. 지금 떠오르는 고민을 적어 주세요. 갤러리/음성 탭에서 분석한 맥락은 상담에 보조적으로 반영됩니다.")

    def _build_gallery_tab(self) -> None:
        root = ttk.Frame(self.gallery_tab)
        root.pack(fill="both", expand=True, padx=12, pady=12)
        ttk.Label(root, text="갤러리 분석", style="Header.TLabel").pack(anchor="w")

        controls = ttk.Frame(root)
        controls.pack(fill="x", pady=(12, 8))
        ttk.Button(controls, text="이미지 파일 선택", command=self.choose_gallery_files).pack(side="left")
        ttk.Button(controls, text="폴더 선택", command=self.choose_gallery_folder).pack(side="left", padx=8)
        ttk.Button(controls, text="분석 비우기", command=self.clear_gallery).pack(side="left")

        self.gallery_text = tk.Text(root, height=8, wrap="word", font=("Malgun Gothic", 10), bg="#ffffff", relief="flat")
        self.gallery_text.pack(fill="x")
        self.gallery_text.insert("1.0", "이미지나 폴더를 선택하면 밝기/색감 기반 보조 요약을 생성합니다.\n")
        self.gallery_text.configure(state="disabled")

        self.gallery_canvas = tk.Canvas(root, bg="#f4f7f6", highlightthickness=0)
        self.gallery_canvas.pack(fill="both", expand=True, pady=(12, 0))
        self.gallery_inner = ttk.Frame(self.gallery_canvas)
        self.gallery_canvas.create_window((0, 0), window=self.gallery_inner, anchor="nw")
        self.gallery_inner.bind("<Configure>", lambda _e: self.gallery_canvas.configure(scrollregion=self.gallery_canvas.bbox("all")))

    def _build_voice_tab(self) -> None:
        root = ttk.Frame(self.voice_tab)
        root.pack(fill="both", expand=True, padx=12, pady=12)
        ttk.Label(root, text="음성 분석", style="Header.TLabel").pack(anchor="w")
        ttk.Label(root, text="WAV 파일을 선택하면 12초 기준으로 감정 분포를 분석하고 상담 프롬프트 맥락으로 저장합니다.").pack(anchor="w", pady=(8, 12))

        controls = ttk.Frame(root)
        controls.pack(fill="x")
        ttk.Button(controls, text="WAV 선택 및 분석", command=self.choose_voice_file).pack(side="left")
        ttk.Button(controls, text="음성 맥락 비우기", command=self.clear_voice).pack(side="left", padx=8)

        self.voice_status = ttk.Label(root, text=self.voice.status)
        self.voice_status.pack(anchor="w", pady=(12, 8))
        self.voice_text = tk.Text(root, wrap="word", font=("Malgun Gothic", 11), bg="#ffffff", relief="flat")
        self.voice_text.pack(fill="both", expand=True)
        self.voice_text.insert("1.0", "분석 결과가 여기에 표시됩니다.\n")
        self.voice_text.configure(state="disabled")

    def _build_memory_tab(self) -> None:
        root = ttk.Frame(self.memory_tab)
        root.pack(fill="both", expand=True, padx=12, pady=12)
        ttk.Label(root, text="중요 기억", style="Header.TLabel").pack(anchor="w")
        ttk.Label(root, text="상담 원칙과 사용자가 저장한 기억을 관리합니다.").pack(anchor="w", pady=(8, 12))

        self.memory_list = tk.Listbox(root, font=("Malgun Gothic", 11), height=16)
        self.memory_list.pack(fill="both", expand=True)
        controls = ttk.Frame(root)
        controls.pack(fill="x", pady=(8, 0))
        self.memory_entry = ttk.Entry(controls)
        self.memory_entry.pack(side="left", fill="x", expand=True)
        ttk.Button(controls, text="추가", command=self.add_memory).pack(side="left", padx=8)
        ttk.Button(controls, text="선택 삭제", command=self.delete_memory).pack(side="left")
        self.refresh_memories()

    def _build_prompt_tab(self) -> None:
        root = ttk.Frame(self.prompt_tab)
        root.pack(fill="both", expand=True, padx=12, pady=12)
        header = ttk.Frame(root)
        header.pack(fill="x")
        ttk.Label(header, text="프롬프트 미리보기", style="Header.TLabel").pack(side="left", anchor="w")
        ttk.Button(header, text="갱신", command=self.refresh_prompt_preview).pack(side="right")
        self.prompt_text = tk.Text(root, wrap="word", font=("Consolas", 10), bg="#ffffff", relief="flat")
        self.prompt_text.pack(fill="both", expand=True, pady=(12, 0))
        self.refresh_prompt_preview()

    def _build_settings_tab(self) -> None:
        root = ttk.Frame(self.settings_tab)
        root.pack(fill="both", expand=True, padx=12, pady=12)
        ttk.Label(root, text="설정", style="Header.TLabel").pack(anchor="w")

        llm_frame = ttk.LabelFrame(root, text="LiteRT-LM 모델 실행")
        llm_frame.pack(fill="x", pady=(12, 8))

        self.llm_model_var = tk.StringVar(value=str(self.llm.model_path))
        self.llm_backend_var = tk.StringVar(value=self.llm.backend_name)
        self.llm_speculative_var = tk.BooleanVar(value=self.llm.enable_speculative_decoding)
        self.llm_external_command_var = tk.StringVar(value=self.llm.external_command)

        model_row = ttk.Frame(llm_frame)
        model_row.pack(fill="x", padx=10, pady=(10, 4))
        ttk.Label(model_row, text="모델 파일").pack(side="left")
        ttk.Entry(model_row, textvariable=self.llm_model_var).pack(side="left", fill="x", expand=True, padx=8)
        ttk.Button(model_row, text="찾기", command=self.choose_llm_model).pack(side="left")

        runtime_row = ttk.Frame(llm_frame)
        runtime_row.pack(fill="x", padx=10, pady=4)
        ttk.Label(runtime_row, text="Backend").pack(side="left")
        ttk.Combobox(
            runtime_row,
            textvariable=self.llm_backend_var,
            values=["cpu", "gpu", "npu"],
            width=8,
            state="readonly",
        ).pack(side="left", padx=(8, 16))
        ttk.Checkbutton(
            runtime_row,
            text="Speculative decoding 사용",
            variable=self.llm_speculative_var,
        ).pack(side="left")
        ttk.Button(runtime_row, text="적용/엔진 재로드", command=self.apply_llm_settings).pack(side="right", padx=(8, 0))
        ttk.Button(runtime_row, text="짧은 테스트", command=self.test_llm_runtime).pack(side="right")

        external_row = ttk.Frame(llm_frame)
        external_row.pack(fill="x", padx=10, pady=(4, 10))
        ttk.Label(external_row, text="외부 명령(선택)").pack(side="left")
        ttk.Entry(external_row, textvariable=self.llm_external_command_var).pack(side="left", fill="x", expand=True, padx=8)
        ttk.Label(
            llm_frame,
            text="권장 설치: pip install litert-lm-api  /  모델명: gemma-4-E4B-it.litertlm",
        ).pack(anchor="w", padx=10, pady=(0, 8))

        self.settings_text = tk.Text(root, wrap="word", font=("Consolas", 10), bg="#ffffff", relief="flat")
        self.settings_text.pack(fill="both", expand=True, pady=(8, 0))
        self._refresh_settings_text()

    def _build_unsupported_tab(self) -> None:
        root = ttk.Frame(self.unsupported_tab)
        root.pack(fill="both", expand=True, padx=12, pady=12)
        ttk.Label(root, text="Windows 제한", style="Header.TLabel").pack(anchor="w")
        text = tk.Text(root, wrap="word", font=("Malgun Gothic", 11), bg="#ffffff", relief="flat")
        text.pack(fill="both", expand=True, pady=(12, 0))
        text.insert(
            "1.0",
            "\n".join(
                [
                    "Windows 독립 실행 앱에서는 Android 시스템 권한이 필요한 기능을 동일하게 실행하지 않습니다.",
                    "",
                    "- 피노타입: Android 통화 기록, 앱 사용량 접근 권한에 의존하므로 Windows에서 제외했습니다.",
                    "- Health Connect: Android Health Connect API에 의존하므로 Windows에서 제외했습니다.",
                    "- 모델 채팅: litert-lm-api가 설치되어 있으면 .litertlm 모델을 직접 실행합니다. 미설치 시 fallback 응답을 사용합니다.",
                    "",
                    "대체로 제공하는 흐름:",
                    "- 갤러리 탭에서 이미지/폴더를 직접 선택해 로컬 요약을 만듭니다.",
                    "- 음성 탭에서 WAV 파일을 선택해 ONNX 감정 분석을 수행합니다.",
                    "- 상담 탭에서 갤러리/음성/중요 기억 맥락을 반영해 대화를 이어갑니다.",
                    "- 세션 목록, 내보내기, 불러오기로 대화 기록을 관리합니다.",
                ]
            ),
        )
        text.configure(state="disabled")

    def _settings_info(self) -> dict[str, object]:
        return {
            "llm_model": str(self.llm.model_path),
            "llm_model_info": self.llm.model_info,
            "llm_backend": self.llm.backend_name,
            "llm_speculative_decoding": self.llm.enable_speculative_decoding,
            "llm_status": self.llm.status,
            "llm_external_command": self.llm.external_command or None,
            "llm_settings_file": str(llm_settings_file()),
            "llm_runtime_diagnostics": self.llm.runtime_diagnostics,
            "voice_model": str(self.voice.model_path),
            "voice_status": self.voice.status,
            "active_session_id": self.session_id,
            "sessions_dir": str(sessions_dir()),
            "memory_file": str(memory_file()),
            "unsupported_windows_features": [
                "Android call-log phenotype",
                "Android app-usage phenotype",
                "Health Connect direct integration",
            ],
            "install_hint": "python -m pip install litert-lm-api",
            "run_command": "python .\\DesktopApp\\app.py",
        }

    def _refresh_settings_text(self) -> None:
        self.settings_text.configure(state="normal")
        self.settings_text.delete("1.0", "end")
        self.settings_text.insert("1.0", json.dumps(self._settings_info(), ensure_ascii=False, indent=2))
        self.settings_text.configure(state="disabled")

    def choose_llm_model(self) -> None:
        path = filedialog.askopenfilename(
            title="LiteRT-LM 모델 선택",
            filetypes=[("LiteRT-LM model", "*.litertlm"), ("All files", "*.*")],
        )
        if path:
            self.llm_model_var.set(path)

    def apply_llm_settings(self) -> None:
        status = self.llm.configure(
            Path(self.llm_model_var.get().strip()),
            self.llm_backend_var.get().strip(),
            bool(self.llm_speculative_var.get()),
            self.llm_external_command_var.get().strip(),
        )
        self.chat_status.configure(text=status)
        self._refresh_settings_text()
        self.refresh_prompt_preview()
        messagebox.showinfo(APP_TITLE, status)

    def test_llm_runtime(self) -> None:
        self.chat_status.configure(text="LLM 짧은 테스트 중...")
        threading.Thread(target=self._test_llm_runtime_worker, daemon=True).start()

    def _test_llm_runtime_worker(self) -> None:
        try:
            reply = self.llm.generate(
                [ChatMessage("user", "한 문장으로만 답해줘. LiteRT-LM 테스트 성공이라고 말해줘.")],
                None,
                None,
                self.memories,
            )
            self.queue.put(lambda: self._finish_llm_runtime_test(reply))
        except Exception as error:
            self.queue.put(lambda: self._finish_llm_runtime_test(f"테스트 실패: {error}"))

    def _finish_llm_runtime_test(self, reply: str) -> None:
        self.chat_status.configure(text=self.llm.status)
        self._refresh_settings_text()
        preview = shorten_context(reply, 500)
        messagebox.showinfo(APP_TITLE, f"LLM 테스트 결과:\n\n{preview}")

    def on_close(self) -> None:
        save_session(self.session_id, self.messages, self.memories)
        self.llm.close()
        self.destroy()

    def choose_gallery_files(self) -> None:
        files = filedialog.askopenfilenames(
            title="분석할 이미지 선택",
            filetypes=[("Images", "*.jpg *.jpeg *.png *.bmp *.webp"), ("All files", "*.*")],
        )
        if files:
            self.analyze_gallery([Path(f) for f in files])

    def choose_gallery_folder(self) -> None:
        folder = filedialog.askdirectory(title="이미지 폴더 선택")
        if not folder:
            return
        paths = collect_gallery_images(Path(folder))
        self.analyze_gallery(paths)

    def analyze_gallery(self, paths: list[Path]) -> None:
        if Image is None:
            messagebox.showerror(APP_TITLE, "Pillow 패키지를 찾지 못해 이미지를 분석할 수 없습니다.")
            return
        paths = paths[:MAX_GALLERY_IMAGES]
        self.gallery_summary = summarize_gallery(paths)
        self._set_text(self.gallery_text, self.gallery_summary.to_context())
        self.render_gallery_thumbnails(self.gallery_summary.image_paths[:40])
        self.refresh_prompt_preview()

    def render_gallery_thumbnails(self, paths: list[Path]) -> None:
        for child in self.gallery_inner.winfo_children():
            child.destroy()
        self.gallery_thumbnails.clear()
        for index, path in enumerate(paths):
            try:
                image = Image.open(path)
                image.thumbnail((150, 150))
                photo = ImageTk.PhotoImage(image)
                self.gallery_thumbnails.append(photo)
                label = ttk.Label(self.gallery_inner, image=photo, text=path.name, compound="top")
                label.grid(row=index // 5, column=index % 5, padx=8, pady=8)
            except Exception:
                continue

    def clear_gallery(self) -> None:
        self.gallery_summary = None
        self._set_text(self.gallery_text, "갤러리 분석 맥락을 비웠습니다.")
        self.render_gallery_thumbnails([])
        self.refresh_prompt_preview()

    def choose_voice_file(self) -> None:
        path = filedialog.askopenfilename(title="WAV 파일 선택", filetypes=[("WAV", "*.wav"), ("All files", "*.*")])
        if not path:
            return
        self.voice_status.configure(text="음성 분석 중입니다...")
        threading.Thread(target=self._analyze_voice_worker, args=(Path(path),), daemon=True).start()

    def _analyze_voice_worker(self, path: Path) -> None:
        try:
            result = self.voice.analyze(path)
            self.queue.put(lambda: self._set_voice_result(result))
        except Exception as error:
            self.queue.put(lambda: self._set_voice_error(error))

    def _set_voice_result(self, result: VoiceResult) -> None:
        self.voice_result = result
        lines = [
            f"예측 감정: {result.label} ({result.confidence * 100:.2f}%)",
            "",
            "감정 분포:",
        ]
        for label, value in sorted(result.probabilities.items(), key=lambda item: item[1], reverse=True):
            lines.append(f"- {label}: {value * 100:.2f}%")
        lines.extend(["", "상담 반영 맥락:", result.prompt_context])
        self.voice_status.configure(text=self.voice.status)
        self._set_text(self.voice_text, "\n".join(lines))
        self.refresh_prompt_preview()

    def _set_voice_error(self, error: Exception) -> None:
        self.voice_status.configure(text=str(error))
        self._set_text(self.voice_text, f"음성 분석 실패: {error}")

    def clear_voice(self) -> None:
        self.voice_result = None
        self._set_text(self.voice_text, "음성 분석 맥락을 비웠습니다.")
        self.refresh_prompt_preview()

    def add_memory(self) -> None:
        text = self.memory_entry.get().strip()
        if not text:
            return
        self.memories.append(text)
        save_memories(self.memories)
        self.memory_entry.delete(0, "end")
        self.refresh_memories()

    def delete_memory(self) -> None:
        selected = list(self.memory_list.curselection())
        if not selected:
            return
        for index in reversed(selected):
            del self.memories[index]
        save_memories(self.memories)
        self.refresh_memories()

    def refresh_memories(self) -> None:
        self.memory_list.delete(0, "end")
        for memory in self.memories:
            self.memory_list.insert("end", memory)
        save_memories(self.memories)
        save_session(self.session_id, self.messages, self.memories)
        self.refresh_prompt_preview()

    def send_chat(self) -> None:
        if self.chat_busy:
            self.chat_status.configure(text="이전 응답을 생성 중입니다. 잠시만 기다려 주세요.")
            return
        content = self.chat_input.get("1.0", "end").strip()
        if not content:
            return
        self.chat_busy = True
        self.chat_send_button.configure(state="disabled")
        if any(token in content for token in ("기억해줘", "저장해줘", "이건 중요해")):
            self.memories.append(content)
            save_memories(self.memories)
            self.refresh_memories()
        self.chat_input.delete("1.0", "end")
        self.messages.append(ChatMessage("user", content))
        self._append_chat("나", content)
        save_session(self.session_id, self.messages, self.memories)
        self.chat_status.configure(text="LLM 로딩/응답 생성 중... 첫 실행은 1분 이상 걸릴 수 있습니다.")
        self._start_chat_stream()
        self.refresh_prompt_preview()
        threading.Thread(target=self._chat_worker, daemon=True).start()

    def _chat_worker(self) -> None:
        try:
            gallery_context = self.gallery_summary.to_context() if self.gallery_summary else None
            voice_context = self.voice_result.prompt_context if self.voice_result else None
            reply = self.llm.generate(
                self.messages,
                gallery_context,
                voice_context,
                self.memories,
                stream_callback=lambda text: self.queue.put(lambda text=text: self._queue_chat_stream_text(text)),
            )
        except Exception as error:
            reply = f"응답 생성 중 오류가 났습니다.\n\n오류: {error}"
            self.queue.put(lambda reply=reply: self._queue_chat_stream_text(reply))
        self.messages.append(ChatMessage("assistant", reply))
        self.queue.put(self._finish_chat)

    def _finish_chat(self) -> None:
        self.chat_stream_finish_pending = True
        if not self.chat_stream_active:
            self._drain_chat_stream()
        self.chat_status.configure(text=self.llm.status)
        save_session(self.session_id, self.messages, self.memories)
        self.refresh_prompt_preview()

    def _render_loaded_messages(self) -> None:
        if not self.messages:
            return
        for message in self.messages:
            role = "나" if normalize_role(message.role) == "user" else "상담 챗봇"
            self._append_chat(role, message.content)

    def new_session(self) -> None:
        save_session(self.session_id, self.messages, self.memories)
        self.session_id = create_session_id()
        save_active_session_id(self.session_id)
        self.messages = []
        self.chat_busy = False
        self.chat_stream_chars.clear()
        self.chat_stream_active = False
        self.chat_stream_finish_pending = False
        self.chat_send_button.configure(state="normal")
        self.chat_log.configure(state="normal")
        self.chat_log.delete("1.0", "end")
        self.chat_log.configure(state="disabled")
        self._append_chat("system", "새 상담 세션을 시작했습니다. 지금 떠오르는 고민을 적어 주세요.")
        self.chat_status.configure(text=f"새 세션: {self.session_id}")
        self._refresh_settings_text()
        self.refresh_prompt_preview()

    def show_sessions(self) -> None:
        sessions = list_sessions()
        window = tk.Toplevel(self)
        window.title("세션 목록")
        window.geometry("560x360")
        frame = ttk.Frame(window)
        frame.pack(fill="both", expand=True, padx=12, pady=12)
        listbox = tk.Listbox(frame, font=("Malgun Gothic", 10))
        listbox.pack(fill="both", expand=True)
        for session in sessions:
            label = f"{format_timestamp(session.updated_at)} · {len(session.messages)}개 · {session.title}"
            listbox.insert("end", label)

        def load_selected() -> None:
            selected = listbox.curselection()
            if not selected:
                return
            self.load_session_into_ui(sessions[selected[0]])
            window.destroy()

        controls = ttk.Frame(frame)
        controls.pack(fill="x", pady=(8, 0))
        ttk.Button(controls, text="불러오기", command=load_selected).pack(side="left")
        ttk.Button(controls, text="닫기", command=window.destroy).pack(side="right")

    def load_session_into_ui(self, session: SavedSession) -> None:
        save_session(self.session_id, self.messages, self.memories)
        self.session_id = session.id
        save_active_session_id(self.session_id)
        self.messages = session.messages
        self.memories = session.memories
        save_memories(self.memories)
        self.refresh_memories()
        self.chat_log.configure(state="normal")
        self.chat_log.delete("1.0", "end")
        self.chat_log.configure(state="disabled")
        self._render_loaded_messages()
        self.chat_status.configure(text=f"세션 불러옴: {session.title}")
        self._refresh_settings_text()
        self.refresh_prompt_preview()

    def export_session(self) -> None:
        path = filedialog.asksaveasfilename(
            title="상담 세션 내보내기",
            defaultextension=".json",
            initialfile="counseling_session.json",
            filetypes=[("JSON", "*.json"), ("All files", "*.*")],
        )
        if not path:
            return
        save_session(self.session_id, self.messages, self.memories)
        data = session_to_dict(self.session_id, self.messages, self.memories)
        Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        self.chat_status.configure(text=f"세션 내보내기 완료: {path}")

    def import_session(self) -> None:
        path = filedialog.askopenfilename(
            title="상담 세션 불러오기",
            filetypes=[("JSON", "*.json"), ("All files", "*.*")],
        )
        if not path:
            return
        try:
            session = parse_session(Path(path).read_text(encoding="utf-8"), fallback_id=create_session_id())
        except Exception as error:
            messagebox.showerror(APP_TITLE, f"세션을 불러오지 못했습니다: {error}")
            return
        self.load_session_into_ui(session)

    def refresh_prompt_preview(self) -> None:
        if not hasattr(self, "prompt_text"):
            return
        gallery_context = self.gallery_summary.to_context() if self.gallery_summary else None
        voice_context = self.voice_result.prompt_context if self.voice_result else None
        last_user = next((m.content for m in reversed(self.messages) if normalize_role(m.role) == "user"), "")
        prompt = build_prompt_preview(last_user, gallery_context, voice_context, self.memories, self.llm.status)
        self._set_text(self.prompt_text, prompt)

    def _append_chat(self, role: str, content: str) -> None:
        self.chat_log.configure(state="normal")
        self.chat_log.insert("end", f"{role}\n{content}\n\n")
        self.chat_log.see("end")
        self.chat_log.configure(state="disabled")

    def _start_chat_stream(self) -> None:
        self.chat_stream_chars.clear()
        self.chat_stream_active = False
        self.chat_stream_finish_pending = False
        self.chat_log.configure(state="normal")
        self.chat_log.insert("end", "상담 챗봇\n")
        self.chat_log.see("end")
        self.chat_log.configure(state="disabled")

    def _queue_chat_stream_text(self, text: str) -> None:
        if not text:
            return
        self.chat_stream_chars.extend(text)
        if not self.chat_stream_active:
            self.chat_stream_active = True
            self._drain_chat_stream()

    def _drain_chat_stream(self) -> None:
        if self.chat_stream_chars:
            char = self.chat_stream_chars.popleft()
            self.chat_log.configure(state="normal")
            self.chat_log.insert("end", char)
            self.chat_log.see("end")
            self.chat_log.configure(state="disabled")
            self.after(12, self._drain_chat_stream)
            return

        self.chat_stream_active = False
        if self.chat_stream_finish_pending:
            self.chat_stream_finish_pending = False
            self.chat_log.configure(state="normal")
            self.chat_log.insert("end", "\n\n")
            self.chat_log.see("end")
            self.chat_log.configure(state="disabled")
            self.chat_busy = False
            self.chat_send_button.configure(state="normal")

    def _set_text(self, widget: tk.Text, value: str) -> None:
        widget.configure(state="normal")
        widget.delete("1.0", "end")
        widget.insert("1.0", value)
        widget.configure(state="disabled")

    def _process_queue(self) -> None:
        while True:
            try:
                callback = self.queue.get_nowait()
            except queue.Empty:
                break
            callback()
        self.after(80, self._process_queue)


def summarize_gallery(paths: list[Path]) -> GallerySummary:
    dark = bright = warm = cool = 0
    brightness_values: list[float] = []
    valid_paths: list[Path] = []
    for path in paths:
        if path.suffix.lower() not in IMAGE_EXTENSIONS:
            continue
        try:
            image = Image.open(path).convert("RGB")
            image = ImageOps.exif_transpose(image)
            image.thumbnail((256, 256))
            arr = np.asarray(image).astype(np.float32)
        except Exception:
            continue
        valid_paths.append(path)
        channel_mean = arr.mean(axis=(0, 1))
        brightness = float(channel_mean.mean())
        brightness_values.append(brightness)
        if brightness < 85:
            dark += 1
        if brightness > 180:
            bright += 1
        if channel_mean[0] > channel_mean[2] + 8:
            warm += 1
        if channel_mean[2] > channel_mean[0] + 8:
            cool += 1
    avg = float(np.mean(brightness_values)) if brightness_values else 0.0
    return GallerySummary(len(valid_paths), dark, bright, warm, cool, avg, valid_paths)


def collect_gallery_images(folder: Path) -> list[Path]:
    if not folder.exists() or not folder.is_dir():
        return []
    paths = [
        path
        for path in folder.rglob("*")
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    ]
    return sorted(paths, key=lambda path: str(path).lower())[:MAX_GALLERY_IMAGES]


def read_wav_mono(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as reader:
        channels = reader.getnchannels()
        sample_width = reader.getsampwidth()
        sample_rate = reader.getframerate()
        frames = reader.readframes(reader.getnframes())
    if sample_width == 1:
        data = np.frombuffer(frames, dtype=np.uint8).astype(np.float32)
        data = (data - 128.0) / 128.0
    elif sample_width == 2:
        data = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
    elif sample_width == 4:
        data = np.frombuffer(frames, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        raise ValueError(f"지원하지 않는 WAV sample width: {sample_width}")
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return data.astype(np.float32), sample_rate


def convert_sample_rate(samples: np.ndarray, source_rate: int, target_rate: int) -> np.ndarray:
    if source_rate == target_rate:
        return samples
    target_len = int(len(samples) * target_rate / source_rate)
    x_old = np.linspace(0.0, 1.0, len(samples), endpoint=False)
    x_new = np.linspace(0.0, 1.0, target_len, endpoint=False)
    return np.interp(x_new, x_old, samples).astype(np.float32)


def softmax(values: np.ndarray) -> np.ndarray:
    values = values.astype(np.float64)
    exp = np.exp(values - np.max(values))
    return (exp / exp.sum()).astype(np.float32)


def build_voice_prompt_context(label: str, confidence: float, probabilities: dict[str, float]) -> str:
    korean = {
        "Happy": "밝거나 긍정적인 정서",
        "Sad": "슬픔 또는 처진 정서",
        "Angry": "분노 또는 억울함",
        "Fearful": "불안 또는 두려움",
        "Neutral": "중립적 정서",
    }.get(label, label)
    scores = ", ".join(f"{name} {value * 100:.1f}%" for name, value in probabilities.items())
    return (
        f"음성 감정 분석 보조 결과: {korean}({label}), confidence={confidence * 100:.1f}%.\n"
        f"전체 분포: {scores}.\n"
        "이 결과는 현재 턴의 말투 참고용 보조 신호이며, 감정 상태를 확정하거나 진단하지 않습니다."
    )


def inspect_litertlm_header(path: Path) -> str | None:
    if not path.exists() or not path.is_file():
        return None
    try:
        with path.open("rb") as handle:
            header = handle.read(32)
        if len(header) < 32 or header[:8] != b"LITERTLM":
            return None
        major = int.from_bytes(header[8:12], "little")
        minor = int.from_bytes(header[12:16], "little")
        patch = int.from_bytes(header[16:20], "little")
        metadata_end = int.from_bytes(header[24:32], "little")
        size_gb = path.stat().st_size / (1024 ** 3)
        return f"LiteRT-LM v{major}.{minor}.{patch}, metadata_end={metadata_end}, {size_gb:.2f}GB"
    except OSError:
        return None


def fallback_counseling_reply(
    user_text: str,
    gallery_context: str | None,
    voice_context: str | None,
    memories: list[str],
) -> str:
    risk_words = ["죽고", "자살", "자해", "때려", "폭력", "학대", "응급", "위험"]
    parts = ["말해준 내용을 보면 지금 부담을 줄이면서도 상황을 정리하고 싶은 마음이 커 보여요."]
    if voice_context:
        parts.append("음성 분석은 진단이 아니라 말투 참고용으로만 반영할게요.\n" + shorten_context(voice_context))
    if gallery_context:
        parts.append("선택한 이미지 요약은 생활 맥락을 떠올리는 보조 자료로만 참고하겠습니다.\n" + shorten_context(gallery_context))
    if memories:
        parts.append(f"저장된 기억 중에는 '{memories[-1][:60]}' 내용을 우선 참고할 수 있어요.")
    if any(word in user_text for word in risk_words):
        parts.append("혹시 지금 몸이나 마음이 다치거나 위험하다고 느끼는 상황인가요? 즉시 위험하면 112 또는 119에 연락하거나 가까운 응급실로 이동해 주세요.")
    else:
        parts.append("지금 이 문제를 나누면, 사실로 확인된 것과 마음속 압박감을 분리해서 볼 수 있습니다.")
        parts.append("가장 먼저 다뤄보고 싶은 건 상황 정리, 감정 진정, 다음 행동 선택 중 어느 쪽인가요?")
    return "\n\n".join(parts)


def build_prompt_preview(
    user_text: str,
    gallery_context: str | None,
    voice_context: str | None,
    memories: list[str],
    runtime_status: str | None = None,
) -> str:
    sections = [
        "[상담 보조자 지침]",
        "진단이나 치료를 대신하지 않고, 사용자가 감정과 상황을 정리하고 다음 행동을 고를 수 있도록 돕습니다.",
        "자해, 폭력, 학대, 긴급 위험 신호가 있으면 안전 확인과 긴급 지원 안내를 우선합니다.",
        "갤러리와 음성 분석은 확정 판단이 아니라 대화 참고용 보조 맥락으로만 사용합니다.",
        "",
        "[사용자 메시지]",
        user_text.strip() if user_text.strip() else "(아직 사용자 메시지가 없습니다.)",
        "",
        "[중요 기억]",
    ]
    if memories:
        sections.extend(f"- {memory}" for memory in memories[-20:])
    else:
        sections.append("(저장된 중요 기억이 없습니다.)")
    sections.extend(["", "[갤러리 분석 맥락]", gallery_context or "(갤러리 분석 맥락이 없습니다.)"])
    sections.extend(["", "[음성 분석 맥락]", voice_context or "(음성 분석 맥락이 없습니다.)"])
    sections.extend(
        [
            "",
            "[로컬 LLM 실행 상태]",
            runtime_status or "LiteRT-LM Python API 또는 CLI를 자동 감지합니다.",
        ]
    )
    return "\n".join(sections)


def build_model_prompt(
    messages: list[ChatMessage],
    gallery_context: str | None,
    voice_context: str | None,
    memories: list[str],
) -> str:
    sections = [
        "너는 온디바이스 상담 보조자다.",
        "의학적 진단이나 치료를 대신하지 말고, 사용자가 감정과 상황을 정리하고 작고 안전한 다음 행동을 고르도록 도와라.",
        "자살, 자해, 타해, 학대, 폭력, 응급 위험 신호가 있으면 안전 확인과 긴급 지원 안내를 우선하라.",
        "갤러리와 음성 분석은 확정 판단이 아니라 참고용 보조 맥락으로만 사용하라.",
        "답변은 한국어로, 따뜻하지만 과장하지 않고 구체적으로 작성하라.",
        "",
        "[중요 기억]",
    ]
    if memories:
        sections.extend(f"- {memory}" for memory in memories[-20:])
    else:
        sections.append("(없음)")
    sections.extend(["", "[갤러리 분석 맥락]", gallery_context or "(없음)"])
    sections.extend(["", "[음성 분석 맥락]", voice_context or "(없음)"])
    sections.extend(["", "[최근 대화]"])
    recent_messages = messages[-12:]
    if not recent_messages:
        sections.append("User: (아직 메시지가 없습니다.)")
    for message in recent_messages:
        role = "Assistant" if normalize_role(message.role) == "assistant" else "User"
        sections.append(f"{role}: {message.content.strip()}")
    sections.extend(["", "Assistant:"])
    return "\n".join(sections)


def run_external_llm_command(
    command: str,
    model_path: Path,
    prompt: str,
    stream_callback: Callable[[str], None] | None = None,
) -> str | None:
    env = os.environ.copy()
    env["COUNSELING_LLM_MODEL"] = str(model_path)
    env["COUNSELING_LLM_PROMPT_FORMAT"] = "plain-text-stdin"
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            shell=True,
            env=env,
            bufsize=1,
        )
    except Exception as error:
        return f"외부 LLM 명령 실행 실패: {error}"
    assert process.stdin is not None
    assert process.stdout is not None
    assert process.stderr is not None
    stderr_parts: list[str] = []
    stderr_thread = threading.Thread(target=lambda: stderr_parts.append(process.stderr.read()), daemon=True)
    stderr_thread.start()
    try:
        process.stdin.write(prompt)
        process.stdin.close()
        output_parts: list[str] = []
        while True:
            char = process.stdout.read(1)
            if not char:
                break
            output_parts.append(char)
            if stream_callback is not None:
                stream_callback(char)
        return_code = process.wait(timeout=300)
        stderr_thread.join(timeout=1)
    except subprocess.TimeoutExpired:
        process.kill()
        return "외부 LLM 명령 실행 실패: timeout=300"
    except Exception as error:
        process.kill()
        return f"외부 LLM 명령 실행 실패: {error}"
    output = "".join(output_parts).strip()
    if return_code != 0:
        detail = "".join(stderr_parts).strip() or output or f"exit={return_code}"
        return f"외부 LLM 명령 실패: {detail}"
    return output or None


def run_litert_lm_cli(
    cli_path: str,
    model_path: Path,
    prompt: str,
    backend_name: str,
    enable_speculative_decoding: bool,
    stream_callback: Callable[[str], None] | None = None,
) -> str | None:
    command = [cli_path, "run", str(model_path), "--prompt", prompt]
    backend_name = normalize_backend_name(backend_name)
    if backend_name in {"gpu", "npu"}:
        command.append(f"--backend={backend_name}")
    if enable_speculative_decoding:
        command.append("--enable-speculative-decoding=true")
    try:
        process = subprocess.Popen(
            command,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=1,
        )
    except Exception as error:
        return f"LiteRT-LM CLI 실행 실패: {error}"
    assert process.stdout is not None
    assert process.stderr is not None
    stderr_parts: list[str] = []
    stderr_thread = threading.Thread(target=lambda: stderr_parts.append(process.stderr.read()), daemon=True)
    stderr_thread.start()
    try:
        output_parts: list[str] = []
        while True:
            char = process.stdout.read(1)
            if not char:
                break
            output_parts.append(char)
            if stream_callback is not None:
                stream_callback(char)
        return_code = process.wait(timeout=300)
        stderr_thread.join(timeout=1)
    except subprocess.TimeoutExpired:
        process.kill()
        return "LiteRT-LM CLI 실행 실패: timeout=300"
    except Exception as error:
        process.kill()
        return f"LiteRT-LM CLI 실행 실패: {error}"
    output = "".join(output_parts).strip()
    if return_code != 0:
        detail = "".join(stderr_parts).strip() or output or f"exit={return_code}"
        return f"LiteRT-LM CLI 실패: {detail}"
    return output or None


def shorten_context(value: str, limit: int = 420) -> str:
    compact = " ".join(value.split())
    if len(compact) <= limit:
        return compact
    return compact[: limit - 3].rstrip() + "..."


def find_module(name: str) -> bool:
    try:
        return find_spec(name) is not None
    except Exception:
        return False


def import_litert_lm() -> object | None:
    for module_name in ("litert_lm", "google.ai.edge.litertlm", "litertlm"):
        try:
            return importlib.import_module(module_name)
        except Exception:
            continue
    return None


def create_litert_backend(litert_lm: object, backend_name: str) -> object | None:
    backend_factory = getattr(litert_lm, "Backend", None)
    if backend_factory is None:
        return None
    backend_name = normalize_backend_name(backend_name)
    method_name = {"cpu": "CPU", "gpu": "GPU", "npu": "NPU"}.get(backend_name, "CPU")
    method = getattr(backend_factory, method_name, None)
    if method is None:
        return None
    try:
        return method()
    except TypeError:
        return method


def create_litert_system_message(litert_lm: object) -> object | None:
    message_type = getattr(litert_lm, "Message", None)
    if message_type is None or not hasattr(message_type, "system"):
        return None
    try:
        return message_type.system(
            "너는 한국어 온디바이스 상담 보조자다. 진단을 내리지 말고 안전하고 구체적인 도움을 제공한다."
        )
    except Exception:
        return None


def extract_litert_text(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        if isinstance(value.get("text"), str):
            return value["text"]
        content = value.get("content")
        if isinstance(content, list):
            parts: list[str] = []
            for item in content:
                if isinstance(item, dict):
                    if isinstance(item.get("text"), str):
                        parts.append(item["text"])
                    elif isinstance(item.get("content"), str):
                        parts.append(item["content"])
                elif isinstance(item, str):
                    parts.append(item)
            return "".join(parts)
        if isinstance(content, str):
            return content
    if isinstance(value, list):
        return "".join(extract_litert_text(item) for item in value)
    return str(value)


def normalize_backend_name(value: str) -> str:
    normalized = str(value or "cpu").strip().lower()
    if normalized not in {"cpu", "gpu", "npu"}:
        return "cpu"
    return normalized


def parse_bool(value: object) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


def llm_settings_file() -> Path:
    return writable_app_dir() / "llm_settings.json"


def load_llm_settings() -> dict[str, object]:
    path = llm_settings_file()
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_llm_settings(settings: dict[str, object]) -> None:
    llm_settings_file().write_text(json.dumps(settings, ensure_ascii=False, indent=2), encoding="utf-8")


def inspect_llm_runtime() -> dict[str, object]:
    modules = [
        "litert_lm",
        "google.ai.edge.litertlm",
        "litertlm",
        "ai_edge_litert",
        "ai_edge_litert.pywrap_genai_ops",
        "ai_edge_litert.interpreter",
        "litert_torch",
    ]
    module_status = {name: find_module(name) for name in modules}
    cli_path = shutil.which("litert-lm")
    return {
        "modules": module_status,
        "litert_lm_cli": cli_path,
        "external_command_configured": bool(os.environ.get("COUNSELING_LLM_COMMAND", "").strip()),
        "python_generation_runtime_available": bool(
            module_status["litert_lm"] or module_status["google.ai.edge.litertlm"] or module_status["litertlm"]
        ),
        "install_hint": "python -m pip install litert-lm-api",
        "cli_install_hint": "uv tool install litert-lm",
        "note": (
            "litert-lm-api exposes the litert_lm.Engine Python API for .litertlm generation. "
            "The litert-lm CLI can be used as a fallback when it is available on PATH."
        ),
    }

def normalize_role(role: str) -> str:
    value = str(role).strip().lower()
    if value in {"user", "나"}:
        return "user"
    if value in {"assistant", "model", "bot", "상담 챗봇"}:
        return "assistant"
    if value == "system":
        return "system"
    return "user"


def android_role(role: str) -> str:
    normalized = normalize_role(role)
    if normalized == "assistant":
        return "Assistant"
    if normalized == "system":
        return "System"
    return "User"


def memory_file() -> Path:
    return writable_app_dir() / "memories.json"


def load_memories() -> list[str]:
    path = memory_file()
    if not path.exists():
        return [
            "상담 응답은 진단이나 치료를 대신하지 않고, 감정 반영과 정리, 작은 다음 행동 선택을 돕는다.",
            "자살, 자해, 타해, 학대, 폭력, 응급 위험 신호가 있으면 안전 확인과 긴급 지원 안내를 우선한다.",
        ]
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return [str(item) for item in data if str(item).strip()]
    except Exception:
        return []


def save_memories(memories: list[str]) -> None:
    memory_file().write_text(json.dumps(memories, ensure_ascii=False, indent=2), encoding="utf-8")


def sessions_dir() -> Path:
    path = writable_app_dir() / "chat_sessions"
    path.mkdir(parents=True, exist_ok=True)
    return path


def active_session_file() -> Path:
    return writable_app_dir() / "active_chat_session_id.txt"


def create_session_id() -> str:
    return f"session_{int(time.time() * 1000)}"


def load_active_session_id() -> str | None:
    path = active_session_file()
    if not path.exists():
        return None
    value = path.read_text(encoding="utf-8").strip()
    return value or None


def save_active_session_id(session_id: str) -> None:
    active_session_file().write_text(session_id, encoding="utf-8")


def session_file(session_id: str) -> Path:
    safe_id = "".join(char if char.isalnum() or char in {"_", "-"} else "_" for char in session_id)
    return sessions_dir() / f"{safe_id}.json"


def derive_session_title(messages: list[ChatMessage]) -> str:
    first_user = next((m.content.strip() for m in messages if normalize_role(m.role) == "user" and m.content.strip()), "")
    return " ".join(first_user.split())[:28] if first_user else "새 상담"


def session_to_dict(session_id: str, messages: list[ChatMessage], memories: list[str]) -> dict[str, object]:
    return {
        "version": 3,
        "id": session_id,
        "title": derive_session_title(messages),
        "updatedAt": int(time.time() * 1000),
        "systemPrompt": "Counseling Desktop fallback prompt",
        "importantMemories": memories,
        "messages": [
            {
                "role": android_role(message.role),
                "content": message.content,
                "imagePath": None,
                "audioPath": None,
                "attachmentLabel": None,
            }
            for message in messages
        ],
    }


def parse_session(text: str, fallback_id: str | None = None) -> SavedSession:
    data = json.loads(text)
    messages: list[ChatMessage] = []
    for item in data.get("messages", []):
        if not isinstance(item, dict):
            continue
        content = str(item.get("content", "")).strip()
        if not content:
            continue
        messages.append(ChatMessage(normalize_role(str(item.get("role", "User"))), content))
    memories = [
        str(item).strip()
        for item in data.get("importantMemories", [])
        if str(item).strip()
    ]
    session_id = str(data.get("id") or fallback_id or create_session_id())
    title = str(data.get("title") or derive_session_title(messages))
    updated_at = int(data.get("updatedAt") or int(time.time() * 1000))
    return SavedSession(session_id, title, updated_at, messages, memories)


def save_session(session_id: str, messages: list[ChatMessage], memories: list[str]) -> None:
    if not messages and not memories:
        save_active_session_id(session_id)
        return
    target = session_file(session_id)
    partial = target.with_suffix(".json.partial")
    partial.write_text(
        json.dumps(session_to_dict(session_id, messages, memories), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    if target.exists():
        target.unlink()
    partial.rename(target)
    save_active_session_id(session_id)


def load_session(session_id: str) -> SavedSession | None:
    path = session_file(session_id)
    if not path.exists():
        return None
    try:
        return parse_session(path.read_text(encoding="utf-8"), fallback_id=session_id)
    except Exception:
        return None


def list_sessions() -> list[SavedSession]:
    sessions: list[SavedSession] = []
    for path in sessions_dir().glob("*.json"):
        try:
            session = parse_session(path.read_text(encoding="utf-8"), fallback_id=path.stem)
            sessions.append(session)
        except Exception:
            continue
    return sorted(sessions, key=lambda item: item.updated_at, reverse=True)


def format_timestamp(milliseconds: int) -> str:
    if milliseconds <= 0:
        return "-"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(milliseconds / 1000))


def main() -> None:
    if "--self-test" in sys.argv:
        run_self_test()
        return
    if "--self-test-voice" in sys.argv:
        run_voice_self_test()
        return
    if "--self-test-gallery" in sys.argv:
        run_gallery_self_test()
        return
    if "--self-test-llm" in sys.argv:
        run_llm_self_test()
        return
    if "--self-test-external-llm" in sys.argv:
        run_external_llm_self_test()
        return
    if "--self-test-session" in sys.argv:
        run_session_self_test()
        return
    if "--self-test-prompt" in sys.argv:
        run_prompt_self_test()
        return
    app = CounselingDesktopApp()
    app.mainloop()


def run_self_test() -> None:
    llm = LlmAdapter()
    voice = VoiceEmotionAnalyzer()
    checks = {
        "llm_model_exists": llm.model_path.exists(),
        "llm_model_info": llm.model_info,
        "llm_status": llm.status,
        "llm_runtime_diagnostics": llm.runtime_diagnostics,
        "voice_model_exists": voice.model_path.exists(),
        "voice_metadata_exists": voice.metadata_path.exists(),
        "voice_runtime_available": voice.available(),
        "pillow_available": Image is not None,
        "onnxruntime_available": ort is not None,
        "memory_dir": str(writable_app_dir()),
    }
    failures = [key for key, value in checks.items() if key.endswith("_exists") and not value]
    print(json.dumps(checks, ensure_ascii=False, indent=2))
    if failures:
        raise SystemExit(f"Self-test failed: {', '.join(failures)}")


def run_voice_self_test() -> None:
    test_path = Path.cwd() / ".counseling_desktop" / "self_test.wav"
    test_path.parent.mkdir(parents=True, exist_ok=True)
    sample_rate = VOICE_SAMPLE_RATE
    seconds = 1
    amplitude = 12_000
    with wave.open(str(test_path), "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(sample_rate)
        frames = bytearray()
        for index in range(sample_rate * seconds):
            sample = int(amplitude * math.sin(2 * math.pi * 440 * index / sample_rate))
            frames.extend(sample.to_bytes(2, "little", signed=True))
        writer.writeframes(bytes(frames))
    result = VoiceEmotionAnalyzer().analyze(test_path)
    output = {
        "audio": str(test_path),
        "label": result.label,
        "confidence": result.confidence,
        "probabilities": result.probabilities,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))


def run_llm_self_test() -> None:
    llm = LlmAdapter()
    streamed_parts: list[str] = []
    sample = llm.generate(
        messages=[ChatMessage("user", "요즘 잠을 잘 못 자고 불안해.")],
        gallery_context=GallerySummary(
            image_count=2,
            dark_count=1,
            bright_count=0,
            warm_count=0,
            cool_count=1,
            average_brightness=92.0,
            image_paths=[],
        ).to_context(),
        voice_context=build_voice_prompt_context(
            "Fearful",
            0.62,
            {"Happy": 0.04, "Sad": 0.21, "Angry": 0.06, "Fearful": 0.62, "Neutral": 0.07},
        ),
        memories=["사용자는 부담이 낮은 안정화 방법을 선호한다."],
        stream_callback=streamed_parts.append,
    )
    output = {
        "llm_model_exists": llm.model_path.exists(),
        "llm_model_info": llm.model_info,
        "llm_status": llm.status,
        "runtime_diagnostics": llm.runtime_diagnostics,
        "stream_piece_count": len(streamed_parts),
        "stream_text_length": len("".join(streamed_parts)),
        "sample_reply": sample,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))


def run_external_llm_self_test() -> None:
    command = (
        f'"{sys.executable}" -c "import os,sys; '
        'prompt=sys.stdin.read(); '
        'print(\'external-ok:\' + os.path.basename(os.environ.get(\'COUNSELING_LLM_MODEL\', \'\')) + \':\' + str(len(prompt)))"'
    )
    prompt = build_prompt_preview(
        user_text="외부 런타임 테스트",
        gallery_context="갤러리 테스트 맥락",
        voice_context="음성 테스트 맥락",
        memories=["외부 명령 테스트 기억"],
    )
    result = run_external_llm_command(command, resource_path("LLMmodels", "models", "gemma-4-E4B-it.litertlm"), prompt)
    output = {
        "command": command,
        "result": result,
        "prompt_length": len(prompt),
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    if not result or not result.startswith("external-ok:"):
        raise SystemExit("External LLM self-test failed.")

def run_gallery_self_test() -> None:
    if Image is None:
        raise SystemExit("Pillow is not available.")
    root = writable_app_dir() / "gallery_self_test"
    nested = root / "nested"
    nested.mkdir(parents=True, exist_ok=True)
    samples = [
        (root / "dark.jpg", (20, 20, 24)),
        (root / "bright.jpg", (230, 225, 215)),
        (nested / "cool.png", (40, 80, 180)),
    ]
    for path, color in samples:
        image = Image.new("RGB", (48, 48), color)
        image.save(path)
    paths = collect_gallery_images(root)
    summary = summarize_gallery(paths)
    output = {
        "folder": str(root),
        "collected": [str(path) for path in paths],
        "image_count": summary.image_count,
        "dark_count": summary.dark_count,
        "bright_count": summary.bright_count,
        "cool_count": summary.cool_count,
        "average_brightness": summary.average_brightness,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    if summary.image_count != 3:
        raise SystemExit("Gallery self-test failed: expected 3 images.")


def run_session_self_test() -> None:
    session_id = create_session_id()
    messages = [
        ChatMessage("user", "요즘 잠을 잘 못 자."),
        ChatMessage("assistant", "잠을 잘 못 자서 꽤 지쳐 있을 수 있겠어요."),
    ]
    memories = ["사용자는 짧은 안정화 연습을 선호한다."]
    save_session(session_id, messages, memories)
    loaded = load_session(session_id)
    if loaded is None:
        raise SystemExit("Session self-test failed: saved session was not loaded.")
    export_text = json.dumps(session_to_dict(session_id, messages, memories), ensure_ascii=False, indent=2)
    imported = parse_session(export_text, fallback_id=create_session_id())
    empty_memory_import = parse_session(
        json.dumps(
            {
                "version": 3,
                "id": "empty_memory_session",
                "title": "빈 기억 세션",
                "updatedAt": int(time.time() * 1000),
                "importantMemories": [],
                "messages": [{"role": "User", "content": "기억 없는 세션 테스트"}],
            },
            ensure_ascii=False,
        )
    )
    output = {
        "session_id": session_id,
        "session_file": str(session_file(session_id)),
        "loaded_title": loaded.title,
        "loaded_message_count": len(loaded.messages),
        "loaded_memory_count": len(loaded.memories),
        "imported_message_count": len(imported.messages),
        "empty_memory_import_count": len(empty_memory_import.memories),
        "android_roles": [android_role(message.role) for message in imported.messages],
        "known_sessions": len(list_sessions()),
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    if len(loaded.messages) != 2 or loaded.memories != memories:
        raise SystemExit("Session self-test failed: round-trip mismatch.")
    if empty_memory_import.memories:
        raise SystemExit("Session self-test failed: empty memories should stay empty.")


def run_prompt_self_test() -> None:
    prompt = build_prompt_preview(
        user_text="요즘 너무 불안해서 잠을 못 자.",
        gallery_context="선택 이미지 3장 기준 보조 요약입니다.",
        voice_context="음성 감정 분석 보조 결과: 불안 또는 두려움(Fearful), confidence=70.0%.",
        memories=["사용자는 짧은 호흡 연습을 선호한다."],
    )
    required = ["[상담 보조자 지침]", "[사용자 메시지]", "[중요 기억]", "[갤러리 분석 맥락]", "[음성 분석 맥락]"]
    output = {
        "length": len(prompt),
        "contains": {key: (key in prompt) for key in required},
        "preview": prompt,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    missing = [key for key in required if key not in prompt]
    if missing:
        raise SystemExit(f"Prompt self-test failed: {', '.join(missing)}")


if __name__ == "__main__":
    main()
