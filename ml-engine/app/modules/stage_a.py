"""F11 Stage A — fast deterministic lexicon + entity extraction (<50 ms target)."""

from __future__ import annotations

import logging
import re
import time
import unicodedata
from dataclasses import dataclass, field
from typing import Any, Optional

from app.modules import extractor as extractor_mod
from app.modules import intent as intent_mod
from app.modules.privacy import detect_injection_attempt, mask_account

logger = logging.getLogger("sentinelvoice.ml.stage_a")

_CATEGORY_KEYS = (
    "URGENCY",
    "SECRECY",
    "AUTHORITY",
    "PAYMENT",
    "CREDENTIAL",
    "CUSTOM",
)

_LEXICON_TO_CATEGORY = {
    "urgency": "URGENCY",
    "secrecy": "SECRECY",
    "authority": "AUTHORITY",
    "authorityInvocation": "AUTHORITY",
    "emotionalCoercion": "URGENCY",
    "payment": "PAYMENT",
    "credential": "CREDENTIAL",
    "custom": "CUSTOM",
}

_DIGIT_RUN_RE = re.compile(r"(?<!\d)(\d{9,18})(?!\d)")


def _norm(token: str) -> str:
    t = unicodedata.normalize("NFKC", token or "").lower().strip()
    t = re.sub(r"[^a-z0-9\u0900-\u097f]+", "", t)
    return t


def _fuzzy_eq(a: str, b: str, max_ed: int = 2) -> bool:
    if not a or not b:
        return False
    if a == b:
        return True
    if abs(len(a) - len(b)) > max_ed:
        return False
    # Bounded Levenshtein
    if len(a) < 3 or len(b) < 3:
        return a == b
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            ins, delete, sub = cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (ca != cb)
            cur.append(min(ins, delete, sub))
        if min(cur) > max_ed:
            return False
        prev = cur
    return prev[-1] <= max_ed


@dataclass
class TenantLexicon:
    tenant_id: str
    policy_set_id: Optional[str] = None
    keywords: list[dict[str, Any]] = field(default_factory=list)
    facts: list[dict[str, Any]] = field(default_factory=list)
    rules: list[dict[str, Any]] = field(default_factory=list)
    name_tokens: list[str] = field(default_factory=list)
    languages: list[str] = field(default_factory=lambda: ["en", "hi"])
    fetched_at: float = 0.0


@dataclass
class StageAResult:
    urgency: float = 0.0
    secrecy: float = 0.0
    authority: float = 0.0
    emotional_coercion: float = 0.0
    categories: dict[str, float] = field(default_factory=dict)
    ask_detected: bool = False
    ask: Optional[dict[str, Any]] = None
    matched_rule_ids: list[str] = field(default_factory=list)
    matched_keywords: list[str] = field(default_factory=list)
    claimed_role: Optional[str] = None
    injection_attempt: bool = False
    confidence: float = 0.55
    ambiguous: bool = False
    high_risk: bool = False
    latency_ms: float = 0.0

    def to_linguistic(self, *, language: str, age_ms: int = 0) -> dict[str, Any]:
        cats = {k: float(self.categories.get(k, 0.0)) for k in _CATEGORY_KEYS}
        out: dict[str, Any] = {
            "available": True,
            "source": "STAGE_A",
            "observedAt": int(time.time() * 1000),
            "ageMs": age_ms,
            "confidence": float(self.confidence),
            "language": language or "und",
            "urgency": float(self.urgency),
            "secrecy": float(self.secrecy),
            "authorityInvocation": float(self.authority),
            "emotionalCoercion": float(self.emotional_coercion),
            "askDetected": bool(self.ask_detected),
            "categories": cats,
            "matchedRuleIds": list(self.matched_rule_ids)[:16],
            "matchedKeywords": list(self.matched_keywords)[:16],
            "injectionAttempt": bool(self.injection_attempt),
            "llmPending": False,
            "claimedIdentity": None,
            "claimedRole": self.claimed_role,
        }
        if self.ask:
            out["ask"] = self.ask
        return out


def _score_tenant_keywords(
    text: str, lexicon: Optional[TenantLexicon]
) -> tuple[dict[str, float], list[str], list[str]]:
    """Return (categories, matched_rule_ids, matched_keyword_terms)."""
    cats = {k: 0.0 for k in _CATEGORY_KEYS}
    matched_ids: list[str] = []
    matched_terms: list[str] = []
    if not lexicon or not lexicon.keywords:
        return cats, matched_ids, matched_terms
    raw_lower = unicodedata.normalize("NFKC", text or "").lower()
    # Spaced token blob for single-word hits; raw text for multi-word phrases.
    tokens = [_norm(t) for t in re.findall(r"[\w\u0900-\u097f]+", text or "")]
    tokens = [t for t in tokens if t]
    blob = " ".join(tokens)
    seen_terms: set[str] = set()
    for kw in lexicon.keywords:
        raw_term = str(kw.get("term") or kw.get("keyword") or "").strip()
        if not raw_term:
            continue
        term_norm = _norm(raw_term)  # spaces stripped — single-token compare
        phrase = re.sub(r"\s+", " ", raw_term.lower()).strip()
        cat = str(kw.get("category") or "CUSTOM").upper()
        if cat not in cats:
            cat = "CUSTOM"
        weight = float(kw.get("weight") or 0.35)
        hit = False
        if " " in phrase or "-" in phrase:
            # Phrase: match in raw transcript (preserve word boundaries loosely)
            compact_phrase = re.sub(r"[^a-z0-9\u0900-\u097f]+", "", phrase)
            compact_raw = re.sub(r"[^a-z0-9\u0900-\u097f]+", "", raw_lower)
            hit = bool(phrase and phrase in raw_lower) or bool(
                compact_phrase and compact_phrase in compact_raw
            )
        else:
            hit = term_norm in blob or any(
                _fuzzy_eq(term_norm, tok, 2)
                for tok in tokens
                if abs(len(tok) - len(term_norm)) <= 2
            )
        if hit:
            cats[cat] = min(1.0, cats[cat] + weight)
            rid = (
                kw.get("sourceRuleId")
                or kw.get("source_rule_id")
                or kw.get("ruleId")
                or kw.get("rule_id")
                or kw.get("id")
                or raw_term
            )
            matched_ids.append(str(rid))
            display = raw_term
            key = display.lower()
            if key not in seen_terms:
                seen_terms.add(key)
                matched_terms.append(display)
    return cats, matched_ids[:16], matched_terms[:16]


