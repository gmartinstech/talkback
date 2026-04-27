import json
import pytest
from unittest.mock import patch, MagicMock

from frontend.services.ollama import OllamaClient

class TestOllamaClient:
    def test_list_models_returns_gemma4_first(self):
        mock_response = MagicMock()
        mock_response.json.return_value = {
            "models": [
                {"name": "llama3:8b"},
                {"name": "gemma4:4b"},
                {"name": "gemma4:12b"},
            ]
        }
        mock_response.raise_for_status = MagicMock()

        with patch("requests.get", return_value=mock_response):
            client = OllamaClient("http://localhost:11434")
            models = client.list_models()

        assert models[0]["name"] == "gemma4:4b"
        assert len(models) == 2

    def test_list_models_fallback_when_no_gemma4(self):
        mock_response = MagicMock()
        mock_response.json.return_value = {
            "models": [
                {"name": "llama3:8b"},
                {"name": "mistral:7b"},
            ]
        }
        mock_response.raise_for_status = MagicMock()

        with patch("requests.get", return_value=mock_response):
            client = OllamaClient("http://localhost:11434")
            models = client.list_models()

        assert models[0]["name"] == "llama3:8b"
        assert len(models) == 2

    def test_chat_stream_yields_chunks(self):
        mock_response = MagicMock()
        mock_response.iter_lines.return_value = [
            json.dumps({"message": {"content": "Hello"}}),
            json.dumps({"message": {"content": " world"}}),
            json.dumps({"done": True}),
        ]
        mock_response.raise_for_status = MagicMock()

        with patch("requests.post", return_value=mock_response):
            client = OllamaClient("http://localhost:11434")
            chunks = list(client.chat_stream("gemma4:4b", "Say hello", []))

        assert chunks == ["Hello", " world"]
