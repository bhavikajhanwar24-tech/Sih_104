"""Structured ask + claim extraction from ASR transcripts (Context §10.6).

Indian number forms (lakh / crore / Indian comma grouping) are first-class —
"50 lakh", "50,00,000", and "fifty lakhs" must all yield 5_000_000.
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from typing import Any, Optional

# ---------------------------------------------------------------------------
# Amount parsing — Indian + international
# ---------------------------------------------------------------------------

_ONES = {
    "zero": 0,
    "one": 1,
    "two": 2,
    "three": 3,
    "four": 4,
    "five": 5,
    "six": 6,
    "seven": 7,
    "eight": 8,
    "nine": 9,
    "ten": 10,
    "eleven": 11,
    "twelve": 12,
    "thirteen": 13,
    "fourteen": 14,
    "fifteen": 15,
    "sixteen": 16,
    "seventeen": 17,
    "eighteen": 18,
    "nineteen": 19,
    "twenty": 20,
    "thirty": 30,
    "forty": 40,
    "fifty": 50,
    "sixty": 60,
    "seventy": 70,
    "eighty": 80,
    "ninety": 90,
}

_HI_ONES = {
    "एक": 1,
    "दो": 2,
    "तीन": 3,
    "चार": 4,
    "पांच": 5,
    "पाँच": 5,
    "पचास": 50,
    "दस": 10,
    "बीस": 20,
    "तीस": 30,
    "चालीस": 40,
    "साठ": 60,
    "सत्तर": 70,
    "अस्सी": 80,
    "नब्बे": 90,
    "सौ": 100,
    "हजार": 1000,
}

_SCALE = {
    "thousand": 1_000,
    "thousands": 1_000,
    "lakh": 100_000,
    "lakhs": 100_000,
    "lac": 100_000,
    "lacs": 100_000,
    "लाख": 100_000,
    "लाखों": 100_000,
    "crore": 10_000_000,
    "crores": 10_000_000,
    "करोड़": 10_000_000,
    "करोड़ों": 10_000_000,
    "million": 1_000_000,
    "millions": 1_000_000,
    "billion": 1_000_000_000,
}

_CURRENCY_HINTS = (
    (re.compile(r"\b(?:rs\.?|inr|rupees?|₹)\b", re.I), "INR"),
    (re.compile(r"\b(?:usd|\$|dollars?)\b", re.I), "USD"),
    (re.compile(r"\b(?:eur|€|euros?)\b", re.I), "EUR"),
)

_TX_TYPES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"\b(?:wire|neft|rtgs|imps)\b.*\btransfer\b|\bwire transfer\b|\bneft\b|\brtgs\b|\bimps\b", re.I), "WIRE_TRANSFER"),
    (re.compile(r"\b(?:transfer|bhejo|भेजो|भेजना)\b", re.I), "WIRE_TRANSFER"),
    (re.compile(r"\b(?:upi|gpay|phonepe|paytm)\b", re.I), "UPI"),
    (re.compile(r"\b(?:otp|one[- ]time password)\b", re.I), "OTP_SHARE"),
    (re.compile(r"\b(?:gift\s*card|itunes|steam)\b", re.I), "GIFT_CARD"),
    (re.compile(r"\b(?:crypto|bitcoin|usdt|binance)\b", re.I), "CRYPTO"),
    (re.compile(r"\b(?:loan|emi)\b", re.I), "LOAN"),
    (re.compile(r"\b(?:password|pin|cvv)\b", re.I), "CREDENTIAL_SHARE"),
]

_DEADLINE_RE = re.compile(
    r"(?:"
    r"immediately|right now|asap|"
    r"within\s+\d+\s+minutes?|"
    r"before\s+(?:close of business|cob|the market closes|noon|tonight|today)|"
    r"turant|abhi ke abhi|jaldi|"
    r"तुरंत|अभी के अभी|जल्दी|"
    r"உடனே|ventane"
    r")",
    re.I,
)

_BENEFICIARY_RE = re.compile(
    r"(?:"
    r"(?:to|into|towards)\s+(?:the\s+)?(?P<hint>(?:account|vendor|beneficiary|wallet)[^.,;]{0,40})|"
    r"(?:account\s*(?:ending|number|no\.?)?\s*)(?P<acct>[\dXx*]{3,})"
    r")",
    re.I,
)

_CLAIM_ROLE_RE = [
    re.compile(
        r"(?:this is|i am|i'm|speaking as)\s+(?:the\s+)?(?P<role>ceo|cfo|cto|md|managing director|"
        r"police(?:\s+commissioner)?|director|manager|branch manager)",
        re.I,
    ),
    re.compile(
        r"main\s+(?P<role>ceo|cfo|md|managing director)\s+(?:bol raha hoon|hun|hoon)",
        re.I,
    ),
    re.compile(
        r"मैं\s+(?P<role>सीईओ|सीएफओ|प्रबंध निदेशक)",
        re.I,
    ),
]

_CLAIM_IDENTITY_RE = [
    re.compile(
        r"(?:this is|i am|i'm)\s+(?P<name>[A-Za-z]+(?:\s+[A-Za-z]+){0,3})"
        r"(?:\s*,\s*|\s+)"
        r"(?:your|the)\s+(?P<role>ceo|cfo|cto|md|managing director)",
        re.I,
    ),
    re.compile(
        r"(?:this is|i am|i'm)\s+(?P<name>[A-Za-z]+(?:\s+[A-Za-z]+){0,3})\b",
        re.I,
    ),
    re.compile(
        r"main\s+(?P<name>[A-Za-z]+(?:\s+[A-Za-z]+)?)\s+(?:bol raha hoon|bula raha)",
        re.I,
    ),
]

_WORD_AMOUNT_RE = re.compile(
    r"\b(?P<head>(?:(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|"
    r"one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|"
    r"thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen)"
    r"(?:[\s-](?:one|two|three|four|five|six|seven|eight|nine))?|"
    r"\d+(?:\.\d+)?))"
    r"\s*(?P<scale>lakh|lakhs|lac|lacs|crore|crores|million|millions|"
    r"thousand|thousands|लाख|लाखों|करोड़|करोड़ों)\b",
    re.I,
)

_HI_AMOUNT_RE = re.compile(
    r"(?P<head>पचास|पाँच|पांच|दस|बीस|तीस|चालीस|साठ|\d+(?:\.\d+)?)"
    r"\s*(?P<scale>लाख|लाखों|करोड़|करोड़ों)"
)

# Indian-style 50,00,000 (last group is 3 digits) or western 5,000,000 / 5000000
_NUMERIC_AMOUNT_RE = re.compile(
    r"(?<!\d)(?P<num>\d{1,3}(?:,\d{2})*,\d{3}|\d{1,3}(?:,\d{3})+|\d{4,12})(?!\d)"
)


@dataclass
class AskExtraction:
    type: Optional[str] = None
    amount: Optional[float] = None
    currency: Optional[str] = None
    beneficiaryHint: Optional[str] = None
    deadline: Optional[str] = None
    claimedIdentity: Optional[str] = None
    claimedRole: Optional[str] = None
    askDetected: bool = False

    def to_linguistic_fields(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "askDetected": self.askDetected,
            "claimedIdentity": self.claimedIdentity or "",
            "claimedRole": self.claimedRole or "",
        }
        if self.askDetected and self.type and self.amount is not None:
            out["ask"] = {
                "type": self.type,
                "amount": float(self.amount),
                "currency": self.currency or "INR",
                "beneficiaryHint": self.beneficiaryHint or "",
                "deadline": self.deadline or "",
            }
        return out


def parse_english_word_number(text: str) -> Optional[float]:
    """Parse 'fifty' / 'twenty one' style heads (no scale)."""
    parts = re.split(r"[\s-]+", text.strip().lower())
    if not parts or not parts[0]:
        return None
    if parts[0].replace(".", "", 1).isdigit():
        return float(parts[0])
    total = 0
    for p in parts:
        if p not in _ONES:
            return None
        total += _ONES[p]
    return float(total)


def parse_amount(text: str) -> Optional[float]:
    """Extract the most likely INR/major amount from a transcript fragment."""
    if not text:
        return None

    candidates: list[float] = []

    for m in _WORD_AMOUNT_RE.finditer(text):
        head = parse_english_word_number(m.group("head"))
        if head is None:
            continue
        scale = _SCALE[m.group("scale").lower()]
        candidates.append(head * scale)

    for m in _HI_AMOUNT_RE.finditer(text):
        head_raw = m.group("head")
        if head_raw.isdigit() or re.match(r"^\d+\.\d+$", head_raw):
            head = float(head_raw)
        else:
            head = float(_HI_ONES.get(head_raw, 0))
            if head <= 0:
                continue
        scale = _SCALE[m.group("scale")]
        candidates.append(head * scale)

    for m in _NUMERIC_AMOUNT_RE.finditer(text):
        raw = m.group("num")
        digits = raw.replace(",", "")
        # Skip short runs that look like years / OTPs / phone fragments
        if len(digits) < 4:
            continue
        candidates.append(float(digits))

    if not candidates:
        return None
    # Prefer lakh/crore-scale amounts when present
    large = [c for c in candidates if c >= 100_000]
    return max(large) if large else max(candidates)


def detect_currency(text: str) -> str:
    for rx, code in _CURRENCY_HINTS:
        if rx.search(text):
            return code
    return "INR"


def detect_transaction_type(text: str) -> Optional[str]:
    for rx, label in _TX_TYPES:
        if rx.search(text):
            return label
    return None


def extract_deadline(text: str) -> Optional[str]:
    m = _DEADLINE_RE.search(text)
    return m.group(0) if m else None


def extract_beneficiary(text: str) -> Optional[str]:
    m = _BENEFICIARY_RE.search(text)
    if not m:
        return None
    hint = m.groupdict().get("hint") or m.groupdict().get("acct")
    if not hint:
        return None
    return re.sub(r"\s+", " ", hint).strip()[:80]


def extract_claims(text: str) -> tuple[Optional[str], Optional[str]]:
    identity: Optional[str] = None
    role: Optional[str] = None

    role_map = {
        "सीईओ": "CEO",
        "सीएफओ": "CFO",
        "प्रबंध निदेशक": "Managing Director",
        "md": "MD",
        "managing director": "Managing Director",
    }

    for rx in _CLAIM_IDENTITY_RE:
        m = rx.search(text)
        if not m:
            continue
        name = (m.groupdict().get("name") or "").strip()
        # Skip role-only false names ("the CFO" captured as identity elsewhere).
        if name and name.lower() not in {
            "the", "your", "ceo", "cfo", "cto", "md", "managing", "director",
        }:
            identity = name
        if m.groupdict().get("role"):
            raw = m.group("role")
            role = role_map.get(raw.lower(), raw.upper() if len(raw) <= 3 else raw.title())
        if identity:
            break

    for rx in _CLAIM_ROLE_RE:
        m = rx.search(text)
        if m:
            raw = m.group("role")
            role = role_map.get(raw.lower(), raw.upper() if len(raw) <= 3 else raw.title())
            break

    return identity, role


def extract(text: str) -> AskExtraction:
    """Pull structured ask + identity claims from a (possibly redacted) transcript."""
    if not text or not text.strip():
        return AskExtraction()

    amount = parse_amount(text)
    tx = detect_transaction_type(text)
    currency = detect_currency(text)
    deadline = extract_deadline(text)
    beneficiary = extract_beneficiary(text)
    identity, role = extract_claims(text)

    ask_detected = bool(
        (amount is not None and amount >= 1000)
        or tx in {"WIRE_TRANSFER", "UPI", "CRYPTO", "GIFT_CARD", "OTP_SHARE", "CREDENTIAL_SHARE"}
    )

    return AskExtraction(
        type=tx or ("WIRE_TRANSFER" if ask_detected and amount else None),
        amount=amount,
        currency=currency if ask_detected else None,
        beneficiaryHint=beneficiary,
        deadline=deadline,
        claimedIdentity=identity,
        claimedRole=role,
        askDetected=ask_detected,
    )


def extract_as_dict(text: str) -> dict[str, Any]:
    return asdict(extract(text))
