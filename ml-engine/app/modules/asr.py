"""Streaming multilingual ASR via faster-whisper (Context §7.2 / §10.6).

Slow-path only. Hindi/English code-switch with language=None auto-detect.
REDACTION IS MANDATORY before any transcript leaves this process (§13.2 control 6).
Unredacted text is never returned, logged, or persisted.
"""

from __future__ import annotations

import logging
import re
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Optional

import numpy as np

from app.config import settings
from app.vad import speech_ratio

logger = logging.getLogger("sentinelvoice.ml.asr")

# Known Whisper silence / subtitle-corpus hallucinations (match case-insensitively).
_HALLUCINATION_EXACT = frozenset(
    {
        "",
        ".",
        "..",
        "...",
        "thank you",
        "thank you.",
        "thanks",
        "thanks.",
        "thanks for watching",
        "thanks for watching.",
        "thank you for watching",
        "thank you for watching.",
        "please subscribe",
        "subscribe",
        "subtitles by the amara.org community",
        "subtitulos realizados por la comunidad de amara.org",
        "www.amara.org",
        "amara.org",
        "mbc news",
        "the end",
        "you",
        "you.",
        "music",
        "applause",
        "laughter",
        "foreign",
    }
)

_HALLUCINATION_PREFIXES = (
    "subtitles by",
    "translated by",
    "subtitle",
    "www.",
)

# Redaction patterns — applied in order; replacements never re-matched as digits.
_EMAIL_RE = re.compile(
    r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b"
)
# Aadhaar often spoken/written as 4-4-4 groups.
_AADHAAR_GROUPED_RE = re.compile(
    r"(?<!\d)(\d{4}[\s\-.]?\d{4}[\s\-.]?\d{4})(?!\d)"
)
# Card candidates: 13–19 digits with optional separators; Luhn-checked.
_CARD_RE = re.compile(
    r"(?<!\d)((?:\d[ \-]*?){13,19})(?!\d)"
)
# Indian / general phone patterns.
# Keep tight: bare digit runs of length ≠ 10 must fall through to ACCOUNT/AADHAAR/CARD.
_PHONE_RE = re.compile(
    r"(?<!\d)("
    # Indian mobile: optional +91/91/0, then exactly 10 digits starting 6–9 (separators ok)
    r"(?:\+91[\s\-]*|91[\s\-]*|0[\s\-]*)?[6-9](?:[\s\-]?\d){9}"
    # Explicit +international (E.164-ish)
    r"|\+\d{8,15}"
    # Parenthesised area code forms: (022) 1234 5678
    r"|\(\d{2,5}\)[\s\-]?\d{3,4}[\s\-]?\d{3,4}"
    r")(?!\d)"
)
# Remaining long digit runs (bank accounts etc.).
_ACCOUNT_RE = re.compile(r"(?<!\d)\d{9,}(?!\d)")

_model: Any = None
_model_size: Optional[str] = None
_device: Optional[str] = None
_compute_type: Optional[str] = None
_warmup_ms: Optional[float] = None
_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="asr-whisper")


@dataclass
class WordStamp:
    word: str
    start: float
    end: float
    language: Optional[str] = None


@dataclass
class AsrSessionState:
    """Per-call rolling transcript. Unredacted fields stay in-process only."""

    session_id: str
    _rolling_raw: str = ""
    _last_window_raw: str = ""
    _emitted_raw_len: int = 0
    languages_seen: list[str] = field(default_factory=list)
    last_segment_language: Optional[str] = None
    code_switch_detected: bool = False
    words: list[WordStamp] = field(default_factory=list)
    last_latency_ms: float = 0.0
    last_available: bool = False
    last_updated_monotonic: float = 0.0
    # Snapshot safe to publish (already redacted).
    last_delta_redacted: str = ""
    last_snippet_redacted: str = ""
    last_language_label: str = "und"


@dataclass
class AsrTickResult:
    """Public ASR tick output — redacted fields only.

    Word-level timestamps stay on AsrSessionState.words (in-process, unredacted).
    This result never carries unredacted tokens — only words_redacted.
    """

    available: bool
    delta_redacted: str
    redacted_snippet: str
    language: str
    code_switch_detected: bool
    latency_ms: float
    speech_ratio: float
    skipped_reason: Optional[str] = None
    # Redacted word stamps only (PII masked). Empty when skipped / unavailable.
    words_redacted: list[WordStamp] = field(default_factory=list)


