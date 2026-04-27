import json
import requests
from typing import Generator, List, Dict, Any


class OllamaClient:
    def __init__(self, base_url: str = "http://localhost:11434"):
        self.base_url = base_url.rstrip("/")

    def list_models(self) -> List[Dict[str, Any]]:
        resp = requests.get(f"{self.base_url}/api/tags", timeout=10)
        resp.raise_for_status()
        data = resp.json()
        models = data.get("models") or []

        gemma_models = [m for m in models if "gemma" in m.get("name", "").lower()]
        if gemma_models:
            return gemma_models
        return models

    def chat_stream(
        self, model: str, message: str, history: List[Dict[str, str]]
    ) -> Generator[str, None, None]:
        messages = history + [{"role": "user", "content": message}]
        payload = {"model": model, "messages": messages, "stream": True}

        resp = requests.post(
            f"{self.base_url}/api/chat",
            json=payload,
            stream=True,
            timeout=120,
        )
        resp.raise_for_status()

        for line in resp.iter_lines():
            if not line:
                continue
            try:
                data = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "message" in data and "content" in data["message"]:
                yield data["message"]["content"]
            if data.get("done"):
                break
