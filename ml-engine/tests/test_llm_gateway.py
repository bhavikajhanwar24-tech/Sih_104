"""Tests for F5 LLM gateway (schema, mock labelling, backpressure)."""

from __future__ import annotations

import asyncio

from app.llm_gateway.gateway import LlmGateway


def test_mock_provider_labelled_and_schema_ok():
    async def _run():
        gw = LlmGateway()
        gw._force_mock = True
        schema = {
            "type": "object",
            "required": ["summary"],
            "properties": {
                "summary": {"type": "string"},
                "provider": {"type": "string"},
            },
            "additionalProperties": True,
        }
        out = await gw.complete_json(
            task="policy_compile",
            system="Summarise.",
            user="Clause 1: do not wire fraud.",
            json_schema=schema,
            allow_external_llm=False,
        )
        assert out["ok"] is True
        assert out["provider"] == "mock"
        assert "summary" in out["result"]

    asyncio.run(_run())


def test_runtime_never_uses_external_even_if_flagged():
    async def _run():
        gw = LlmGateway()
        gw._force_mock = True
        gw._openai_enabled = True
        gw._openai_url = "http://example.invalid"
        out = await gw.complete_json(
            task="runtime_intent",
            system="",
            user="hello",
            json_schema={"type": "object"},
            allow_external_llm=True,
        )
        assert out["provider"] == "mock"

    asyncio.run(_run())


def test_groq_used_when_ollama_down():
    async def _run():
        gw = LlmGateway()
        gw._force_mock = False
        gw._groq_key = "gsk_test"
        gw._groq_url = "https://api.groq.com/openai"
        gw._groq_model = "openai/gpt-oss-120b"
        from app.llm_gateway import gateway as gw_mod

        async def no_ollama(_url, _model):
            return False

        gw_mod.probe_ollama = no_ollama  # type: ignore[method-assign]
        import app.llm_gateway.gateway as g
        original = g.probe_ollama
        g.probe_ollama = no_ollama
        try:
            provider = await gw.select_provider(task="policy_compile", allow_external_llm=False)
            assert provider.name == "groq"
            assert provider.model == "openai/gpt-oss-120b"
        finally:
            g.probe_ollama = original

    asyncio.run(_run())


def test_backpressure_when_queue_full():
    async def _run():
        gw = LlmGateway()
        gw._force_mock = True
        gw._queue = asyncio.PriorityQueue(maxsize=1)
        # Pretend workers already started so ensure_workers is a no-op.
        gw._workers = [asyncio.get_running_loop().create_future()]
        blocked = asyncio.get_running_loop().create_future()
        gw._queue.put_nowait((0, 0, blocked, {}))
        out = await gw.complete_json(
            task="policy_compile",
            system="",
            user="",
            json_schema={"type": "object"},
        )
        assert out["ok"] is False
        assert out["error"] == "BACKPRESSURE"

    asyncio.run(_run())
