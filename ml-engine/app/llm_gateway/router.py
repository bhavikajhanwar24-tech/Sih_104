from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

from app.config import settings
from app.llm_gateway.gateway import gateway

logger = logging.getLogger("sentinelvoice.ml.llm_gateway")

router = APIRouter(prefix="/llm/v1", tags=["llm-gateway"])


class RunRequest(BaseModel):
    task: str
    tenantId: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)


def _require_token(x_ml_service_token: str | None) -> None:
    expected = settings.service_token
    if not expected:
        raise HTTPException(status_code=503, detail="service_token_not_configured")
    if not x_ml_service_token or x_ml_service_token != expected:
        raise HTTPException(status_code=401, detail="invalid_service_token")


@router.get("/health")
async def health(x_ml_service_token: str | None = Header(default=None, alias="X-ML-Service-Token")):
    _require_token(x_ml_service_token)
    return await gateway.health_detailed()


@router.post("/selftest")
async def selftest(x_ml_service_token: str | None = Header(default=None, alias="X-ML-Service-Token")):
    """Tiny schema-constrained round-trip — used by Settings 'Run test'."""
    _require_token(x_ml_service_token)
    return await gateway.selftest()


@router.post("/run")
async def run(
    body: RunRequest,
    x_ml_service_token: str | None = Header(default=None, alias="X-ML-Service-Token"),
):
    _require_token(x_ml_service_token)
    if body.task not in ("policy_compile", "runtime_intent", "selftest"):
        raise HTTPException(status_code=400, detail="unknown_task")
    payload = body.payload or {}
    system = str(payload.get("system") or "")
    user = str(payload.get("user") or "")
    schema = payload.get("jsonSchema") or payload.get("json_schema") or {"type": "object"}
    if not isinstance(schema, dict):
        raise HTTPException(status_code=400, detail="jsonSchema_required")
    allow_external = bool(payload.get("allowExternalLlm") or payload.get("allow_external_llm"))
    # Runtime intent never uses external even if payload lies — gateway enforces.
    if body.task == "runtime_intent":
        allow_external = False
    # Wrap untrusted block if provided
    untrusted = payload.get("untrustedData") or payload.get("untrusted_data")
    if untrusted:
        user = user + "\n\n" + gateway.wrap_untrusted(str(untrusted))
    return await gateway.complete_json(
        task=body.task,
        system=system,
        user=user,
        json_schema=schema,
        allow_external_llm=allow_external,
    )
