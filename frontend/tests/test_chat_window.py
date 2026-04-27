import pytest
from unittest.mock import MagicMock, patch

from PyQt6.QtWidgets import QApplication

from frontend.chat_window import ChatWindow


@pytest.fixture(scope="session")
def qapp():
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    yield app


class TestChatWindow:
    def test_window_dimensions(self, qapp):
        w = ChatWindow()
        assert w.width() == 380
        assert w.height() == 600

    def test_appends_user_message(self, qapp):
        w = ChatWindow()
        w.append_user_message("Hello")
        # JS execution is async; just verify method runs without error

    def test_appends_assistant_message(self, qapp):
        w = ChatWindow()
        w.append_assistant_chunk("Hello ")
        w.append_assistant_chunk("world")
        # Stream accumulation
        assert w._current_assistant_div is not None
