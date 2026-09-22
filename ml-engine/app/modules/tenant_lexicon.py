"""F11 — fetch/cache tenant lexicon (policy_keywords + facts + directory name tokens)."""

from __future__ import annotations

import json
import logging
import os
import time
import urllib.parse
import urllib.request
from typing import Any, Optional

from app.config import settings
from app.modules.stage_a import TenantLexicon

logger = logging.getLogger("sentinelvoice.ml.tenant_lexicon")

_cache: dict[str, TenantLexicon] = {}
_TTL_S = 60.0
_FAIL_TTL_S = 5.0  # don't stick an empty lexicon for a full minute after a timeout
_last_fail_at: dict[str, float] = {}

# Always seed these when ACTIVE rules exist — Stage A must not depend on a tiny
# policy_keywords table to wake the LLM / show UI hits.
_SEED_TERMS = (
    ("password", "CREDENTIAL"),
    ("otp", "CREDENTIAL"),
    ("pin", "CREDENTIAL"),
    ("cvv", "CREDENTIAL"),
    ("passcode", "CREDENTIAL"),
    ("secret", "SECRECY"),
    ("callback", "AUTHORITY"),
    ("call back", "AUTHORITY"),
    ("transfer", "PAYMENT"),
    ("wire", "PAYMENT"),
    ("urgent", "URGENCY"),
    ("immediately", "URGENCY"),
    ("verify", "AUTHORITY"),
    ("account number", "PAYMENT"),
    ("share", "CREDENTIAL"),
    ("don't tell", "SECRECY"),
    ("do not tell", "SECRECY"),
)

_STOP = frozenset(
    {
        "the", "and", "or", "a", "an", "to", "of", "in", "on", "for", "is", "are",
        "be", "when", "must", "never", "not", "with", "from", "that", "this", "call",
        "caller", "agent", "employee", "should", "shall", "will", "may", "if", "then",
        "rule", "policy", "level", "active", "does", "doesn", "don", "any", "all",
    }
)


def _norm_term(term: str) -> str:
    return "".join(ch for ch in (term or "").lower() if ch.isalnum() or ch.isspace()).strip()


