"""F11 privacy guards — transcript text must never leave the ml-engine process on the FeatureFrame wire."""

from __future__ import annotations

import hashlib
import re
from typing import Any

# Keys that must never appear with non-empty string values on a FeatureFrame (or linguistic block).
_FORBIDDEN_TEXT_KEYS = frozenset(
    {
        "redactedSnippet",
        "redactedDelta",
        "transcript",
        "rawTranscript",
        "text",
        "utterance",
        "snippet",
    }
)

_EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b")
_PHONE_RE = re.compile(
    r"(?<!\d)(?:\+91[\s\-]*|91[\s\-]*|0[\s\-]*)?[6-9](?:[\s\-]?\d){9}(?!\d)"
    r"|(?<!\d)\+\d{8,15}(?!\d)"
)
_AADHAAR_RE = re.compile(r"(?<!\d)\d{4}[\s\-]?\d{4}[\s\-]?\d{4}(?!\d)")
_PAN_RE = re.compile(r"\b[A-Z]{5}\d{4}[A-Z]\b", re.I)
_CARD_RE = re.compile(r"(?<!\d)(?:\d[ \-]?){13,19}(?!\d)")
_OTP_RE = re.compile(r"(?<!\d)\d{4,8}(?!\d)")
_ACCOUNT_RE = re.compile(r"(?<!\d)\d{9,18}(?!\d)")

_INJECTION_PATTERNS = (
    re.compile(r"\bignore\s+(all\s+)?(your\s+)?(previous\s+)?(rules|instructions)\b", re.I),
    re.compile(r"\byou\s+are\s+an?\s+ai\b", re.I),
    re.compile(r"\bsystem\s*:", re.I),
    re.compile(r"\bdisregard\s+(the\s+)?(system|safety)\b", re.I),
    re.compile(r"\bdo\s+not\s+follow\s+(your\s+)?(rules|policy)\b", re.I),
)


class PrivacyViolation(RuntimeError):
    """Raised when transcript-like text is about to leave the process on a FeatureFrame."""


def assert_no_transcript_on_wire(frame: dict[str, Any]) -> None:
    """Runtime guard — FeatureFrame must not carry transcript text (F11)."""
    if not isinstance(frame, dict):
        return
    ling = frame.get("linguistic")
    if isinstance(ling, dict):
        for key in _FORBIDDEN_TEXT_KEYS:
            val = ling.get(key)
            if isinstance(val, str) and val.strip():
                raise PrivacyViolation(
                    f"FeatureFrame.linguistic.{key} must be empty on the wire (F11 privacy)"
                )
        # claimedIdentity is free-text PII — strip if present
        cid = ling.get("claimedIdentity")
        if isinstance(cid, str) and cid.strip() and not cid.startswith("["):
            raise PrivacyViolation(
                "FeatureFrame.linguistic.claimedIdentity must not carry raw names on the wire"
            )


def strip_transcript_fields(linguistic: dict[str, Any]) -> dict[str, Any]:
    """Return a copy safe for FeatureFrame emission."""
    out = dict(linguistic)
    for key in _FORBIDDEN_TEXT_KEYS:
        out.pop(key, None)
    # Keep role tokens like [PERSON_1] / CFO; drop raw names
    cid = out.get("claimedIdentity")
    if isinstance(cid, str) and cid.strip() and not (
        cid.startswith("[") or cid.isupper()
    ):
        out["claimedIdentity"] = None
    return out


def mask_account(digits: str) -> tuple[str, str]:
    """Return (masked form, keyed hash). Never returns raw digits."""
    cleaned = re.sub(r"\D", "", digits)
    if len(cleaned) < 4:
        return "[ACCOUNT]", hashlib.sha256(cleaned.encode()).hexdigest()[:16]
    masked = f"[ACCOUNT_…{cleaned[-4:]}]"
    digest = hashlib.sha256(("sv-acct:" + cleaned).encode()).hexdigest()[:24]
    return masked, digest


def redact_for_llm(text: str) -> str:
    """PII redactor for Stage B LLM input (phones, accounts, Aadhaar/PAN, emails, OTP runs)."""
    if not text:
        return ""
    out = _EMAIL_RE.sub("[EMAIL]", text)
    out = _AADHAAR_RE.sub("[AADHAAR]", out)
    out = _PAN_RE.sub("[PAN]", out)
    out = _CARD_RE.sub("[CARD]", out)
    out = _PHONE_RE.sub("[PHONE]", out)
    out = _ACCOUNT_RE.sub("[ACCOUNT]", out)
    # OTP-like short digit runs after longer forms removed
    out = _OTP_RE.sub("[OTP]", out)
    return out


def detect_injection_attempt(text: str) -> bool:
    if not text:
        return False
    return any(p.search(text) for p in _INJECTION_PATTERNS)


def wrap_untrusted(text: str) -> str:
    return f"<untrusted_data>\n{text}\n</untrusted_data>"
