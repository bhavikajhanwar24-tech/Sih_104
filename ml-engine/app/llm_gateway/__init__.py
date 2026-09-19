"""F5 LLM gateway — separate from the 500ms feature-frame path.

Lives as a FastAPI router on the Inference Plane process so it shares
service-token auth and ops surface, but uses its own async queue and is
never imported by the fast-path emitter/scheduler loop.
"""

from __future__ import annotations

from app.llm_gateway.router import router

__all__ = ["router"]
