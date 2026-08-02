from PySide6.QtCore import Qt
from PySide6.QtGui import QCloseEvent
from PySide6.QtTextToSpeech import QTextToSpeech
from PySide6.QtWidgets import (
    QCheckBox,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QSpacerItem,
    QVBoxLayout,
    QWidget,
)

from .api import ApiWorker


class MessageBubble(QFrame):
    def __init__(self, role: str, text: str):
        super().__init__()
        self.setObjectName("userBubble" if role == "user" else "assistantBubble")
        layout = QVBoxLayout(self)
        name = QLabel("나" if role == "user" else "온마음")
        name.setObjectName("bubbleName")
        body = QLabel(text)
        body.setWordWrap(True)
        body.setTextInteractionFlags(Qt.TextSelectableByMouse)
        layout.addWidget(name)
        layout.addWidget(body)
        self.setMaximumWidth(680)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("온마음 홈")
        self.resize(1100, 760)
        self.session_id: str | None = None
        self.recording = False
        self.workers: set[ApiWorker] = set()
        self.tts = QTextToSpeech(self)
        self._build_ui()
        self._apply_style()
        self.check_connection()

    def _build_ui(self) -> None:
        root = QWidget()
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)
        outer.setContentsMargins(28, 22, 28, 24)
        outer.setSpacing(16)

        header = QHBoxLayout()
        title_box = QVBoxLayout()
        title = QLabel("온마음 홈")
        title.setObjectName("title")
        subtitle = QLabel("편안하게 말씀해 주세요. Jetson 안에서 음성을 처리합니다.")
        subtitle.setObjectName("subtitle")
        title_box.addWidget(title)
        title_box.addWidget(subtitle)
        header.addLayout(title_box)
        header.addStretch()

        self.status = QLabel("● 연결 확인 중")
        self.status.setObjectName("statusPending")
        header.addWidget(self.status)
        outer.addLayout(header)

        connection = QFrame()
        connection.setObjectName("connectionPanel")
        connection_layout = QHBoxLayout(connection)
        connection_layout.addWidget(QLabel("Jetson 주소"))
        self.url = QLineEdit("http://10.61.230.134:8000")
        self.url.setMinimumWidth(320)
        connection_layout.addWidget(self.url)
        self.connect_button = QPushButton("연결 확인")
        self.connect_button.clicked.connect(self.check_connection)
        connection_layout.addWidget(self.connect_button)
        self.auto_tts = QCheckBox("답변 음성으로 듣기")
        self.auto_tts.setChecked(True)
        connection_layout.addWidget(self.auto_tts)
        connection_layout.addStretch()
        outer.addWidget(connection)

        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.NoFrame)
        self.message_host = QWidget()
        self.messages = QVBoxLayout(self.message_host)
        self.messages.setAlignment(Qt.AlignTop)
        self.messages.setSpacing(12)
        self.messages.addWidget(
            MessageBubble(
                "assistant",
                "안녕하세요. 오늘은 어떤 이야기를 나누고 싶으세요?",
            ),
            alignment=Qt.AlignLeft,
        )
        self.scroll.setWidget(self.message_host)
        outer.addWidget(self.scroll, 1)

        controls = QFrame()
        controls.setObjectName("controls")
        control_layout = QVBoxLayout(controls)
        voice_row = QHBoxLayout()
        self.voice_button = QPushButton("🎙  말하기 시작")
        self.voice_button.setObjectName("voiceButton")
        self.voice_button.setMinimumHeight(58)
        self.voice_button.clicked.connect(self.toggle_recording)
        self.voice_button.setEnabled(False)
        voice_row.addWidget(self.voice_button, 2)
        self.stop_tts_button = QPushButton("음성 멈춤")
        self.stop_tts_button.clicked.connect(self.tts.stop)
        self.stop_tts_button.setMinimumHeight(58)
        voice_row.addWidget(self.stop_tts_button, 1)
        control_layout.addLayout(voice_row)

        text_row = QHBoxLayout()
        self.input = QLineEdit()
        self.input.setPlaceholderText("직접 입력할 수도 있습니다.")
        self.input.returnPressed.connect(self.send_text)
        self.input.setMinimumHeight(48)
        text_row.addWidget(self.input, 1)
        self.send_button = QPushButton("보내기")
        self.send_button.clicked.connect(self.send_text)
        self.send_button.setMinimumHeight(48)
        self.send_button.setEnabled(False)
        text_row.addWidget(self.send_button)
        control_layout.addLayout(text_row)
        outer.addWidget(controls)

    def _apply_style(self) -> None:
        self.setStyleSheet(
            """
            QMainWindow, QWidget { background: #F5F3EE; color: #26312D;
                font-family: "Malgun Gothic"; font-size: 17px; }
            QLabel#title { font-size: 30px; font-weight: 700; color: #183C34; }
            QLabel#subtitle { color: #66736E; font-size: 15px; }
            QLabel#statusPending { color: #A66A00; font-weight: 700; }
            QLabel#statusOnline { color: #177245; font-weight: 700; }
            QLabel#statusOffline { color: #B13A32; font-weight: 700; }
            QFrame#connectionPanel, QFrame#controls { background: white;
                border: 1px solid #DDD9D0; border-radius: 14px; }
            QFrame#userBubble { background: #D9EEE6; border-radius: 15px; }
            QFrame#assistantBubble { background: white; border: 1px solid #E2DED5;
                border-radius: 15px; }
            QLabel#bubbleName { color: #27735E; font-size: 14px; font-weight: 700; }
            QLineEdit { background: white; border: 1px solid #CBC7BE;
                border-radius: 10px; padding: 9px 12px; }
            QPushButton { background: #E9E6DF; border: none; border-radius: 10px;
                padding: 10px 18px; font-weight: 700; }
            QPushButton:hover { background: #DEDAD1; }
            QPushButton:disabled { color: #9B9B9B; background: #ECEAE6; }
            QPushButton#voiceButton { background: #1F6B57; color: white;
                font-size: 20px; }
            QPushButton#voiceButton:hover { background: #195947; }
            """
        )

    def check_connection(self) -> None:
        self._request("health", "GET", "/health", timeout=8)

    def create_session(self) -> None:
        self._request("session", "POST", "/sessions", {})

    def toggle_recording(self) -> None:
        self.tts.stop()
        if not self.session_id:
            return
        if self.recording:
            self.voice_button.setText("음성을 처리하고 있어요…")
            self.voice_button.setEnabled(False)
            self._request(
                "voice_stop",
                "POST",
                "/voice/stop",
                {"session_id": self.session_id},
                timeout=180,
            )
        else:
            self._request(
                "voice_start",
                "POST",
                "/voice/start",
                {"session_id": self.session_id},
            )

    def send_text(self) -> None:
        text = self.input.text().strip()
        if not text or not self.session_id:
            return
        self.tts.stop()
        self.input.clear()
        self._add_message("user", text)
        self._set_busy(True)
        self._request(
            "chat",
            "POST",
            "/chat",
            {"session_id": self.session_id, "text": text},
            timeout=120,
        )

    def _request(
        self,
        operation: str,
        method: str,
        path: str,
        payload: dict | None = None,
        timeout: float = 120,
    ) -> None:
        worker = ApiWorker(operation, self.url.text(), method, path, payload, timeout)
        self.workers.add(worker)
        worker.succeeded.connect(self._on_success)
        worker.failed.connect(self._on_failure)
        worker.finished.connect(lambda: self._release_worker(worker))
        worker.start()

    def _release_worker(self, worker: ApiWorker) -> None:
        self.workers.discard(worker)
        worker.deleteLater()

    def _on_success(self, operation: str, data: object) -> None:
        payload = dict(data)
        if operation == "health":
            healthy = payload.get("llm") and payload.get("whisper_model")
            self.status.setText("● Jetson 연결됨" if healthy else "● 일부 기능 점검 필요")
            self.status.setObjectName("statusOnline" if healthy else "statusPending")
            self.status.style().unpolish(self.status)
            self.status.style().polish(self.status)
            if not self.session_id:
                self.create_session()
        elif operation == "session":
            self.session_id = payload["session_id"]
            self.voice_button.setEnabled(True)
            self.send_button.setEnabled(True)
        elif operation == "voice_start":
            self.recording = True
            self.voice_button.setText("■  말하기 완료")
            self.status.setText("● Jetson 마이크로 듣고 있어요")
        elif operation == "voice_stop":
            self.recording = False
            self.voice_button.setText("🎙  말하기 시작")
            self.voice_button.setEnabled(True)
            self.status.setText("● Jetson 연결됨")
            self._add_message("user", payload["transcript"])
            self._show_answer(payload["assistant"])
        elif operation == "chat":
            self._set_busy(False)
            self._show_answer(payload["assistant"])

    def _on_failure(self, operation: str, message: str) -> None:
        self.recording = False
        self._set_busy(False)
        self.voice_button.setText("🎙  말하기 시작")
        self.voice_button.setEnabled(self.session_id is not None)
        if operation == "health":
            self.status.setText("● Jetson 연결 안 됨")
            self.status.setObjectName("statusOffline")
            self.status.style().unpolish(self.status)
            self.status.style().polish(self.status)
        QMessageBox.warning(self, "온마음 홈", message)

    def _show_answer(self, answer: str) -> None:
        self._add_message("assistant", answer)
        if self.auto_tts.isChecked():
            self.tts.say(answer)

    def _add_message(self, role: str, text: str) -> None:
        bubble = MessageBubble(role, text)
        self.messages.addWidget(
            bubble,
            alignment=Qt.AlignRight if role == "user" else Qt.AlignLeft,
        )
        self.scroll.verticalScrollBar().setValue(self.scroll.verticalScrollBar().maximum())

    def _set_busy(self, busy: bool) -> None:
        self.input.setEnabled(not busy)
        self.send_button.setEnabled(not busy and self.session_id is not None)
        self.voice_button.setEnabled(not busy and self.session_id is not None)

    def closeEvent(self, event: QCloseEvent) -> None:
        self.tts.stop()
        event.accept()

