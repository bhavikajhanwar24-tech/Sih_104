"""F11 — fetch/cache tenant lexicon (policy_keywords + facts + directory name tokens)."""

from __future__ import annotations

import logging
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import json
from typing import Any, Optional

from app.modules.stage_a import TenantLexicon

logger = logging.getLogger("sentinelvoice.ml.tenant_lexicon")

_cache: dict[str, TenantLexicon] = {}
_TTL_S = 60.0


def invalidate(tenant_id: str | None = None) -> None:
    if tenant_id is None:
        _cache.clear()
    else:
        _cache.pop(tenant_id, None)


def _backend_base() -> str:
    return (
        os.environ.get("SV_BACKEND_URL")
        or os.environ.get("SENTINELVOICE_BACKEND_URL")
        or "http://127.0.0.1:8081"
    ).rstrip("/")


def _token() -> str:
    return os.environ.get("ML_SERVICE_TOKEN") or os.environ.get("SENTINELVOICE_ML_SERVICE_TOKEN") or ""


def _get_json(path: str) -> Optional[dict[str, Any]]:
    url = _backend_base() + path
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "X-ML-Service-Token": _token(),
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except Exception as exc:  # noqa: BLE001
        logger.debug("tenant_lexicon_fetch_failed path=%s err=%s", path, exc)
        return None


def get_lexicon(tenant_id: str | None) -> TenantLexicon:
    if not tenant_id:
        return TenantLexicon(tenant_id="", fetched_at=time.monotonic())
    cached = _cache.get(tenant_id)
    if cached and (time.monotonic() - cached.fetched_at) < _TTL_S:
        return cached
    q = urllib.parse.urlencode({"tenantId": tenant_id})
    data = _get_json(f"/internal/v2/linguistics/lexicon?{q}") or {}
    lex = TenantLexicon(
        tenant_id=tenant_id,
        policy_set_id=str(data["policySetId"]) if data.get("policySetId") else None,
        keywords=list(data.get("keywords") or []),
        facts=list(data.get("facts") or [])[:20],
        name_tokens=[str(x) for x in (data.get("nameTokens") or []) if x][:200],
        languages=[str(x) for x in (data.get("languages") or ["en", "hi"])],
        fetched_at=time.monotonic(),
    )
    _cache[tenant_id] = lex
    return lex
