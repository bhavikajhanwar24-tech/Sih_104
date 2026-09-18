"""Dataset loaders for the evaluation harness (Context §15.1).

Wraps ``training.dataset`` loaders and adds codec-degraded + fairness group metadata.
Use ``--limit`` for smoke tests. When corpora are absent, ``synthetic=True`` builds a
deterministic proxy that is clearly labelled — never publish synthetic EER as a field result.
"""

from __future__ import annotations

import hashlib
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator, Literal, Optional

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from training.dataset import (  # noqa: E402
    DEFAULT_DATASETS,
    Sample,
    generate_synthetic_corpus,
    load_asvspoof2019_la,
    load_asvspoof2021_df,
    load_audio,
    load_in_the_wild,
)

ChannelCondition = Literal[
    "clean_16k",
    "opus_24k",
    "g711_ulaw_8k",
    "amr_nb_12k2",
    "webrtc_wideband",
    "pstn_narrowband",
]

DATASET_IDS = (
    "asvspoof2019_la_eval",
    "asvspoof2021_df_eval",
    "in_the_wild",
    "codec_degraded",
)

# Fairness groups (§13.5) — when metadata is absent we still emit the schema with
# synthetic proxy groups so the compliance portal chart has a stable contract.
FAIRNESS_GROUPS = (
    "indo_aryan",
    "dravidian",
    "gender_f",
    "gender_m",
    "age_young",
    "age_senior",
)


@dataclass
class EvalItem:
    sample: Sample
    dataset_id: str
    channel_condition: ChannelCondition
    fairness_group: str = "unspecified"
    dataset_version: str = "unknown"


@dataclass
class DatasetBundle:
    dataset_id: str
    version: str
    items: list[EvalItem] = field(default_factory=list)
    source: str = "disk"  # disk | synthetic
    note: str = ""

    @property
    def n(self) -> int:
        return len(self.items)


def _version_stamp(paths: list[Path], fallback: str) -> str:
    h = hashlib.sha256()
    h.update(fallback.encode())
    for p in sorted(paths)[:50]:
        h.update(str(p).encode())
        if p.is_file():
            h.update(str(p.stat().st_size).encode())
    return f"{fallback}@{h.hexdigest()[:12]}"


