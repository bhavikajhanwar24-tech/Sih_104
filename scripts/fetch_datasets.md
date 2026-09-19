# Fetch evaluation / training corpora

**This document is layout + licence guidance only.** SentinelVoice does **not**
download corpora inside the app, the ML service, or CI. Place files under a
gitignored `datasets/` directory (repo root preferred; `ml-engine/datasets/` also works).

After placement, train / evaluate **without** `--synthetic`:

```bash
cd ml-engine
python -m training.train_antispoof --datasets ../datasets --epochs 20
python -m training.calibrate --checkpoint models/antispoof/codec_aug.pt --datasets ../datasets
python -m benchmarks.run_eval --datasets ../datasets --seed 42
python -m benchmarks.codec_study --datasets ../datasets --seed 42
python -m benchmarks.fairness --datasets ../datasets
```

If corpora are missing, those commands **exit** (or leave report cells labelled
`synthetic` / `missing`). They must not invent `source=disk` numbers.

---

## Directory layout (canonical)

```text
datasets/
├── ASVspoof2019/
│   └── LA/
│       ├── ASVspoof2019_LA_cm_protocols/
│       │   ├── ASVspoof2019.LA.cm.train.trn.txt
│       │   ├── ASVspoof2019.LA.cm.dev.trl.txt
│       │   └── ASVspoof2019.LA.cm.eval.trl.txt
│       ├── ASVspoof2019_LA_train/flac/   # or wav/
│       ├── ASVspoof2019_LA_dev/flac/
│       └── ASVspoof2019_LA_eval/flac/
├── ASVspoof2021/
│   └── DF/
│       ├── ASVspoof2021_DF_cm_protocols/
│       │   └── ASVspoof2021.DF.cm.eval.trl.txt
│       └── ASVspoof2021_DF_eval/flac/
├── release_in_the_wild/
│   ├── meta.csv          # columns: file, speaker, label
│   └── *.wav | *.flac
├── common_voice/
│   ├── hi/
│   │   ├── clips/
│   │   └── validated.tsv   # or train.tsv (TSV with path, client_id, age, gender, locale)
│   ├── mr/
│   ├── bn/
│   ├── ta/
│   └── te/
└── codec_degraded/         # optional; else LA eval + channel_condition proxy
```

Alternate accepted roots (loaders probe both):

- `datasets/ASVspoof2019_LA/…`
- `datasets/ASVspoof2021_DF/…`
- `datasets/in_the_wild/` (instead of `release_in_the_wild/`)
- `datasets/cv-corpus-*/{hi,mr,…}/` (Mozilla zip layout)

Synthetic smoke (explicit only): `datasets/_synthetic_antispoof/` written by
`--synthetic` — always labelled `source=synthetic`.

---

## Corpora & licences

| Corpus | Role | Where to obtain | Licence / terms (check upstream) |
|---|---|---|---|
| **ASVspoof 2019 LA** | Train / DEV / in-domain eval | [Edinburgh DataShare — ASVspoof 2019](https://datashare.ed.ac.uk/handle/10283/3336) | Research use per DataShare terms; cite Todisco et al., Interspeech 2019 |
| **ASVspoof 2021 DF** | Codec / compression eval | ASVspoof 2021 challenge DataShare mirrors | Research use per challenge terms; cite ASVspoof 2021 papers |
| **In-the-Wild** (Fraunhofer AISEC) | Honest out-of-domain deepfake test | [github.com/AI-Secure/In-the-Wild](https://github.com/AI-Secure/In-the-Wild) / Müller et al. release | Per upstream README; cite Müller et al. |
| **Mozilla Common Voice** (`hi`, `mr`, `bn`, `ta`, `te`) | Bonafide fairness probe (language / gender / age TSV tags) | [commonvoice.mozilla.org/datasets](https://commonvoice.mozilla.org/datasets) | **CC-0** for released clips; respect Mozilla download ToS |
| **Codec-degraded set** | Telephony stress | Generate locally via `python -m benchmarks.build_codec_set` (ffmpeg) | Same licence as source clips |

Always keep a local note of the exact download date + archive checksum for your
own reproducibility log. Do **not** commit audio into git.

---

## Common Voice TSV columns used

From `validated.tsv` / `train.tsv` (tab-separated):

- `path` → file under `clips/`
- `client_id` → speaker id (fairness / disjoint checks)
- `locale` → language (`hi` / `mr` / `bn` / `ta` / `te`)
- `gender` → `female` / `male` (mapped to `gender_f` / `gender_m`)
- `age` → Common Voice age buckets (`twenties`, `sixties`, … → young / senior)

If a column is blank, that dimension is reported as `unspecified` — **never
fabricated**.

---

## Speaker-disjoint training

ASVspoof 2019 LA official protocols are already speaker-disjoint across
train / DEV / eval. `training.train_antispoof` loads those protocols and keeps
disjointness under `--limit`. Platt calibration (`training.calibrate`) fits
**DEV only**, never eval.