def _seed_keywords_from_rules(
    keywords: list[dict[str, Any]], rules: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Derive Stage-A keywords from ACTIVE rule text so paraphrases still hit."""
    out = list(keywords)
    seen = {_norm_term(str(k.get("term") or k.get("keyword") or "")) for k in out}
    seen.discard("")

    def _add(term: str, category: str, rule_id: Any) -> None:
        t = (term or "").strip()
        key = _norm_term(t)
        if not key or key in seen or len(key) < 3:
            return
        seen.add(key)
        row: dict[str, Any] = {
            "term": t,
            "category": category,
            "weight": 0.45,
        }
        if rule_id:
            row["sourceRuleId"] = str(rule_id)
        out.append(row)

    if rules:
        for term, cat in _SEED_TERMS:
            _add(term, cat, None)
        for r in rules:
            rid = r.get("ruleId") or r.get("id")
            blob = " ".join(
                str(r.get(k) or "")
                for k in ("title", "firesWhen", "plainEnglish", "doesNotFireWhen")
            ).lower()
            for term, cat in _SEED_TERMS:
                if term in blob:
                    _add(term, cat, rid)
            # Significant tokens from firesWhen (≥5 chars) become soft keywords.
            fires = str(r.get("firesWhen") or r.get("plainEnglish") or "")
            for tok in fires.replace("/", " ").replace("-", " ").split():
                clean = "".join(ch for ch in tok.lower() if ch.isalnum())
                if len(clean) >= 5 and clean not in _STOP:
                    _add(clean, "CUSTOM", rid)
    return out[:120]


def invalidate(tenant_id: str | None = None) -> None:
    if tenant_id is None:
        _cache.clear()
        _last_fail_at.clear()
    else:
        _cache.pop(tenant_id, None)
        _last_fail_at.pop(tenant_id, None)


def _backend_base() -> str:
    raw = (
        os.environ.get("ML_LEXICON_BACKEND_URL")
        or settings.java_decision_http
        or os.environ.get("SV_BACKEND_URL")
        or os.environ.get("SENTINELVOICE_BACKEND_URL")
        or "http://127.0.0.1:8081"
    ).rstrip("/")
    # Asterisk AGI in Docker uses host.docker.internal; ml-engine on the Windows/mac
    # host often cannot resolve that → lexicon fetch fails → lexicon_kw=0 forever.
    if "host.docker.internal" in raw:
        raw = raw.replace("host.docker.internal", "127.0.0.1")
    return raw


def _token() -> str:
    return (
        settings.service_token
        or os.environ.get("ML_SERVICE_TOKEN")
        or os.environ.get("SENTINELVOICE_ML_SERVICE_TOKEN")
        or ""
    )


def _get_json(path: str) -> Optional[dict[str, Any]]:
    url = _backend_base() + path
    token = _token()
    if not token:
        logger.warning("tenant_lexicon_no_token path=%s", path)
        return None
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "X-ML-Service-Token": token,
        },
        method="GET",
    )
    try:
        # Backend lexicon under load can take ~5s; 3s caused permanent empty cache.
        with urllib.request.urlopen(req, timeout=12) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except Exception as exc:  # noqa: BLE001
        logger.warning("tenant_lexicon_fetch_failed path=%s err=%s", path, exc)
        return None


def get_lexicon(tenant_id: str | None) -> TenantLexicon:
    if not tenant_id:
        return TenantLexicon(tenant_id="", fetched_at=time.monotonic())
    now = time.monotonic()
    cached = _cache.get(tenant_id)
    if cached and (now - cached.fetched_at) < _TTL_S and (cached.keywords or cached.rules):
        return cached
    # After a failed fetch, briefly reuse any prior non-empty cache instead of empty.
    if cached and (cached.keywords or cached.rules) and (now - _last_fail_at.get(tenant_id, 0.0)) < _FAIL_TTL_S:
        return cached
    if not cached and (now - _last_fail_at.get(tenant_id, 0.0)) < _FAIL_TTL_S:
        return TenantLexicon(tenant_id=tenant_id, fetched_at=now)

    q = urllib.parse.urlencode({"tenantId": tenant_id})
    data = _get_json(f"/internal/v2/linguistics/lexicon?{q}")
    if data is None:
        _last_fail_at[tenant_id] = now
        if cached and (cached.keywords or cached.rules):
            logger.warning(
                "tenant_lexicon_stale_reuse tenant=%s keywords=%s rules=%s",
                tenant_id,
                len(cached.keywords),
                len(cached.rules),
            )
            return cached
        return TenantLexicon(tenant_id=tenant_id, fetched_at=now)

    keywords = list(data.get("keywords") or [])
    rules = list(data.get("rules") or [])[:24]
    keywords = _seed_keywords_from_rules(keywords, rules)
    lex = TenantLexicon(
        tenant_id=tenant_id,
        policy_set_id=str(data["policySetId"]) if data.get("policySetId") else None,
        keywords=keywords,
        facts=list(data.get("facts") or [])[:20],
        rules=rules,
        name_tokens=[str(x) for x in (data.get("nameTokens") or []) if x][:200],
        languages=[str(x) for x in (data.get("languages") or ["en", "hi"])],
        fetched_at=now,
    )
    if keywords or rules:
        _cache[tenant_id] = lex
        _last_fail_at.pop(tenant_id, None)
        logger.info(
            "tenant_lexicon_loaded tenant=%s keywords=%s rules=%s policySetId=%s",
            tenant_id,
            len(keywords),
            len(rules),
            lex.policy_set_id,
        )
    else:
        logger.warning(
            "tenant_lexicon_empty tenant=%s policySetId=%s",
            tenant_id,
            data.get("policySetId"),
        )
        _last_fail_at[tenant_id] = now
    return lex
