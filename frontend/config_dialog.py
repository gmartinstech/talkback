# frontend/config_dialog.py
import json
from pathlib import Path

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout,
    QLabel, QLineEdit, QComboBox,
    QPushButton, QFormLayout, QMessageBox
)


class ConfigDialog(QDialog):
    def __init__(self, parent=None, config_path: Path = None):
        super().__init__(parent)
        self.setWindowTitle("Settings")
        self.setFixedSize(400, 280)
        self.setStyleSheet("""
            QDialog {
                background: #0C1222;
                border: 1px solid #334155;
                border-radius: 12px;
            }
            QLabel {
                color: #94A3B8;
                font-size: 13px;
            }
            QLineEdit, QComboBox {
                background: #001D3D;
                color: #F1F5F9;
                border: 1px solid #334155;
                border-radius: 8px;
                padding: 8px 12px;
                font-size: 14px;
            }
            QLineEdit:focus, QComboBox:focus {
                border-color: #F5CC00;
                outline: none;
            }
            QComboBox::drop-down {
                border: none;
                width: 24px;
            }
            QComboBox QAbstractItemView {
                background: #0F172A;
                color: #F1F5F9;
                border: 1px solid #334155;
                selection-background-color: rgba(245, 204, 0, 0.2);
            }
            QPushButton {
                background: #003566;
                color: #F5CC00;
                border: none;
                border-radius: 8px;
                padding: 10px 20px;
                font-size: 14px;
                font-weight: 600;
            }
            QPushButton:hover {
                background: #004080;
            }
        """)

        self._config_path = config_path or Path.home() / ".claude" / "talkback" / "config.json"
        self._config = self._load_config()

        self._setup_ui()
        self._populate_values()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 24, 24, 24)
        layout.setSpacing(16)

        title = QLabel("TalkBack Settings")
        title.setStyleSheet("font-size: 18px; font-weight: 700; color: #F1F5F9;")
        layout.addWidget(title)

        form = QFormLayout()
        form.setSpacing(12)

        self._ollama_url = QLineEdit()
        form.addRow("Ollama URL:", self._ollama_url)

        self._model_select = QComboBox()
        self._model_select.setEditable(True)
        form.addRow("Model:", self._model_select)

        self._tts_select = QComboBox()
        self._tts_select.addItems(["qwen", "kokoro", "edge", "sapi"])
        form.addRow("TTS Engine:", self._tts_select)

        layout.addLayout(form)
        layout.addStretch()

        btn_row = QHBoxLayout()
        btn_row.addStretch()

        cancel = QPushButton("Cancel")
        cancel.setStyleSheet("background: transparent; color: #94A3B8;")
        cancel.clicked.connect(self.reject)
        btn_row.addWidget(cancel)

        save = QPushButton("Save")
        save.clicked.connect(self._save)
        btn_row.addWidget(save)

        layout.addLayout(btn_row)

    def _load_config(self) -> dict:
        if self._config_path.exists():
            try:
                return json.loads(self._config_path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, IOError):
                pass
        return {
            "ollama_url": "http://localhost:11434",
            "ollama_model": "gemma4:4b",
            "tts_engine": "qwen",
        }

    def _populate_values(self):
        self._ollama_url.setText(self._config.get("ollama_url", "http://localhost:11434"))
        self._tts_select.setCurrentText(self._config.get("tts_engine", "qwen"))

        current_model = self._config.get("ollama_model", "gemma4:4b")
        self._model_select.addItem(current_model)
        self._model_select.setCurrentText(current_model)

    def set_available_models(self, models: list):
        self._model_select.clear()
        for m in models:
            self._model_select.addItem(m)
        current = self._config.get("ollama_model", "")
        if current:
            self._model_select.setCurrentText(current)

    def _save(self):
        self._config["ollama_url"] = self._ollama_url.text().strip()
        self._config["ollama_model"] = self._model_select.currentText()
        self._config["tts_engine"] = self._tts_select.currentText()

        try:
            self._config_path.parent.mkdir(parents=True, exist_ok=True)
            self._config_path.write_text(
                json.dumps(self._config, indent=2), encoding="utf-8"
            )
        except IOError as e:
            QMessageBox.critical(self, "Error", f"Failed to save config: {e}")
            return

        self.accept()

    def get_config(self) -> dict:
        return self._config.copy()