def _luhn_ok(digits: str) -> bool:
    if not digits.isdigit() or not (13 <= len(digits) <= 19):
        return False
    total = 0
    alt = False
    for ch in reversed(digits):
        n = ord(ch) - 48
        if alt:
            n *= 2
            if n > 9:
                n -= 9
        total += n
        alt = not alt
    return total % 10 == 0


def _mask_cards(text: str) -> str:
    def _repl(m: re.Match[str]) -> str:
        digits = re.sub(r"\D", "", m.group(1))
        if _luhn_ok(digits):
            return "[CARD]"
        return m.group(0)

    return _CARD_RE.sub(_repl, text)


def _mask_aadhaar(text: str) -> str:
    def _repl(m: re.Match[str]) -> str:
        digits = re.sub(r"\D", "", m.group(1))
        if len(digits) == 12:
            return "[AADHAAR]"
        return m.group(0)

    return _AADHAAR_GROUPED_RE.sub(_repl, text)


def redact(text: str) -> str:
    """Mask PII before any transcript leaves Python (Context §13.2 control 6)."""
    if not text:
        return ""
    out = _EMAIL_RE.sub("[EMAIL]", text)
    out = _mask_cards(out)
    out = _mask_aadhaar(out)
    out = _PHONE_RE.sub("[PHONE]", out)
    out = _ACCOUNT_RE.sub("[ACCOUNT]", out)
    return out


def _is_hallucination(text: str) -> bool:
    cleaned = " ".join(text.strip().split())
    if not cleaned:
        return True
    low = cleaned.lower()
    if low in _HALLUCINATION_EXACT:
        return True
    if any(low.startswith(p) for p in _HALLUCINATION_PREFIXES):
        return True
    # Single punctuation / very short filler
    if len(low) <= 2 and not any(c.isalnum() for c in low):
        return True
    return False


def _filter_hallucinations(text: str) -> str:
    if _is_hallucination(text):
        return ""
    # Drop hallucinated sentences inside a longer string.
    parts = re.split(r"(?<=[.!?])\s+", text)
    kept = [p for p in parts if p and not _is_hallucination(p)]
    return " ".join(kept).strip()


def _novel_suffix(previous: str, current: str) -> str:
    """Return the portion of ``current`` not already covered by ``previous`` (overlap)."""
    prev = previous.strip()
    cur = current.strip()
    if not cur:
        return ""
    if not prev:
        return cur
    if cur.startswith(prev):
        return cur[len(prev) :].lstrip()
    max_k = min(len(prev), len(cur))
    for k in range(max_k, 0, -1):
        if prev[-k:] == cur[:k]:
            return cur[k:].lstrip()
    # Soft fallback: token-level overlap on the trailing half of prev.
    prev_toks = prev.split()
    cur_toks = cur.split()
    if not cur_toks:
        return ""
    max_n = min(len(prev_toks), len(cur_toks))
    for n in range(max_n, 0, -1):
        if prev_toks[-n:] == cur_toks[:n]:
            return " ".join(cur_toks[n:]).strip()
    return cur


def _snippet(redacted_full: str, max_chars: Optional[int] = None) -> str:
    n = max_chars if max_chars is not None else settings.asr_snippet_chars
    text = " ".join(redacted_full.split())
    if len(text) <= n:
        return text
    return "…" + text[-(n - 1) :]


def _redact_words(words: list[WordStamp]) -> list[WordStamp]:
    """Return word stamps with token text PII-masked (timestamps unchanged)."""
    out: list[WordStamp] = []
    for w in words:
        out.append(
            WordStamp(
                word=redact(w.word),
                start=w.start,
                end=w.end,
                language=w.language,
            )
        )
    return out


def _resolve_device_compute() -> tuple[str, str]:
    device = (settings.asr_device or "auto").lower()
    compute = (settings.asr_compute_type or "default").lower()
    if device == "auto":
        try:
            import ctranslate2

            device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
        except Exception:
            device = "cpu"
    if compute == "default":
        compute = "float16" if device == "cuda" else "int8"
    return device, compute


def is_ready() -> bool:
    return _model is not None


def warmup_ms() -> Optional[float]:
    return _warmup_ms


