from __future__ import annotations

import json
import logging
import time
from abc import ABC, abstractmethod
from typing import Any

import httpx

logger = logging.getLogger("sentinelvoice.ml.llm_gateway")


class LlmProvider(ABC):
    name: str = "base"

    @abstractmethod
    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int,
        temperature: float,
        timeout_ms: int,
    ) -> tuple[str, dict[str, int]]:
        """Return (raw_text, usage_token_counts)."""


class MockProvider(LlmProvider):
    name = "mock"

    def __init__(self, schema: dict[str, Any] | None = None) -> None:
        self.model = "mock"
        self._schema = schema or {"type": "object"}

    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int,
        temperature: float,
        timeout_ms: int,
    ) -> tuple[str, dict[str, int]]:
        payload = _minimal_from_schema(self._schema)
        return json.dumps(payload), {"prompt_tokens": 0, "completion_tokens": 0}


def _minimal_from_schema(schema: dict[str, Any]) -> Any:
    """Build a deterministic instance that usually validates against simple schemas."""
    if not isinstance(schema, dict):
        return {"provider": "mock"}
    if "const" in schema:
        return schema["const"]
    if "enum" in schema and schema["enum"]:
        return schema["enum"][0]
    t = schema.get("type")
    if isinstance(t, list):
        t = t[0] if t else "object"
    if t == "object" or (t is None and "properties" in schema):
        props = schema.get("properties") or {}
        required = set(schema.get("required") or [])
        out: dict[str, Any] = {}
        keys = required or set(props.keys())
        if not keys and props:
            keys = set(props.keys())
        for key in keys:
            sub = props.get(key) or {}
            out[key] = _minimal_from_schema(sub if isinstance(sub, dict) else {})
        if not out:
            # Label mock responses when schema is unconstrained
            out = {"provider": "mock", "ok": True, "summary": "Mock LLM response for offline demo"}
        elif "provider" in props:
            out["provider"] = "mock"
        return out
    if t == "array":
        items = schema.get("items") or {}
        return [_minimal_from_schema(items if isinstance(items, dict) else {})]
    if t == "string":
        return "mock"
    if t == "integer":
        return 0
    if t == "number":
        return 0.0
    if t == "boolean":
        return False
    return {"provider": "mock"}


class OllamaProvider(LlmProvider):
    name = "ollama"

    def __init__(self, base_url: str, model: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model

    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int,
        temperature: float,
        timeout_ms: int,
    ) -> tuple[str, dict[str, int]]:
        timeout = httpx.Timeout(timeout_ms / 1000.0)
        body = {
            "model": self.model,
            "stream": False,
            "format": "json",
            "options": {"temperature": temperature, "num_predict": max_tokens},
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(f"{self.base_url}/api/chat", json=body)
            resp.raise_for_status()
            data = resp.json()
        text = (data.get("message") or {}).get("content") or ""
        usage = {
            "prompt_tokens": int(data.get("prompt_eval_count") or 0),
            "completion_tokens": int(data.get("eval_count") or 0),
        }
        return text, usage


class OpenAICompatibleProvider(LlmProvider):
    name = "openai_compatible"

    def __init__(self, base_url: str, api_key: str, model: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model

    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int,
        temperature: float,
        timeout_ms: int,
    ) -> tuple[str, dict[str, int]]:
        timeout = httpx.Timeout(timeout_ms / 1000.0)
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        body = {
            "model": self.model,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(
                f"{self.base_url}/v1/chat/completions",
                headers=headers,
                json=body,
            )
            resp.raise_for_status()
            data = resp.json()
        text = (((data.get("choices") or [{}])[0].get("message") or {}).get("content")) or ""
        usage_raw = data.get("usage") or {}
        usage = {
            "prompt_tokens": int(usage_raw.get("prompt_tokens") or 0),
            "completion_tokens": int(usage_raw.get("completion_tokens") or 0),
        }
        return text, usage


async def probe_ollama(base_url: str, model: str) -> bool:
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            resp = await client.get(f"{base_url.rstrip('/')}/api/tags")
            if resp.status_code != 200:
                return False
            names = [m.get("name") for m in (resp.json().get("models") or [])]
            if not names:
                # Daemon up but no models pulled yet — still "reachable" for health;
                # complete() will fail and gateway falls through to mock on next select.
                return True
            return any(model in (n or "") for n in names)
    except Exception:
        return False
