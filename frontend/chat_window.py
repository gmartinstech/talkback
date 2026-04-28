import logging
from pathlib import Path

from PyQt6.QtCore import QObject, pyqtSignal, pyqtSlot, Qt
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QScrollArea,
    QLabel, QLineEdit, QPushButton, QComboBox, QFrame, QSizePolicy
)


class ChatBridge(QObject):
    messageSent = pyqtSignal(str)
    modelSelectionChanged = pyqtSignal(str)
    openSettingsRequested = pyqtSignal()
    openFileRequested = pyqtSignal(str)

    @pyqtSlot(str)
    def sendMessage(self, text: str):
        self.messageSent.emit(text)

    @pyqtSlot(str)
    def modelChanged(self, model: str):
        self.modelSelectionChanged.emit(model)

    @pyqtSlot()
    def openSettings(self):
        self.openSettingsRequested.emit()

    @pyqtSlot(str)
    def openFile(self, file_path: str):
        self.openFileRequested.emit(file_path)


class MessageBubble(QLabel):
    clickedFile = pyqtSignal(str)

    def __init__(self, text: str, role: str, parent=None):
        super().__init__(parent)
        self.setWordWrap(True)
        self.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)
        self.setMaximumWidth(320)
        self._role = role
        self._raw_text = text
        self._update_style()
        self.setText(self._format_text(text))

    def _update_style(self):
        if self._role == "user":
            self.setStyleSheet("""
                QLabel {
                    background: #002952;
                    color: #F1F5F9;
                    border-radius: 12px;
                    border-bottom-right-radius: 4px;
                    padding: 10px 14px;
                    font-size: 14px;
                    line-height: 1.5;
                }
            """)
            self.setAlignment(Qt.AlignmentFlag.AlignRight)
        else:
            self.setStyleSheet("""
                QLabel {
                    background: #0F172A;
                    color: #F1F5F9;
                    border-radius: 12px;
                    border-bottom-left-radius: 4px;
                    padding: 10px 14px;
                    font-size: 14px;
                    line-height: 1.5;
                }
            """)
            self.setAlignment(Qt.AlignmentFlag.AlignLeft)

    def _format_text(self, text: str) -> str:
        import re
        # Escape HTML
        text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        # Convert file references to styled spans
        text = re.sub(
            r'\b([\w\-/]+\.(?:py|js|ts|jsx|tsx|css|scss|html|json|yml|yaml|md|rs|go|java|kt|c|cpp|h|hpp|sh|bat|ps1|toml|ini|cfg|xml|sql|gradle|podfile))\b',
            r'<span style="color:#F5CC00;text-decoration:underline;cursor:pointer;">\1</span>',
            text, flags=re.IGNORECASE
        )
        # Convert newlines to <br>
        text = text.replace("\n", "<br>")
        return text

    def append_text(self, text: str):
        self._raw_text += text
        self.setText(self._format_text(self._raw_text))

    def mousePressEvent(self, event):
        # Check if click is on a file-link styled span
        # QLabel doesn't give us hit-testing on styled spans easily,
        # so we rely on the parent ChatWindow to handle link clicks via text parsing
        super().mousePressEvent(event)