def model_info() -> dict[str, Any]:
    return {
        "ready": _model is not None,
        "model_size": _model_size,
        "device": _device,
        "compute_type": _compute_type,
        "warmup_ms": _warmup_ms,
    }


_import_failed: bool = False
_import_error: Optional[str] = None


def warmup(force: bool = False) -> dict[str, Any]:
    """Load faster-whisper once at startup; log size/device/compute."""
    global _model, _model_size, _device, _compute_type, _warmup_ms
    global _import_failed, _import_error

    if _model is not None and not force:
        return model_info()
    if _import_failed and not force:
        return {"ready": False, "error": _import_error or "import_failed"}

    size = settings.asr_model_size
    device, compute = _resolve_device_compute()
    t0 = time.perf_counter()
    try:
        from faster_whisper import WhisperModel
    except Exception as exc:
        _import_failed = True
        _import_error = type(exc).__name__
        logger.exception("asr_import_failed error=%s — will not retry until restart", _import_error)
        return {"ready": False, "error": _import_error}

    try:
        _model = WhisperModel(size, device=device, compute_type=compute)
    except Exception as exc:
        # CPU float16 is unsupported on some builds — fall back to int8.
        if device == "cpu" and compute != "int8":
            logger.warning(
                "asr_load_retry size=%s device=cpu compute=int8 prior_error=%s",
                size,
                type(exc).__name__,
            )
            compute = "int8"
            _model = WhisperModel(size, device="cpu", compute_type="int8")
        else:
            logger.exception("asr_load_failed size=%s device=%s", size, device)
            _model = None
            return {"ready": False, "error": type(exc).__name__}

    _model_size = size
    _device = device
    _compute_type = compute
    _warmup_ms = (time.perf_counter() - t0) * 1000.0
    logger.info(
        "asr_model_loaded size=%s device=%s compute_type=%s warmup_ms=%.1f",
        size,
        device,
        compute,
        _warmup_ms,
    )
    return model_info()


def reset_session_state(state: Optional[AsrSessionState] = None) -> None:
    """Zeroise in-memory transcript on session close (F11 privacy)."""
    if state is None:
        return
    state._rolling_raw = ""
    state._last_window_raw = ""
    state._emitted_raw_len = 0
    state.languages_seen.clear()
    state.last_segment_language = None
    state.code_switch_detected = False
    state.words.clear()
    state.last_latency_ms = 0.0
    state.last_available = False
    state.last_updated_monotonic = 0.0
    state.last_delta_redacted = ""
    state.last_snippet_redacted = ""
    state.last_language_label = "und"


def trim_rolling_window(state: AsrSessionState, horizon_s: Optional[float] = None) -> None:
    """Keep only the last ~horizon_s of word stamps + reconstructed raw text."""
    horizon = float(horizon_s if horizon_s is not None else settings.asr_rolling_seconds)
    if not state.words:
        # Character fallback ~ 12 chars/s speech
        max_chars = max(80, int(horizon * 14))
        if len(state._rolling_raw) > max_chars:
            state._rolling_raw = state._rolling_raw[-max_chars:]
        return
    end_t = state.words[-1].end
    cutoff = end_t - horizon
    kept = [w for w in state.words if w.end >= cutoff]
    state.words = kept
    state._rolling_raw = " ".join(w.word for w in kept).strip()
    state._emitted_raw_len = min(state._emitted_raw_len, len(state._rolling_raw))


def raw_rolling_text(state: AsrSessionState) -> str:
    """In-process only — never put on FeatureFrame."""
    return state._rolling_raw or ""



def _record_languages(state: AsrSessionState, langs: list[str]) -> None:
    for lang in langs:
        if not lang:
            continue
        norm = lang.lower().split("-")[0]
        if norm not in state.languages_seen:
            state.languages_seen.append(norm)
        if state.last_segment_language and norm != state.last_segment_language:
            # Hinglish segments flip between hi/en (and occasionally other Indic labels).
            pair = {state.last_segment_language, norm}
            if pair & {"hi", "en"} or len(pair) > 1:
                state.code_switch_detected = True
        state.last_segment_language = norm


def _language_label(state: AsrSessionState) -> str:
    if state.code_switch_detected and (
        {"hi", "en"} <= set(state.languages_seen)
        or len(set(state.languages_seen) & {"hi", "en"}) >= 1
        and len(state.languages_seen) > 1
    ):
        return "hi-en"
    if state.code_switch_detected and state.languages_seen:
        return "hi-en" if "hi" in state.languages_seen else "-".join(state.languages_seen[:2])
    if state.last_segment_language:
        return state.last_segment_language
    if state.languages_seen:
        return state.languages_seen[-1]
    return "und"


