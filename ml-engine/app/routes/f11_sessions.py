"""F11 — ml-engine endpoints for gate-check wait + session linguistic stats."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Header, HTTPException

from app.config import settings
from app.modules.stage_b import stage_b_runner
from app.session import registry

router = APIRouter(prefix="/internal/v1/sessions", tags=["f11-sessions"])


def _auth(token: str | None) -> None:
    expected = settings.service_token or ""
    if expected and (token or "") != expected:
        raise HTTPException(status_code=401, detail="unauthorized")


@router.post("/{session_id}/gate-wait")
async def gate_wait(
    session_id: str,
    x_ml_service_token: str | None = Header(default=None, alias="X-ML-Service-Token"),
) -> dict[str, Any]:
    """Block up to 800ms for an in-flight Stage B extraction (F11 approval gate)."""
    _auth(x_ml_service_token)
    latest = await stage_b_runner.wait_inflight(session_id, timeout_s=0.8)
    return {
        "sessionId": session_id,
        "hasResult": latest is not None,
        "source": (latest or {}).get("source"),
        "stats": stage_b_runner.stats(session_id),
    }


@router.get("/{session_id}/linguistic-stats")
async def linguistic_stats(
    session_id: str,
    x_ml_service_token: str | None = Header(default=None, alias="X-ML-Service-Token"),
) -> dict[str, Any]:
    _auth(x_ml_service_token)
    session = registry.get(session_id)
    ling = (session.slow_path_linguistic if session else None) or {}
    stats = stage_b_runner.stats(session_id)
    status = "unavailable"
    if stage_b_runner.pending(session_id) or ling.get("llmPending"):
        status = "pending"
    elif ling.get("available"):
        status = "live"
    return {
        "sessionId": session_id,
        "status": status,
        "source": ling.get("source"),
        "ageMs": ling.get("ageMs"),
        "confidence": ling.get("confidence"),
        "injectionAttempt": ling.get("injectionAttempt"),
        "matchedKeywords": ling.get("matchedKeywords") or [],
        "matchedRuleIds": ling.get("matchedRuleIds") or [],
        "llmThinking": ling.get("llmThinking"),
        "llmPending": bool(ling.get("llmPending") or stage_b_runner.pending(session_id)),
        "categories": ling.get("categories") or {},
        "ask": ling.get("ask"),
        "tenantId": getattr(session, "tenant_id", None) if session else None,
        "lexiconKeywordCount": (
            len(__import__("app.modules.tenant_lexicon", fromlist=["get_lexicon"]).get_lexicon(
                getattr(session, "tenant_id", None)
            ).keywords)
            if session
            else 0
        ),
        "rollingRawChars": len(getattr(getattr(session, "asr_state", None), "_rolling_raw", "") or "")
        if session
        else 0,
        "llm": stats,
    }