class ChatWindow(QWidget):
    sendMessage = pyqtSignal(str)
    modelChanged = pyqtSignal(str)
    openSettings = pyqtSignal()
    openFile = pyqtSignal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setFixedSize(380, 600)

        self._bridge = ChatBridge()
        self._bridge.messageSent.connect(self.sendMessage)
        self._bridge.modelSelectionChanged.connect(self.modelChanged)
        self._bridge.openSettingsRequested.connect(self.openSettings)
        self._bridge.openFileRequested.connect(self.openFile)

        self._current_bubble = None
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # ── Header ──
        header = QWidget()
        header.setFixedHeight(48)
        header.setStyleSheet("""
            QWidget {
                background: #002952;
                border-bottom: 1px solid #F5CC00;
            }
            QLabel {
                color: #F1F5F9;
                font-size: 16px;
                font-weight: 600;
            }
            QComboBox {
                background: #0F172A;
                color: #F1F5F9;
                border: 1px solid #1E293B;
                border-radius: 6px;
                padding: 4px 8px;
                font-size: 12px;
            }
            QComboBox:focus { border-color: #F5CC00; }
            QComboBox::drop-down { border: none; width: 20px; }
            QPushButton {
                background: transparent;
                border: none;
                color: #94A3B8;
                font-size: 16px;
                padding: 4px;
            }
            QPushButton:hover { color: #F5CC00; }
        """)
        hlayout = QHBoxLayout(header)
        hlayout.setContentsMargins(12, 0, 12, 0)

        hlayout.addWidget(QLabel("TalkBack Reviewer"))
        hlayout.addStretch()

        self._model_select = QComboBox()
        self._model_select.addItem("Loading...")
        self._model_select.currentTextChanged.connect(self._bridge.modelChanged)
        hlayout.addWidget(self._model_select)

        settings_btn = QPushButton("⚙")
        settings_btn.setFixedSize(28, 28)
        settings_btn.clicked.connect(self._bridge.openSettings)
        hlayout.addWidget(settings_btn)
        layout.addWidget(header)

        # ── Scroll area for messages ──
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setStyleSheet("QScrollArea { border: none; background: #0C1222; }")
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)

        self._messages_widget = QWidget()
        self._messages_widget.setStyleSheet("background: #0C1222;")
        self._messages_layout = QVBoxLayout(self._messages_widget)
        self._messages_layout.setAlignment(Qt.AlignmentFlag.AlignTop)
        self._messages_layout.setSpacing(8)
        self._messages_layout.setContentsMargins(12, 12, 12, 12)
        self._messages_layout.addStretch()

        scroll.setWidget(self._messages_widget)
        layout.addWidget(scroll, 1)

        # ── Typing indicator ──
        self._typing = QLabel("● ● ●")
        self._typing.setStyleSheet("""
            QLabel {
                background: #0F172A;
                color: #94A3B8;
                padding: 8px 14px;
                border-radius: 12px;
                border-bottom-left-radius: 4px;
                margin: 0 12px 8px;
            }
        """)
        self._typing.hide()
        layout.addWidget(self._typing, alignment=Qt.AlignmentFlag.AlignLeft)

        # ── Input area ──
        input_area = QWidget()
        input_area.setStyleSheet("""
            QWidget {
                background: #0F172A;
                border-top: 1px solid #1E293B;
            }
            QLineEdit {
                background: #002952;
                color: #F1F5F9;
                border: 1px solid #1E293B;
                border-radius: 8px;
                padding: 8px 12px;
                font-size: 14px;
            }
            QLineEdit:focus { border-color: #F5CC00; }
            QPushButton {
                background: #003566;
                color: #F5CC00;
                border: none;
                border-radius: 8px;
                padding: 8px 14px;
                font-size: 14px;
                font-weight: 600;
            }
            QPushButton:hover { background: #004080; }
        """)
        ilayout = QHBoxLayout(input_area)
        ilayout.setContentsMargins(10, 10, 10, 10)
        ilayout.setSpacing(8)

        self._input = QLineEdit()
        self._input.setPlaceholderText("Paste a PR link or type a message...")
        self._input.returnPressed.connect(self._send)
        ilayout.addWidget(self._input, 1)

        send_btn = QPushButton("➤")
        send_btn.setFixedSize(36, 36)
        send_btn.clicked.connect(self._send)
        ilayout.addWidget(send_btn)

        layout.addWidget(input_area)

    def _send(self):
        text = self._input.text().strip()
        if not text:
            return
        self._input.clear()
        self.append_user_message(text)
        self.sendMessage.emit(text)

    def _add_bubble(self, text: str, role: str) -> MessageBubble:
        bubble = MessageBubble(text, role)
        wrapper = QWidget()
        wlayout = QHBoxLayout(wrapper)
        wlayout.setContentsMargins(0, 0, 0, 0)
        if role == "user":
            wlayout.addStretch()
            wlayout.addWidget(bubble)
        else:
            wlayout.addWidget(bubble)
            wlayout.addStretch()
        # Insert before the stretch spacer
        idx = self._messages_layout.count() - 1
        self._messages_layout.insertWidget(idx, wrapper)
        return bubble

    def append_user_message(self, text: str):
        self._current_bubble = None
        self._add_bubble(text, "user")

    def append_assistant_chunk(self, text: str):
        if self._current_bubble is None:
            self._current_bubble = self._add_bubble(text, "assistant")
        else:
            self._current_bubble.append_text(text)

    def finalize_assistant_message(self):
        self._current_bubble = None

    def set_typing(self, show: bool):
        self._typing.setVisible(show)

    def set_models(self, models: list):
        current = self._model_select.currentText()
        self._model_select.clear()
        for m in models:
            self._model_select.addItem(m)
        if current in models:
            self._model_select.setCurrentText(current)
        elif models:
            self._model_select.setCurrentText(models[0])