def _transcribe_sync(
    samples: np.ndarray,
    sr: int,
    state: AsrSessionState,
    initial_prompt: Optional[str] = None,
) -> AsrTickResult:
    """Blocking Whisper call — must run in a worker thread, never on the event loop."""
    audio = np.asarray(samples, dtype=np.float32).reshape(-1)
    ratio = float(speech_ratio(audio)) if audio.size else 0.0
    t0 = time.perf_counter()

    if ratio <= settings.asr_vad_speech_ratio_min:
        ms = (time.perf_counter() - t0) * 1000.0
        state.last_latency_ms = ms
        return AsrTickResult(
            available=False,
            delta_redacted="",
            redacted_snippet=_snippet(redact(state._rolling_raw)),
            language=_language_label(state),
            code_switch_detected=state.code_switch_detected,
            latency_ms=ms,
            speech_ratio=ratio,
            skipped_reason="vad_gate",
            words_redacted=_redact_words(state.words),
        )

    if _model is None:
        warmup()
    if _model is None:
        ms = (time.perf_counter() - t0) * 1000.0
        return AsrTickResult(
            available=False,
            delta_redacted="",
            redacted_snippet="",
            language="und",
            code_switch_detected=False,
            latency_ms=ms,
            speech_ratio=ratio,
            skipped_reason="model_unavailable",
        )

    # Resample to 16 kHz if needed (faster-whisper expects 16 kHz mono float32).
    if sr != 16000 and audio.size:
        n_out = int(round(audio.size * 16000 / float(sr)))
        x_old = np.linspace(0.0, 1.0, audio.size, dtype=np.float64)
        x_new = np.linspace(0.0, 1.0, max(n_out, 1), dtype=np.float64)
        audio = np.interp(x_new, x_old, audio.astype(np.float64)).astype(np.float32)

    prompt = initial_prompt
    if prompt is None and state._rolling_raw:
        # Continuity across overlapping windows; keep prompt short.
        prompt = state._rolling_raw[-220:]

    try:
        segments_iter, info = _model.transcribe(
            audio,
            language=None,  # auto-detect — required for Hinglish code-switch
            condition_on_previous_text=True,
            word_timestamps=True,
            vad_filter=False,  # we gate on our own VAD speech_ratio
            initial_prompt=prompt or None,
        )
        segments = list(segments_iter)
    except Exception as exc:
        ms = (time.perf_counter() - t0) * 1000.0
        logger.exception(
            "asr_transcribe_failed session_id=%s error=%s",
            state.session_id,
            type(exc).__name__,
        )
        return AsrTickResult(
            available=False,
            delta_redacted="",
            redacted_snippet=_snippet(redact(state._rolling_raw)),
            language=_language_label(state),
            code_switch_detected=state.code_switch_detected,
            latency_ms=ms,
            speech_ratio=ratio,
            skipped_reason=type(exc).__name__,
            words_redacted=_redact_words(state.words),
        )

    info_lang = getattr(info, "language", None)
    seg_texts: list[str] = []
    seg_langs: list[str] = []
    new_words: list[WordStamp] = []

    for seg in segments:
        raw = (seg.text or "").strip()
        if not raw:
            continue
        filtered = _filter_hallucinations(raw)
        if not filtered:
            continue
        seg_texts.append(filtered)
        lang = getattr(seg, "language", None) or info_lang
        if lang:
            seg_langs.append(str(lang))
        words = getattr(seg, "words", None) or []
        for w in words:
            token = (getattr(w, "word", None) or "").strip()
            if not token:
                continue
            new_words.append(
                WordStamp(
                    word=token,
                    start=float(getattr(w, "start", 0.0) or 0.0),
                    end=float(getattr(w, "end", 0.0) or 0.0),
                    language=str(lang) if lang else None,
                )
            )

    window_raw = " ".join(seg_texts).strip()
    window_raw = _filter_hallucinations(window_raw)

    if seg_langs:
        _record_languages(state, seg_langs)
    elif info_lang and window_raw:
        _record_languages(state, [str(info_lang)])

    delta_raw = _novel_suffix(state._last_window_raw, window_raw) if window_raw else ""
    # Prefer appending only novel text; if first window, take full.
    if not state._last_window_raw and window_raw:
        delta_raw = window_raw

    if delta_raw:
        if state._rolling_raw:
            state._rolling_raw = (state._rolling_raw + " " + delta_raw).strip()
        else:
            state._rolling_raw = delta_raw
        state.words.extend(new_words)
        # Cap in-memory word list (rolling, not persisted).
        if len(state.words) > 2000:
            del state.words[:-1000]
        trim_rolling_window(state)

    if window_raw:
        state._last_window_raw = window_raw

    # Emit only the redacted delta since last emission.
    emitted_before = state._emitted_raw_len
    new_portion_raw = state._rolling_raw[emitted_before:].lstrip()
    state._emitted_raw_len = len(state._rolling_raw)
    delta_redacted = redact(new_portion_raw)
    snippet = _snippet(redact(state._rolling_raw))
    lang_label = _language_label(state)

    ms = (time.perf_counter() - t0) * 1000.0
    state.last_latency_ms = ms
    state.last_available = bool(delta_redacted or snippet)
    state.last_updated_monotonic = time.monotonic()
    state.last_delta_redacted = delta_redacted
    state.last_snippet_redacted = snippet
    state.last_language_label = lang_label

    if ms > settings.asr_latency_budget_ms:
        logger.warning(
            "asr_slow session_id=%s latency_ms=%.1f budget_ms=%.1f size=%s",
            state.session_id,
            ms,
            settings.asr_latency_budget_ms,
            _model_size,
        )
    else:
        logger.info(
            "asr_tick session_id=%s latency_ms=%.1f speech_ratio=%.2f lang=%s "
            "code_switch=%s delta_chars=%d",
            state.session_id,
            ms,
            ratio,
            lang_label,
            state.code_switch_detected,
            len(delta_redacted),
        )

    return AsrTickResult(
        available=bool(snippet),
        delta_redacted=delta_redacted,
        redacted_snippet=snippet,
        language=lang_label,
        code_switch_detected=state.code_switch_detected,
        latency_ms=ms,
        speech_ratio=ratio,
        words_redacted=_redact_words(state.words),
    )


