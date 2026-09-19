"""Unit tests for hardened disk loaders (no fabricated fairness tags)."""

from __future__ import annotations

from pathlib import Path

import numpy as np

from training.dataset import (
    Sample,
    assert_speaker_disjoint,
    fairness_tags,
    generate_synthetic_corpus,
    language_family,
    primary_fairness_group,
    resolve_splits,
    speaker_disjoint_cap,
)


def test_synthetic_explicitly_labelled(tmp_path: Path) -> None:
    syn = generate_synthetic_corpus(tmp_path / "syn", n_train=8, n_dev=4, n_eval=4, n_itw=4, seed=0)
    assert all(s.source == "synthetic" for s in syn["train"])
    assert all(s.source == "synthetic" for s in syn["eval"])


def test_resolve_splits_no_silent_synthetic(tmp_path: Path) -> None:
    empty = tmp_path / "empty_datasets"
    empty.mkdir()
    splits = resolve_splits(empty, limit=10, synthetic=False)
    assert splits["train"] == []
    assert splits["dev"] == []


def test_resolve_splits_synthetic_flag(tmp_path: Path) -> None:
    splits = resolve_splits(tmp_path, limit=20, synthetic=True, synthetic_dir=tmp_path / "_syn")
    assert len(splits["train"]) > 0
    assert all(s.source == "synthetic" for s in splits["train"])


def test_speaker_disjoint_cap() -> None:
    samples = [
        Sample(Path("a.wav"), 0, "train", "t", speaker_id="spk1"),
        Sample(Path("b.wav"), 1, "train", "t", speaker_id="spk2"),
        Sample(Path("c.wav"), 0, "train", "t", speaker_id="spk1"),
    ]
    capped = speaker_disjoint_cap(samples, limit=10, forbidden_speakers={"spk1"})
    assert all(s.speaker_id != "spk1" for s in capped)
    assert len(capped) == 1


def test_assert_speaker_disjoint_raises() -> None:
    a = [Sample(Path("a.wav"), 0, "train", "t", speaker_id="x")]
    b = [Sample(Path("b.wav"), 1, "dev", "t", speaker_id="x")]
    try:
        assert_speaker_disjoint(a, b)
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_fairness_tags_from_cv_metadata_only() -> None:
    s = Sample(
        Path("x.mp3"),
        0,
        "eval",
        "common_voice",
        language="hi",
        gender="female",
        age="twenties",
    )
    tags = fairness_tags(s)
    assert tags["language_family"] == "indo_aryan"
    assert tags["gender"] == "gender_f"
    assert tags["age"] == "age_young"
    assert primary_fairness_group(s) == "indo_aryan"
    assert language_family("ta") == "dravidian"

    bare = Sample(Path("y.wav"), 1, "eval", "asvspoof2019_la")
    assert fairness_tags(bare) == {}
    assert primary_fairness_group(bare) == "unspecified"


def test_load_audio_wav_roundtrip(tmp_path: Path) -> None:
    from training.dataset import _write_wav, load_audio

    path = tmp_path / "t.wav"
    audio = (0.1 * np.sin(2 * np.pi * 220 * np.arange(16000) / 16000)).astype(np.float32)
    _write_wav(path, audio, 16000)
    got = load_audio(path, 16000)
    assert got.shape == audio.shape
