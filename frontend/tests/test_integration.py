# frontend/tests/test_integration.py
import json
from unittest.mock import patch, MagicMock

import pytest

from PyQt6.QtWidgets import QApplication

from frontend.app import TalkBackApp
from frontend.services.github import GitHubClient
from frontend.services.ollama import OllamaClient


@pytest.fixture(scope="session")
def qapp():
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    yield app


class TestIntegration:
    def test_github_client_parses_pr_url(self):
        client = GitHubClient()
        result = client.parse_pr_url("https://github.com/gmartinstech/talkback/pull/42")
        assert result == ("gmartinstech", "talkback", "42")

    def test_ollama_client_filters_gemma4(self):
        with patch("frontend.services.ollama.requests.get") as mock_get:
            mock_get.return_value = MagicMock(
                json=lambda: {
                    "models": [
                        {"name": "llama3:8b"},
                        {"name": "gemma4:4b"},
                    ]
                },
                raise_for_status=MagicMock(),
            )
            client = OllamaClient()
            models = client.list_models()
            assert len(models) == 1
            assert models[0]["name"] == "gemma4:4b"