def transcribe_window(
    samples: np.ndarray,
    sr: int,
    state: AsrSessionState,
    initial_prompt: Optional[str] = None,
) -> AsrTickResult:
    """Public sync entry — safe to call from a thread pool."""
    return _transcribe_sync(samples, sr, state, initial_prompt=initial_prompt)


def get_executor() -> ThreadPoolExecutor:
    return _executor


def linguistic_from_state(
    state: AsrSessionState,
    *,
    now_monotonic: Optional[float] = None,
    lexicon: Any = None,
    stage_b_overlay: Optional[dict[str, Any]] = None,
    llm_pending: bool = False,
) -> dict[str, Any]:
    """Build a FeatureFrame ``linguistic`` block (F11 — numbers/enums only on the wire).

    Transcript text stays on ``AsrSessionState`` in-process; this payload is stripped
    of redactedSnippet/redactedDelta before emit.
    """
    from app.modules.privacy import strip_transcript_fields
    from app.modules.stage_a import run_stage_a

    if not state.last_available and not state._rolling_raw.strip():
        return {"available": False}

    now = now_monotonic if now_monotonic is not None else time.monotonic()
    age_ms = 0
    if state.last_updated_monotonic > 0:
        age_ms = max(0, int((now - state.last_updated_monotonic) * 1000.0))

    raw = state._rolling_raw or ""
    # Keep in-process redacted copies for debug endpoints that opt-in; not on FeatureFrame.
    state.last_snippet_redacted = redact(raw)[-settings.asr_snippet_chars :] if raw else ""

    stage_a = run_stage_a(raw, lexicon=lexicon)
    ling = stage_a.to_linguistic(
        language=state.last_language_label or "und",
        age_ms=age_ms,
    )
    if stage_b_overlay and stage_b_overlay.get("available"):
        # Prefer merged Stage B when fresher
        merged = dict(stage_b_overlay)
        merged["ageMs"] = age_ms
        merged["language"] = state.last_language_label or merged.get("language") or "und"
        ling = merged
    ling["llmPending"] = bool(llm_pending)
    return strip_transcript_fields(ling)

