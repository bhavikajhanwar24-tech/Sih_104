"""P10.2 linguistic intent — lexicon + semantic hybrid, ask extraction."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from app.modules import extractor, intent

FIXTURE_PATH = Path(__file__).resolve().parent / "fixtures" / "transcripts" / "labelled.yaml"


@pytest.fixture(scope="module")
def fixtures() -> list[dict]:
    data = yaml.safe_load(FIXTURE_PATH.read_text(encoding="utf-8"))
    assert isinstance(data, list) and len(data) >= 20
    return data


@pytest.fixture(scope="module")
def scored(fixtures: list[dict]) -> dict[str, intent.IntentResult]:
    # Lexicon is authoritative for labelled fixtures; semantic is best-effort.
    # use_semantic=True still works offline via the token-overlap fallback.
    out: dict[str, intent.IntentResult] = {}
    for row in fixtures:
        out[row["id"]] = intent.score_text(row["text"], use_semantic=True)
    return out


def test_lexicon_loads_all_languages() -> None:
    entries = intent.load_lexicon()
    assert len(entries) >= 80
    cats = {e.category for e in entries}
    assert cats == set(intent.CATEGORIES)


def test_secrecy_threshold_is_lowest() -> None:
    # Documented product choice — secrecy fires earlier than other axes.
    assert intent._FIRE_THRESHOLD["secrecy"] < intent._FIRE_THRESHOLD["urgency"]
    assert intent._FIRE_THRESHOLD["secrecy"] < intent._FIRE_THRESHOLD["authority"]


def test_all_twenty_fixtures_category_label(
    fixtures: list[dict], scored: dict[str, intent.IntentResult]
) -> None:
    assert len(fixtures) >= 20
    for row in fixtures:
        result = scored[row["id"]]
        got = intent.classify_label(result)
        assert got == row["label"], (
            f"{row['id']}: expected {row['label']} got {got} "
            f"(u={result.urgency:.2f} s={result.secrecy:.2f} "
            f"a={result.authority:.2f} c={result.coercion:.2f})"
        )


def test_expected_categories_fire(
    fixtures: list[dict], scored: dict[str, intent.IntentResult]
) -> None:
    for row in fixtures:
        result = scored[row["id"]]
        for cat in row.get("expect_fired") or []:
            assert result.fired[cat], (
                f"{row['id']}: expected {cat} to fire, "
                f"score={getattr(result, cat if cat != 'authority' else 'authority'):.3f}"
            )
        for cat, ceiling in (row.get("expect_low") or {}).items():
            val = getattr(result, cat if cat != "authority" else "authority")
            assert val < float(ceiling), (
                f"{row['id']}: expected {cat} < {ceiling}, got {val:.3f}"
            )


def test_benign_urgent_high_urgency_low_secrecy(
    fixtures: list[dict], scored: dict[str, intent.IntentResult]
) -> None:
    benign_urgent = [
        r
        for r in fixtures
        if r["label"] == "benign" and "urgency" in (r.get("expect_fired") or [])
    ]
    assert len(benign_urgent) >= 5
    for row in benign_urgent:
        result = scored[row["id"]]
        assert result.urgency > 0.6, f"{row['id']} urgency={result.urgency}"
        assert result.secrecy < 0.2, f"{row['id']} secrecy={result.secrecy}"
        assert result.authority < 0.25, f"{row['id']} authority={result.authority}"


def test_amount_extraction_indian_forms() -> None:
    cases = [
        ("Please transfer 50 lakh immediately", 5_000_000.0),
        ("Wire 50,00,000 to the vendor", 5_000_000.0),
        ("Send fifty lakhs before cob", 5_000_000.0),
        ("पचास लाख भेजो", 5_000_000.0),
        ("Need 5 million today", 5_000_000.0),
        ("Release 2 crore NEFT", 20_000_000.0),
        ("Pay 3.5 lakh", 350_000.0),
    ]
    for text, expected in cases:
        got = extractor.parse_amount(text)
        assert got == expected, f"{text!r}: got {got}, expected {expected}"


def test_ask_extraction_on_fraud_fixture(scored: dict[str, intent.IntentResult]) -> None:
    result = scored["fraud_en_cfo_wire"]
    assert result.ask["askDetected"] is True
    assert result.ask["ask"]["amount"] == 5_000_000.0
    assert result.ask["ask"]["currency"] == "INR"


def test_hinglish_grandparent_coercion_and_urgency(
    scored: dict[str, intent.IntentResult],
) -> None:
    result = scored["fraud_hinglish_grandparent"]
    assert result.fired["coercion"]
    assert result.fired["urgency"]
    assert result.coercion >= 0.35
    assert result.urgency >= 0.35


def test_claimed_identity_and_role() -> None:
    ext = extractor.extract(
        "This is Rajesh Kumar, your CFO. Transfer 50 lakh immediately."
    )
    assert ext.claimedRole in {"CFO", "Cfo"} or (ext.claimedRole or "").upper() == "CFO"
    assert ext.amount == 5_000_000.0


def test_recency_weighting_prefers_recent_secrecy() -> None:
    # Old secrecy mention should decay; recent one dominates.
    old_only = intent.score_segments(
        [("Don't tell your manager about this transfer", 90.0)],
        use_semantic=False,
    )
    recent = intent.score_segments(
        [
            ("Don't tell your manager about this transfer", 90.0),
            ("Keep this between us, verbal approval only", 2.0),
        ],
        use_semantic=False,
    )
    assert recent.secrecy > old_only.secrecy
    assert recent.secrecy >= 0.5


def test_score_returns_evidence_source() -> None:
    result = intent.score_text(
        "Keep this between us and don't tell your manager",
        use_semantic=False,
    )
    ev = result.evidence["secrecy"]
    assert ev.source == "lexicon"
    assert ev.lexicon_score >= ev.score - 1e-9
    assert ev.hits


def test_warmup_reports_lexicon() -> None:
    info = intent.warmup()
    assert info["lexicon_entries"] >= 80
    assert "secrecy_threshold" in info