def _fairness_for(sample: Sample, idx: int) -> str:
    # Prefer speaker-id heuristics; otherwise rotate so each group sees both labels
    # (synthetic corpora alternate bona/spoof by index).
    sid = (sample.speaker_id or "-").lower()
    if any(x in sid for x in ("hi", "bn", "mr", "gu", "pa")):
        return "indo_aryan"
    if any(x in sid for x in ("ta", "te", "kn", "ml")):
        return "dravidian"
    return FAIRNESS_GROUPS[(idx // 2) % len(FAIRNESS_GROUPS)]


def load_dataset(
    dataset_id: str,
    *,
    root: Optional[Path] = None,
    limit: Optional[int] = None,
    synthetic: bool = False,
    channel_condition: ChannelCondition = "clean_16k",
) -> DatasetBundle:
    root = Path(root) if root else DEFAULT_DATASETS
    samples: list[Sample] = []
    version = "missing"
    source = "disk"
    note = ""

    if dataset_id == "asvspoof2019_la_eval":
        samples = load_asvspoof2019_la(root, "eval", limit=limit)
        version = _version_stamp([s.path for s in samples], "ASVspoof2019-LA-eval")
    elif dataset_id == "asvspoof2021_df_eval":
        samples = load_asvspoof2021_df(root, limit=limit)
        version = _version_stamp([s.path for s in samples], "ASVspoof2021-DF-eval")
    elif dataset_id == "in_the_wild":
        samples = load_in_the_wild(root, limit=limit)
        version = _version_stamp([s.path for s in samples], "InTheWild-Fraunhofer")
    elif dataset_id == "codec_degraded":
        # Prefer a dedicated codec-degraded tree; else reuse LA eval paths tagged as codec.
        codec_root = root / "codec_degraded"
        if codec_root.is_dir():
            wavs = sorted(codec_root.rglob("*.wav")) + sorted(codec_root.rglob("*.flac"))
            for i, p in enumerate(wavs):
                # Filename convention: {id}_{bonafide|spoof}_*.wav
                name = p.stem.lower()
                label = 0 if "bonafide" in name or "genuine" in name else 1
                samples.append(Sample(p, label, "eval", "codec_degraded"))  # type: ignore[arg-type]
                if limit is not None and len(samples) >= limit:
                    break
            version = _version_stamp([s.path for s in samples], "codec-degraded-ffmpeg")
        else:
            samples = load_asvspoof2019_la(root, "eval", limit=limit)
            version = _version_stamp([s.path for s in samples], "ASVspoof2019-LA-as-codec-proxy")
            note = "codec_degraded/ missing — using ASVspoof 2019 LA eval as path proxy; apply channel_condition in scorer"
    else:
        raise ValueError(f"unknown dataset_id={dataset_id}")

    if not samples and synthetic:
        syn_root = root / "_synthetic_antispoof"
        n = limit or 80
        syn = generate_synthetic_corpus(
            syn_root,
            n_train=max(40, n),
            n_dev=max(20, n // 2),
            n_eval=max(40, n),
            n_itw=max(40, n),
            seed=0,
        )
        if dataset_id == "in_the_wild":
            samples = list(syn.get("itw") or [])
        else:
            # ASVspoof / codec cells share the in-domain synthetic eval split.
            samples = list(syn.get("eval") or [])
        if limit is not None:
            samples = samples[:limit]
        source = "synthetic"
        version = f"synthetic@{dataset_id}"
        note = (
            "SYNTHETIC SMOKE CORPUS — not a published result. "
            "Context §3.1: report real In-the-Wild / ASVspoof numbers when corpora are present."
        )

    items = [
        EvalItem(
            sample=s,
            dataset_id=dataset_id,
            channel_condition=channel_condition,
            fairness_group=_fairness_for(s, i),
            dataset_version=version,
        )
        for i, s in enumerate(samples)
    ]
    return DatasetBundle(
        dataset_id=dataset_id,
        version=version,
        items=items,
        source=source,
        note=note,
    )


def iter_eval_matrix(
    *,
    root: Optional[Path] = None,
    limit: Optional[int] = None,
    synthetic: bool = False,
    datasets: Optional[list[str]] = None,
    channels: Optional[list[ChannelCondition]] = None,
) -> Iterator[DatasetBundle]:
    """Yield DatasetBundle for each dataset × channel cell."""
    ds_ids = datasets or list(DATASET_IDS)
    chans: list[ChannelCondition] = channels or [
        "clean_16k",
        "opus_24k",
        "g711_ulaw_8k",
        "amr_nb_12k2",
    ]
    telephony: set[str] = {"opus_24k", "g711_ulaw_8k", "amr_nb_12k2"}
    for ds in ds_ids:
        for ch in chans:
            if ds == "codec_degraded":
                # Dedicated degraded set: only telephony conditions (no clean).
                if ch not in telephony:
                    continue
                yield load_dataset(
                    ds, root=root, limit=limit, synthetic=synthetic, channel_condition=ch
                )
            else:
                yield load_dataset(
                    ds, root=root, limit=limit, synthetic=synthetic, channel_condition=ch
                )


def audio_for_item(item: EvalItem, target_sr: int = 16000) -> np.ndarray:
    """Load PCM; channel_condition may down/resample for PSTN-like stress without ffmpeg."""
    pcm = load_audio(item.sample.path, target_sr)
    ch = item.channel_condition
    if ch in ("g711_ulaw_8k", "amr_nb_12k2", "pstn_narrowband"):
        # Crude telephony: downsample to 8 kHz then back (spectral wipe of >4 kHz).
        n8 = max(1, int(round(pcm.size * 8000 / float(target_sr))))
        x = np.linspace(0.0, 1.0, pcm.size)
        low = np.interp(np.linspace(0.0, 1.0, n8), x, pcm)
        pcm = np.interp(np.linspace(0.0, 1.0, pcm.size), np.linspace(0.0, 1.0, n8), low).astype(np.float32)
        # Mild quantisation noise approximating µ-law.
        pcm = (np.round(pcm * 127.0) / 127.0).astype(np.float32)
    elif ch == "opus_24k":
        # Mild band-limit + noise as a stand-in when ffmpeg Opus cache is absent.
        n = max(1, int(round(pcm.size * 12000 / float(target_sr))))
        x = np.linspace(0.0, 1.0, pcm.size)
        mid = np.interp(np.linspace(0.0, 1.0, n), x, pcm)
        pcm = np.interp(np.linspace(0.0, 1.0, pcm.size), np.linspace(0.0, 1.0, n), mid).astype(np.float32)
    return pcm


def bundle_manifest(bundle: DatasetBundle) -> dict[str, Any]:
    return {
        "dataset_id": bundle.dataset_id,
        "version": bundle.version,
        "n": bundle.n,
        "source": bundle.source,
        "note": bundle.note,
        "channel_condition": bundle.items[0].channel_condition if bundle.items else None,
    }