def _extract_amounts(text: str) -> Optional[float]:
    try:
        return extractor_mod.parse_amount(text)
    except Exception:
        return None


def run_stage_a(
    text: str,
    *,
    lexicon: Optional[TenantLexicon] = None,
) -> StageAResult:
    t0 = time.perf_counter()
    result = StageAResult()
    if not (text or "").strip():
        result.latency_ms = (time.perf_counter() - t0) * 1000.0
        return result

    result.injection_attempt = detect_injection_attempt(text)

    # Base generic lexicon (v1 fallback)
    try:
        scored = intent_mod.score_text(text, use_semantic=True)
        fields = scored.to_linguistic_fields()
        result.urgency = float(fields.get("urgency") or 0.0)
        result.secrecy = float(fields.get("secrecy") or 0.0)
        result.authority = float(fields.get("authorityInvocation") or 0.0)
        result.emotional_coercion = float(fields.get("emotionalCoercion") or 0.0)
        result.ask_detected = bool(fields.get("askDetected"))
        role = fields.get("claimedRole") or ""
        result.claimed_role = role.strip() or None
    except Exception:
        logger.exception("stage_a_base_lexicon_failed")

    cats, matched_ids, matched_terms = _score_tenant_keywords(text, lexicon)
    # Merge base scores into categories
    cats["URGENCY"] = max(cats["URGENCY"], result.urgency, result.emotional_coercion * 0.7)
    cats["SECRECY"] = max(cats["SECRECY"], result.secrecy)
    cats["AUTHORITY"] = max(cats["AUTHORITY"], result.authority)
    result.categories = cats
    result.matched_rule_ids = matched_ids
    result.matched_keywords = matched_terms

    # Sync continuous scores from categories when tenant lexicon fires harder
    result.urgency = max(result.urgency, cats["URGENCY"])
    result.secrecy = max(result.secrecy, cats["SECRECY"])
    result.authority = max(result.authority, cats["AUTHORITY"])

    ask_type = None
    amount = _extract_amounts(text)
    shares_cred = False
    beneficiary = False
    try:
        extracted = extractor_mod.extract(text)
        if extracted is not None:
            ask_type = getattr(extracted, "type", None)
            if amount is None:
                amount = getattr(extracted, "amount", None)
            role = getattr(extracted, "claimedRole", None) or getattr(extracted, "claimed_role", None)
            if role and not result.claimed_role:
                result.claimed_role = str(role)
            if getattr(extracted, "askDetected", False):
                result.ask_detected = True
    except Exception:
        logger.debug("stage_a_extractor_failed", exc_info=True)

    low = (text or "").lower()
    # Credential / payment asks come from ACTIVE tenant keywords (categories) + structured
    # extractor — not from hardcoded lab scripts. sharesCredential follows CREDENTIAL category.
    if cats.get("CREDENTIAL", 0.0) >= 0.35:
        shares_cred = True
        ask_type = ask_type or "OTP_SHARE"
    if cats.get("PAYMENT", 0.0) >= 0.35:
        ask_type = ask_type or "WIRE_TRANSFER"
    if any(w in low for w in ("beneficiary", "vendor", "account ending", "send to")):
        beneficiary = True

    # Mask account-like digits (never keep raw)
    for m in _DIGIT_RUN_RE.finditer(text):
        _mask, _digest = mask_account(m.group(1))
        beneficiary = beneficiary or True

    # Fuzzy directory name hits → role token only
    if lexicon and lexicon.name_tokens:
        tokens = [_norm(t) for t in re.findall(r"[\w\u0900-\u097f]+", text)]
        for i, name in enumerate(lexicon.name_tokens[:200]):
            nn = _norm(name)
            if nn and any(_fuzzy_eq(nn, tok, 2) for tok in tokens if len(tok) >= 3):
                # Do not put raw name on wire
                if not result.claimed_role:
                    result.claimed_role = f"[PERSON_{i + 1}]"
                break

    if ask_type or amount is not None or shares_cred:
        result.ask_detected = True
        result.ask = {
            "type": str(ask_type or "OTHER").upper().replace(" ", "_"),
            "amount": float(amount) if amount is not None else None,
            "currency": "INR" if amount is not None else None,
            "beneficiaryHint": None,
            "deadline": None,
            "sharesCredential": shares_cred,
            "beneficiaryMentioned": beneficiary,
        }

    result.categories = cats
    peak = max(cats.values()) if cats else 0.0
    result.confidence = min(0.95, 0.45 + 0.4 * peak)
    result.high_risk = peak >= 0.65 or shares_cred or result.injection_attempt
    result.ambiguous = 0.35 <= peak < 0.65 and not result.ask_detected
    result.latency_ms = (time.perf_counter() - t0) * 1000.0
    return result
