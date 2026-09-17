# SentinelVoice — Master Project Context

**SIH Problem Statement:** SIH_104 (Real-Time Voice Cloning & Synthetic Speech Defense)
**Document purpose:** This is the single source of truth for *what* SentinelVoice is, *why* every design decision was made, and *what every component must do*. It is written to be read by (a) your team, (b) an SIH judge, and (c) **Cursor's AI, as a pinned context file**.
**Companion document:** `01_EXECUTION_PLAN.md` — the step-by-step build order with copy-paste Cursor prompts.
**Status as of writing:** Java backend skeleton exists (~1,400 LOC, ~8% complete and largely non-functional). No frontend. No ML engine. No call integration. See §18.

---

## 0. How to use this document

| Audience | Read |
|---|---|
| You, before building | §1–§6, §17 |
| Cursor (pin this file) | Everything. Reference it as `@00_PROJECT_CONTEXT.md` in every prompt. |
| Teammate joining Day 5 | §1, §6, §7, §8, §19 |
| Preparing for judge Q&A | §3, §15, §16 |
| Writing the pitch deck | §2, §3, §4, §11, §16 |

**Put this file at `docs/00_PROJECT_CONTEXT.md` in the repo.** Cursor indexes markdown in the workspace, and you can `@`-reference it so the AI stops inventing its own architecture mid-build. This single habit will save you more time than any other in this project.

---

## 1. One-page summary

SentinelVoice is a **real-time call-fraud interdiction system**, not a deepfake detector.

It sits on a live voice call — from a browser softphone, an enterprise PBX, or a PSTN carrier trunk — and continuously answers one question:

> *Is this specific call, in this specific context, about to cause a fraudulent irreversible action? And if so, can I stop it before the money moves?*

It does this by fusing **six independent evidence families** into a single calibrated risk score updated every 500 ms:

1. **Voice authenticity** — vocoder/synthesis artifacts in the signal itself
2. **Channel forensics** — does the audio's physical provenance match the claimed channel?
3. **Prosody & micro-behaviour** — breathing, jitter, shimmer, disfluency, turn-taking
4. **Linguistic intent** — urgency, secrecy, authority-invocation, and the *ask*
5. **Transaction context** — is this action consistent with policy and the caller's authority?
6. **Relationship graph** — has this caller ever spoken to this person before?

The score drives a **5-stage graduated intervention ladder** that escalates from silent logging → on-screen advisory → locking the approve button and firing out-of-band MFA → auto-hold + supervisor bridge → call termination and beneficiary-account freeze.

Everything runs with **zero raw-audio retention** — audio exists only in a bounded RAM ring buffer, is converted to non-invertible feature vectors, and the buffer is overwritten. Every decision is written to a **SHA-256 hash-chained audit ledger** so a bank's compliance team or a cyber-crime investigator can prove, after the fact, exactly what the system knew and when.

**The one-line pitch:**
> *Most teams will tell you whether a voice is fake. We tell you whether a call is about to cost you ₹50 lakh — and we stop it while the caller is still talking.*

---

## 2. The problem, with evidence

You need real numbers in the pitch. These are sourced, current as of 2026. **Cite the source on the slide** — judges check.

### 2.1 The threat is real and accelerating

- CrowdStrike recorded a 442% increase in voice phishing between the first and second halves of 2024, and vishing attacks using cloned voices surged 1,633% in Q1 2025.
- As little as 3 seconds of audio is needed to create a voice clone with 85% accuracy (McAfee).
- Over 10% of banks have suffered deepfake vishing losses above $1 million, with an average loss of $600K per incident.
- 70% of organizations have fallen victim to a voice phishing attack, and Deloitte projects GenAI-enabled fraud losses will reach $40 billion in the US by 2027.
- The single largest known case: the Arup deepfake scam cost $25.6 million.

### 2.2 India is disproportionately exposed — this is your SIH angle

- A McAfee survey cited in Observer Research Foundation analysis found 83% of Indian victims of AI voice scams suffered financial losses, while nearly half lost more than ₹50,000. 47% of Indian adults had either experienced an AI voice or deepfake scam or knew someone who had, while 69% said they could not confidently distinguish an AI-generated voice from a real one.
- Deepfake cases in India have surged 550% since 2019, with projected losses of ₹70,000 crore in 2024 alone. At 47%, the share of Indian adults personally touched by a voice-cloning or deepfake scam is nearly double the global average.
- India grapples with up to 15 million spoofed calls daily at peak.
- When DoT launched the International Incoming Spoofed Calls Prevention System in October 2024, about 1.35 crore incoming international calls displaying Indian phone numbers were identified as spoofed and blocked within the first 24 hours.

### 2.3 The regulatory moment — your strongest framing device

India launched **CNAP (Calling Name Presentation)** nationally. DoT directed Airtel, Jio and Vi to roll out CNAP across all telecom circles by March 2026, and it uses names registered during SIM issuance through mandatory KYC processes, typically linked to Aadhaar. The recipient's telecom provider queries the caller's operator for the registered name, and the verified name appears on screen alongside the number.

**This is the wedge for your entire pitch:**

> CNAP answers *"whose number is this?"*
> It cannot answer *"is the voice on this line actually that person?"*
> A fraudster who buys a KYC-verified SIM in a mule's name, or who clones a CEO's voice and calls from a perfectly legitimate number, defeats CNAP completely.
> **CNAP verifies the line. SentinelVoice verifies the speaker and the intent. They are complementary layers, and India has just built the first one.**

Put that on slide 2. It shows you understand the *current* Indian policy landscape, not a textbook version of it. Very few teams will.

---

## 3. Why existing detection fails — the scientific core of your project

This section is the intellectual backbone. **Learn it cold.** It is what separates a team that read a blog post from a team that read the literature.

### 3.1 The generalization collapse

Audio deepfake detectors look spectacular in papers and fall apart in reality.

- State-of-the-art detectors achieve below 2% equal error rate (EER) on ASVspoof 2019 LA, but performance degrades to over 20% EER on real-world data.
- The canonical study on this is brutal: on the "In-the-Wild" dataset, EER values of standard models deteriorate by roughly 200% to 1000%, and often the models do not perform better than random guessing. Training RawNet2 on the full ASVspoof 2019 set and re-evaluating in the wild yielded 33.1% EER — no improvement from the extra data.
- The gap is largely attributed to implicit identity leakage — detectors capturing speaker-specific characteristics rather than synthesis artifacts. Models exploit spurious speaker-label correlations instead of learning transferable artifact features, as further evidenced by the ASVspoof 5 challenge, where crowdsourced recordings with severe statistical mismatch caused widespread performance degradation.
- Independent industry measurement agrees: AI detection tools achieve up to 96% accuracy in lab conditions but drop 45–50% in real-world use.

### 3.2 What this means for you

Three consequences, and each one justifies a design decision:

**(a) A binary detector cannot be the product.** If your acoustic classifier is 20–33% EER in the wild, then at any useful sensitivity you are either missing a third of attacks or false-alarming on a third of legitimate customers. A bank cannot deploy that. *Therefore: fuse acoustic evidence with independent non-acoustic evidence (§9.3).* This is not a product gimmick — it is the mathematically correct response to a weak, poorly-calibrated base classifier.

**(b) Telephony destroys your best features.** The literature's clean-studio assumptions evaporate on a real call:
- PSTN narrowband is sampled at **8 kHz** → the signal has *no content above 4 kHz* (Nyquist). Every ">8 kHz spectral rolloff" feature in your original plan is **mathematically unavailable on a real phone call.** This is the single biggest technical error in the current plan document and you must fix it (§10.4).
- Codecs (G.711 µ-law/A-law, AMR-NB 4.75–12.2 kbps, AMR-WB, Opus, EVS) are *lossy and perceptually-motivated* — they discard exactly the fine spectral and phase structure your detector keys on.
- Packet loss, jitter buffers and comfort-noise generation inject artifacts that look like synthesis artifacts.

**(c) Therefore: you need dual operating profiles and codec-augmented training.** This is your genuine technical contribution and it is achievable in a hackathon (§10.4, §15.3).

### 3.3 The honest framing for judges

Do not claim 99% accuracy. Claim this instead:

> *"The published literature shows the best detectors fall to 20–30% EER on real-world audio. We accept that. So we designed a system that is useful even with a mediocre acoustic detector — because the acoustic score is only 45% of our decision, and we require corroboration from an independent evidence family before we ever interrupt a human. We report our numbers on In-the-Wild and on codec-degraded audio, not just on ASVspoof."*

A judge who knows the field will immediately mark you above every team claiming 98%.

---

## 4. The reframe

### 4.1 What everyone else builds

```
Audio clip ──► CNN/Transformer ──► P(synthetic) ──► "82% fake"
```

Failure modes: no timing (the verdict arrives after the call), no context (a synthetic voice reading a weather report is harmless; a human voice demanding a secret wire transfer is not), no action (the employee still has to decide), no privacy story (the audio was stored to analyse it).

### 4.2 What SentinelVoice builds

```
Live call ──► continuous multi-vector evidence ──► calibrated risk with
              corroboration rules ──► graduated action on the actual call
                                      ──► tamper-evident audit record
```

Four claims, each defensible:

| Claim | Mechanism |
|---|---|
| **Timely** — decision before the harm | 500 ms scoring cadence, actuation on the live channel (hold / whisper / terminate) |
| **Contextual** — the call, not the clip | 6 evidence families, only 2 of which are acoustic |
| **Actionable** — the system acts, not just alerts | 5-stage ladder wired to call control and transaction control |
| **Private** — analysis without surveillance | Zero raw-audio retention, non-invertible embeddings, hash-chained access log |

### 4.3 The non-obvious insight to lead with

> **Fraud has a grammar.** A real CFO calling his treasury desk and a cloned CFO calling the same desk differ in far more than their vocal tract. They differ in *who they call*, *what they ask for*, *how they respond to friction*, and *what happened on email 36 hours earlier*. The acoustic signal is the attacker's strongest suit — it is the thing they spend GPU hours perfecting. Everything else is the thing they cannot fake, because it requires being an actual employee with an actual history.
>
> **So we spend most of our confidence budget on the evidence the attacker cannot synthesise.**

---

## 5. Threat model

Define this explicitly. Judges ask "what if the attacker does X?" and a written threat model is the answer.

### 5.1 Attacker capabilities we assume (in scope)

| # | Capability | Our counter |
|---|---|---|
| A1 | Clone a target's voice from ≤3 s of public audio (podcast, LinkedIn video, voice note) | Acoustic artifact detection + speaker-embedding mismatch |
| A2 | Run real-time voice conversion (RVC) with ~200–500 ms latency, live-driven by a human | Prosody drift, challenge-response latency, conversational timing |
| A3 | Spoof caller ID / originate from a legitimate-looking SIP trunk or KYC'd SIM | CLI-vs-claim mismatch, trunk provenance, relationship graph |
| A4 | Pre-stage the attack over email and SMS (BEC + smishing) | Cross-channel correlation window |
| A5 | Social-engineer under urgency + authority + secrecy | Linguistic intent classifier; secrecy is itself a hard signal |
| A6 | Inject pre-rendered audio via virtual audio cable / SIP media injection | Room-impulse-response absence, double-compression forensics |
| A7 | Know that a detector exists and try to evade it (add noise, room tone, breaths) | Adversarial red-team module; corroboration requirement |

### 5.2 Explicitly out of scope (say this out loud; it is a strength)

| # | Out of scope | Why / mitigation |
|---|---|---|
| B1 | A **genuine human** insider committing fraud | We detect synthetic speech and anomalous *context* — context vectors still fire, acoustic vectors will not. We reduce, not eliminate, insider risk. |
| B2 | Compromise of the host bank's own infrastructure | Assumed trusted boundary. |
| B3 | Video deepfakes | Audio-only system. Architecture extends; not built. |
| B4 | Nation-state adversary with white-box access to our model weights performing gradient-based adversarial perturbation | Acknowledged hard problem; corroboration across non-differentiable evidence families (relationship graph, policy) is our partial defence. |
| B5 | Attacks under 3 seconds of speech | Below our minimum decision window; we output `INSUFFICIENT_EVIDENCE`, not a guess. |

Having B1–B5 written down and admitting them is worth more in judging than pretending they don't exist.

---

## 6. System architecture — four planes

The single most important architectural decision, and a **change from your original plan**:

> **Java never touches raw PCM.**

### 6.1 Why the change

Your current design has audio flowing Browser → Java → (unimplemented bridge) → Python. That is worse in four ways:
1. **Latency** — an extra hop and an extra base64 encode/decode per 500 ms window.
2. **Privacy claim integrity** — "zero raw-audio retention" is far easier to *prove* when exactly one process ever holds PCM, and it is a stateless process with a fixed-size ring buffer.
3. **Correctness** — Java is a bad place to resample, decode µ-law, or run an FFT. Python has librosa, torchaudio, scipy.
4. **Your current code already gets this wrong** — `CallSession.audioBuffer` is an unbounded `ArrayDeque` that is appended to and never drained, so today the system *does* retain all raw audio for the life of the session. Your headline privacy claim is currently false in code.

### 6.2 The four planes

```
┌───────────────────────────────────────────────────────────────────────────────┐
│ ① MEDIA PLANE — carries PCM. Ephemeral. Never persists. Never leaves the host. │
│                                                                                │
│  WebRTC browser tap ─┐                                                         │
│  Asterisk AudioSocket ├──► Normaliser (µ-law/A-law decode, resample,           │
│  Twilio/Exotel bridge ┘     mono, 16-bit) ──► bounded ring buffer (≤8 s)      │
│  SIPREC (architecture only)                          │                         │
└──────────────────────────────────────────────────────┼─────────────────────────┘
                                                       ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│ ② INFERENCE PLANE — Python 3.11 / FastAPI. Stateless per window.               │
│                                                                                │
│   FAST PATH (every 500 ms, budget 120 ms)   SLOW PATH (every 2.5 s, budget 900ms)│
│   • spectral / vocoder artifacts            • Whisper streaming ASR             │
│   • prosody: F0, jitter, shimmer, breath    • intent classifier (urgency /      │
│   • channel: RIR, double-compression          secrecy / authority / the ASK)    │
│   • speaker embedding (ECAPA 192-d)         • entity extraction (name, role,    │
│   • watermark scan (AudioSeal)                amount, beneficiary)              │
│                      │                                    │                    │
│                      └──────────► FeatureFrame JSON ◄──────┘                    │
│                                   (numbers only — no audio, no raw text)        │
└──────────────────────────────────────────┬────────────────────────────────────┘
                                           ▼  WebSocket (persistent)
┌───────────────────────────────────────────────────────────────────────────────┐
│ ③ DECISION PLANE — Java 21 / Spring Boot 3.3. Stateful. Auditable.             │
│                                                                                │
│  Session registry → Identity resolver (CLI ↔ directory ↔ spoken claim ↔        │
│  voiceprint) → Relationship graph → Transaction policy → Cross-channel          │
│  correlator → FUSION ENGINE (weights, EMA, corroboration, hysteresis)           │
│  → INTERVENTION LADDER (FSM) → ACTUATORS (call control, txn lock, OOB MFA)      │
│  → HASH-CHAINED AUDIT LEDGER (SHA-256, persisted)                               │
└──────────────────────────────────────────┬────────────────────────────────────┘
                                           ▼  STOMP /topic/telemetry/{sessionId}
┌───────────────────────────────────────────────────────────────────────────────┐
│ ④ PRESENTATION PLANE — React 18 + TS + Vite + Tailwind                         │
│  Analyst console · Senior Shield · Compliance portal · Red-team lab             │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 6.3 The rule that makes the privacy claim true

> **Raw PCM exists in exactly one process (the Inference Plane), inside one fixed-size ring buffer, for at most 8 seconds, and is overwritten in place. No write to disk, no write to DB, no log line containing audio, no network egress of audio. The Decision Plane receives only floats and enum labels.**

Write that sentence on the compliance slide. Then show the code that enforces it. That combination — a claim plus the enforcing code — is what separates a compliance *slide* from a compliance *architecture*.

---

## 7. Component specifications

### 7.1 Media Plane

#### `gateway/webrtc` (browser, TypeScript)
- `getUserMedia({audio: {channelCount:1, echoCancellation:false, noiseSuppression:false, autoGainControl:false}})`
- **Critical:** disable EC/NS/AGC. Browser noise suppression is a *neural audio enhancer* — it will strip the exact micro-artifacts you are trying to detect and it will fabricate others. Leaving these on invalidates your acoustic features.
- `AudioWorkletNode` → Float32 @ device rate → resample to 16 kHz mono → Int16 → 500 ms frames (8000 samples, 16000 bytes) → binary WebSocket frame to the Inference Plane.
- Send a JSON `hello` frame first: `{sessionId, sampleRate:16000, encoding:"pcm_s16le", channel:"WEBRTC_WIDEBAND"}`.

#### `gateway/asterisk-bridge` (Python or Java, your choice — recommend Python, colocated with ML)
- Asterisk's `AudioSocket()` dialplan application opens a **plain TCP** connection and streams audio with a trivial 3-byte header: `[1 byte type][2 bytes length BE][payload]`. Type `0x10` = audio payload, `0x01` = UUID, `0x00` = terminate.
- Default payload is **SLIN16 @ 8 kHz mono** (signed linear 16-bit). Upsample to 16 kHz for feature consistency, but **tag the frame `channel:"PSTN_NARROWBAND"`** so the fusion engine switches profiles (§10.4).
- Alternative: FreeSWITCH `mod_audio_fork` streams over WebSocket natively, slightly easier if you prefer FS.

#### `gateway/twilio-bridge` (Node or Python)
- TwiML `<Start><Stream url="wss://your-host/twilio"/></Start>` forks the media to your WebSocket.
- Payload: **base64 µ-law (G.711) @ 8 kHz, 20 ms frames.** You must µ-law-decode → linear PCM → accumulate to 500 ms → upsample.
- Exotel (Indian provider, better SIH narrative, works with Indian numbers without a regulatory bundle) offers an equivalent voice-streaming WebSocket. Architecture is identical; only the frame envelope differs. Build the adapter interface so either drops in.

#### `gateway/normaliser` (shared)
Single function, one place, well tested:
```
normalise(bytes, encoding, inRate, channels) -> (float32[] @16kHz mono, ChannelProfile)
```
Handles: µ-law, A-law, SLIN16, PCM16; 8k/16k/44.1k/48k; mono/stereo downmix. Emits `ChannelProfile ∈ {PSTN_NARROWBAND, VOIP_WIDEBAND, WEBRTC_WIDEBAND}`.

### 7.2 Inference Plane (Python)

| Module | Responsibility | Key library | Fast/Slow |
|---|---|---|---|
| `normaliser.py` | Codec decode, resample, profile tagging | `numpy`, `scipy.signal`, `audioop` | — |
| `ring_buffer.py` | Fixed 8 s float32 circular buffer, in-place overwrite, `zeroise()` | `numpy` | — |
| `spectral.py` | LFCC + linear/mel spectrogram, band-energy ratios, spectral flatness/rolloff/centroid, CQT | `librosa` | Fast |
| `phase.py` | STFT phase, group-delay, instantaneous-phase-deviation entropy | `numpy` | Fast |
| `prosody.py` | F0 (pYIN), jitter (local/rap/ppq5), shimmer (local/apq3), HNR, breath detection, pause statistics | `librosa`, `parselmouth` (Praat bindings — far more accurate than hand-rolled jitter) | Fast |
| `channel.py` | RIR/T60 estimate, spectral-notch detection, double-compression (MDCT residual histogram), DC offset, noise-floor stationarity | `numpy`, `scipy` | Fast |
| `speaker.py` | 192-d ECAPA-TDNN embedding + cosine vs enrolled passport + intra-call drift | `speechbrain` (`spkrec-ecapa-voxceleb`) | Fast |
| `antispoof.py` | The learned detector. Start with LFCC+GMM or a small AASIST-style model; upgrade to a wav2vec2/WavLM front-end if time allows | `torch`, `torchaudio`, HF | Fast |
| `watermark.py` | **AudioSeal** detector (Meta, open-source, real). SynthID is *not publicly detectable* — see §16.3 | `audioseal` | Fast |
| `asr.py` | Streaming transcription, Hindi/English/code-switch | `faster-whisper` (CTranslate2, ~4× real-time on CPU with `small`) | Slow |
| `intent.py` | Urgency / secrecy / authority / ask-extraction. Hybrid: lexicon + multilingual transformer (`xlm-roberta` or IndicBERT) | `transformers` | Slow |
| `adversarial.py` | Perturbation generator: additive noise at SNR, pitch shift, codec round-trip, RVC simulation, time-stretch | `librosa`, `ffmpeg` | Offline |
| `calibrate.py` | Platt / isotonic mapping of raw scores → probabilities; per-profile | `sklearn` | Offline |

### 7.3 Decision Plane (Java)

| Service | Responsibility | Current status |
|---|---|---|
| `CallSessionRegistry` | Session lifecycle, metadata, telemetry history (numbers only), TTL eviction | Exists as `CallSessionManager`; must remove audio buffering |
| `FeatureIngestService` | WebSocket client/server for FeatureFrames from Python; backpressure; staleness | **Missing entirely** |
| `IdentityResolutionService` | CLI → directory; spoken claim → entity; voiceprint → passport; emit `IdentityVerdict` | **Missing** |
| `RelationshipGraphService` | Interaction history, first-contact detection, hierarchy distance | Stub with hardcoded `if CFO` |
| `TransactionPolicyService` | Amount vs authority, channel-permitted, beneficiary novelty, velocity | **Missing** (`TransactionContext` record exists, unused) |
| `CrossChannelCorrelationService` | 48 h window of email/SMS/auth events for the same target | Stub returning constants; not even injected |
| `FusionEngineService` | Weighted sum → EMA → corroboration gate → clamp → calibration | Exists as `FusedRiskEngineService`, missing EMA/corroboration/calibration |
| `InterventionLadderService` | FSM with hysteresis + dwell + manual override | Exists, no hysteresis, no dwell, no state |
| `ActuationService` | Call control (hold/whisper/terminate), txn lock, OOB MFA dispatch | **Missing — this is the heart of the pitch** |
| `ChallengeResponseService` | Server-side challenge state, nonce, latency measurement, ASR match | Exists but **state lives on the client → spoofable → currently meaningless** |
| `VoicePassportService` | Consent-gated enrolment, embedding storage, erasure + tombstone | Thin CRUD wrapper |
| `AuditLedgerService` | Genesis block, server-side chaining, persistence, verification endpoint | Exists but in-memory, never called, caller supplies `previousHash` |
| `ForensicDossierService` | Assemble evidence pack, render PDF | Returns a hardcoded map |
| `TelemetryBroadcaster` | STOMP push to `/topic/telemetry/{sessionId}` | **Missing — WebSocketConfig exists but nothing publishes** |

### 7.4 Presentation Plane (React)

Build in this order (see §17 tiering):
1. `AnalystConsole` shell + `useTelemetrySocket` hook
2. `RiskGauge` + `CallerIdentityCard` + `LiveTranscript`
3. `EvidencePanel` (radar + waterfall — this is your explainability proof)
4. `InterventionBar` (the ladder, live, with the approve button that actually locks)
5. `SpectrogramCanvas`
6. `ChallengePanel`
7. `ForensicDossierViewer`
8. `SeniorShieldMode`
9. `CrossChannelTimeline`
10. `RedTeamLab`
11. `CompliancePortal`
12. `VoicePassportManager`

---

## 8. Data contracts

These are the interfaces. **Freeze them on Day 1.** Once frozen, three people can build three planes in parallel without blocking each other. Put them in `docs/contracts/` as JSON Schema and generate the TypeScript types from them.

### 8.1 `FeatureFrame` — Inference Plane → Decision Plane

Emitted every 500 ms per session. **Contains no audio and no verbatim transcript beyond a short redacted snippet.**

```jsonc
{
  "schema": "sentinelvoice.FeatureFrame/1",
  "sessionId": "call-9012",
  "seq": 47,
  "windowStartMs": 23500,
  "windowEndMs": 24000,
  "channelProfile": "PSTN_NARROWBAND",      // PSTN_NARROWBAND | VOIP_WIDEBAND | WEBRTC_WIDEBAND
  "speechPresent": true,                     // VAD; false frames do not update the score
  "cumulativeSpeechMs": 14200,               // gates INSUFFICIENT_EVIDENCE

  "voice": {
    "spoofProbability": 0.71,                // CALIBRATED probability, not a raw logit
    "modelId": "aasist-lfcc-v3-codecaug",
    "confidence": 0.62,                      // 1 - normalised predictive entropy
    "available": true
  },
  "channel": {
    "rirT60Ms": 18.0,                        // implausibly low => injected audio
    "rirPlausible": false,
    "doubleCompressionScore": 0.66,
    "noiseFloorStationarity": 0.91,          // synthetic silence is too stationary
    "dcOffset": 0.0004,
    "available": true
  },
  "prosody": {
    "f0MeanHz": 118.4, "f0StdHz": 4.1,
    "jitterLocalPct": 0.08,                  // human 0.5-1.5%; <0.2% => over-smooth
    "shimmerLocalPct": 1.9,
    "hnrDb": 27.8,                           // implausibly high HNR => synthetic
    "breathEventsPerMin": 0.0,               // human 8-20
    "disfluencyRate": 0.0,
    "articulationRateSylSec": 5.9,
    "unnaturalnessScore": 0.74,
    "available": true
  },
  "speaker": {
    "embeddingId": "emb-9012-47",            // pointer only; vector stays server-side
    "enrolledProfileId": "EMP-10492",
    "cosineSimilarity": 0.41,
    "intraCallDrift": 0.18,                  // RVC systems drift
    "available": true
  },
  "watermark": {
    "detector": "audioseal-v1",
    "detected": false,
    "provider": null,
    "confidence": 0.03
  },
  "linguistic": {                             // slow path; may be stale - check ageMs
    "ageMs": 900,
    "language": "hi-en",                      // code-switch detected
    "urgency": 0.93,
    "secrecy": 0.88,
    "authorityInvocation": 0.90,
    "emotionalCoercion": 0.71,
    "askDetected": true,
    "ask": {
      "type": "WIRE_TRANSFER",
      "amount": 5000000,
      "currency": "INR",
      "beneficiaryHint": "vendor account ending 4471",
      "deadline": "immediate"
    },
    "claimedIdentity": "Rajesh Kumar",
    "claimedRole": "CFO",
    "redactedSnippet": "…this is [NAME], [ROLE]. Transfer [AMOUNT] immediately, don't tell [PERSON]…",
    "available": true
  },
  "latencyMs": {"fastPath": 94, "slowPath": 812}
}
```

**Design notes that matter:**
- Every family carries `available`. On an 8 kHz PSTN call, `voice.available` may be `true` but with a narrowband model; some spectral sub-features are simply absent. **The fusion engine must renormalise weights over available families**, not treat missing as zero.
- `linguistic.ageMs` lets the fusion engine down-weight stale NLP.
- `speechPresent` prevents silence from dragging the EMA down and masking an escalation.

### 8.2 `TelemetryFrame` — Decision Plane → React (STOMP `/topic/telemetry/{sessionId}`)

```jsonc
{
  "schema": "sentinelvoice.TelemetryFrame/1",
  "sessionId": "call-9012",
  "seq": 47,
  "tsEpochMs": 1726584902150,
  "callElapsedMs": 24000,

  "risk": {
    "instantaneous": 0.88,
    "smoothed": 0.81,
    "trend": "RISING",
    "state": "SCORED"                        // SCORED | INSUFFICIENT_EVIDENCE | DEGRADED
  },
  "families": {
    "voice":        {"score":0.71,"weight":0.24,"contribution":0.170,"available":true},
    "channel":      {"score":0.66,"weight":0.08,"contribution":0.053,"available":true},
    "prosody":      {"score":0.74,"weight":0.13,"contribution":0.096,"available":true},
    "linguistic":   {"score":0.91,"weight":0.25,"contribution":0.228,"available":true},
    "transaction":  {"score":0.95,"weight":0.18,"contribution":0.171,"available":true},
    "relationship": {"score":0.87,"weight":0.12,"contribution":0.104,"available":true}
  },
  "corroboration": {
    "familiesAboveThreshold": ["linguistic","transaction","relationship","voice"],
    "independentFamiliesRequired": 2,
    "satisfied": true
  },
  "intervention": {
    "level": "LEVEL_4_AUTO_HOLD",
    "previousLevel": "LEVEL_3_STEP_UP_MFA",
    "changedAtMs": 23500,
    "dwellRemainingMs": 2500,
    "manualOverride": null,
    "actionsFired": ["TXN_APPROVE_LOCKED","OOB_MFA_SENT","CALL_HELD","SUPERVISOR_BRIDGED"]
  },
  "identity": {
    "cli": "+91-22-4000-1234",
    "cliTrunk": "EXTERNAL_SIP",
    "directoryMatch": null,
    "claimedIdentity": "Rajesh Kumar",
    "claimedRole": "CFO",
    "directoryRecordForClaim": {"employeeId":"EMP-10492","role":"Chief Financial Officer"},
    "cliVsClaimMismatch": true,
    "voicePassport": {"enrolled":true,"cosine":0.41,"verdict":"FAILED"},
    "presenceConflict": {"expected":"London (calendar)","observed":"SIP trunk / APAC"}
  },
  "topReasons": [
    {"code":"CLI_CLAIM_MISMATCH","severity":"CRITICAL","text":"Caller claims CFO; call arrives on unregistered external SIP trunk"},
    {"code":"VOICEPRINT_FAIL","severity":"CRITICAL","text":"Cosine 0.41 vs enrolled 0.91 baseline"},
    {"code":"POLICY_VIOLATION","severity":"HIGH","text":"₹50,00,000 verbal wire request; verbal authority limit is ₹0"},
    {"code":"NO_BREATH","severity":"MEDIUM","text":"0 breath events in 24 s of speech (expected 8–20/min)"},
    {"code":"SECRECY_DEMAND","severity":"HIGH","text":"Isolation language detected"}
  ],
  "transcriptDelta": {"tsMs":23400,"text":"…turant transfer kar do, kisi ko mat batana…","flags":["URGENCY","SECRECY"]},
  "auditHash": "9f2c…a71b"
}
```

### 8.3 Other contracts to freeze

- `SessionStartRequest` / `SessionDescriptor`
- `ChallengeIssued` / `ChallengeResult`
- `InterventionOverride` (analyst up/down-grade, with mandatory reason — this is an audited event)
- `AuditBlock` (see §9.5)
- `ForensicDossier`
- `CrossChannelEvent`

---

## 9. The mathematics (corrected and complete)

Your current `FusedRiskEngineService` implements only the first line of this section. Everything below is missing and each piece prevents a specific failure.

### 9.1 Instantaneous fusion with availability renormalisation

Let $\mathcal{A}(t)$ be the set of evidence families available at window $t$.

$$S(t) \;=\; \frac{\sum_{i \in \mathcal{A}(t)} w_i \, c_i(t) \, S_i(t)}{\sum_{i \in \mathcal{A}(t)} w_i \, c_i(t)}$$

- $S_i(t) \in [0,1]$ — calibrated family score
- $w_i$ — base weight (below)
- $c_i(t) \in [0,1]$ — **confidence multiplier**: model confidence for `voice`, staleness decay $e^{-\text{ageMs}/\tau}$ for `linguistic`, availability for the rest

**Base weights.** Two profiles, because a narrowband call cannot support the same acoustic confidence:

| Family | Wideband $w_i$ | Narrowband $w_i$ | Rationale for the shift |
|---|---|---|---|
| voice (spoof model) | 0.24 | 0.15 | Codec destroys high-frequency artifacts |
| channel forensics | 0.08 | 0.10 | Double-compression signal is *stronger* on PSTN |
| prosody | 0.13 | 0.12 | F0/jitter survive narrowband reasonably well |
| linguistic | 0.25 | 0.29 | Language is codec-invariant → lean on it harder |
| transaction | 0.18 | 0.20 | Entirely non-acoustic |
| relationship | 0.12 | 0.14 | Entirely non-acoustic |
| **Σ** | **1.00** | **1.00** | |

Put this table on a slide. "We re-weight our evidence when the channel degrades" is a mature-engineering signal.

### 9.2 Temporal smoothing with asymmetric response

A symmetric EMA is wrong here: you want to **escalate fast and de-escalate slow**, because the cost of a missed fraud vastly exceeds the cost of an extra 3 seconds of amber banner.

$$\bar S(t) = \lambda(t)\,\bar S(t-\Delta t) + \bigl(1-\lambda(t)\bigr) S(t)$$

$$\lambda(t) = \begin{cases}
\lambda_{\text{up}} = 0.55 & \text{if } S(t) > \bar S(t-\Delta t) \quad \text{(rising: react quickly)}\\
\lambda_{\text{down}} = 0.88 & \text{if } S(t) \le \bar S(t-\Delta t) \quad \text{(falling: decay slowly)}\\
0 & \text{if emergency trigger (§9.4)}
\end{cases}$$

Freeze the EMA when `speechPresent == false` — silence must not decay an accumulated risk.

### 9.3 The corroboration gate — your most defensible safety mechanism

**Rule:** no escalation above `LEVEL_2_SOFT_NUDGE` unless at least **two independent evidence families** each exceed their own threshold, and they are **not both acoustic**.

Define families as belonging to two groups:
- **Acoustic group** $G_A$ = {voice, channel, prosody} — these share a failure mode (a clean recording environment defeats all three; an over-processed line triggers all three)
- **Contextual group** $G_C$ = {linguistic, transaction, relationship} — independent of the signal

$$\text{Escalate above L2} \iff \Bigl(|\{i \in G_A : S_i > \theta_i\}| \ge 1\Bigr) \wedge \Bigl(|\{i \in G_C : S_i > \theta_i\}| \ge 1\Bigr)$$

**Why this is the right design and why it will impress:** §3 established that your acoustic detector may be near-random in the wild. A system that fires on acoustic evidence alone inherits that error rate directly. Requiring cross-group corroboration means a false acoustic alarm on a genuine customer stays at an advisory banner, because a genuine customer will not simultaneously exhibit secrecy language, a policy-violating ask, and a zero-history relationship. **You have converted an unreliable classifier into a reliable system.**

Sensible $\theta_i$ to start: voice 0.60, channel 0.55, prosody 0.60, linguistic 0.65, transaction 0.60, relationship 0.60. Tune on a held-out set; do not hand-pick to make the demo work.

### 9.4 Emergency bypass

The corroboration rule must not let a blatant attack crawl up the ladder. Fire immediately to L4 if **any** of:
- `identity.cliVsClaimMismatch == true` **AND** `transaction.ask.amount > verbalAuthorityLimit`
- `speaker.cosineSimilarity < 0.50` on an enrolled passport **AND** `linguistic.askDetected == true`
- `linguistic.secrecy > 0.85` **AND** `linguistic.authorityInvocation > 0.85` **AND** `transaction.score > 0.80`

On bypass, set $\lambda = 0$ (instant score adoption) and log the reason code.

### 9.5 Hysteresis and dwell — prevents the demo-killing flicker

Without these, a score oscillating around 0.55 makes the UI strobe between MFA and nudge, which looks broken.

| Transition | Threshold | Dwell |
|---|---|---|
| L1 → L2 | $\bar S \ge 0.35$ | 1.0 s |
| L2 → L1 | $\bar S \le 0.28$ | 5.0 s |
| L2 → L3 | $\bar S \ge 0.55$ + corroboration | 1.0 s |
| L3 → L2 | $\bar S \le 0.46$ | 8.0 s |
| L3 → L4 | $\bar S \ge 0.75$ + corroboration | 1.0 s |
| L4 → L3 | $\bar S \le 0.66$ | 15.0 s |
| L4 → L5 | $\bar S \ge 0.90$ + corroboration + analyst confirm **or** emergency bypass | — |
| L5 → any | **never automatically** — manual reset only | — |

Down-transitions require the score to fall below the up-threshold by a margin (hysteresis band) **and** the level to have been held for the dwell time.

### 9.6 Calibration — turn scores into probabilities

Raw model outputs are not probabilities. Fit **Platt scaling** (logistic regression on the logit) or **isotonic regression** on a held-out set, separately per `channelProfile`:

$$P(\text{spoof}\mid s) = \frac{1}{1 + \exp(As + B)}$$

Report a **reliability diagram** and **Expected Calibration Error** in your evaluation section. Almost no hackathon team calibrates. It is a 20-line scikit-learn addition and a genuinely strong slide: *"when we say 70%, it is wrong 30% of the time — here is the plot."*

### 9.7 Hash-chained audit ledger

$$H_0 = \text{SHA256}\bigl(\text{"SENTINELVOICE-GENESIS-v1"} \Vert \text{sessionId} \Vert t_0\bigr)$$
$$H_k = \text{SHA256}\bigl(H_{k-1} \Vert t_k \Vert \text{sessionId} \Vert \text{eventType} \Vert \text{canonicalJson(payload)} \Vert \bar S(t_k) \Vert \text{level}\bigr)$$

**Four things your current implementation gets wrong:**
1. There is no genesis block.
2. `previousHash` is a **method parameter supplied by the caller** — an attacker (or a bug) can forge a chain. The service must own the tail hash per session.
3. The ledger is an in-memory `ArrayList`; `AuditBlockRepository` exists but is never injected. Restart = evidence gone.
4. Nothing ever calls `append()`. The compliance endpoint returns an empty list. Verify this yourself: `curl localhost:8082/api/v1/compliance/audit-chain` returns `{"status":"ok","ledger":[]}`.

**Canonical JSON matters**: serialise with sorted keys and fixed number formatting, or the same event hashes differently on two machines and your verification endpoint reports tampering that didn't happen.

Add `GET /api/v1/compliance/verify/{sessionId}` that recomputes the whole chain and returns `{valid, brokenAtIndex}`. Then, in the demo, **tamper with a row via the H2 console live on stage and show the verifier catching it.** That is a 30-second demo beat worth more than five slides.

---

## 10. Feature catalogue

For each feature: what it measures, why synthetic speech differs, its **failure mode** (know this — judges probe it), and whether it survives 8 kHz narrowband.

### 10.1 Spectral / vocoder artifacts

| Feature | Signal | Failure mode | 8 kHz? |
|---|---|---|---|
| High-band energy ratio (>4 kHz of available band) | Vocoders under-model upper harmonics | Any lowpass/codec mimics it → false positive | Partial (reframe as >2.8 kHz) |
| Spectral flatness / rolloff / centroid | Synthesis smooths the envelope | Speaker- and accent-dependent | Yes |
| LFCC (linear-freq cepstral coeffs) | Standard anti-spoofing front-end; outperforms MFCC here because spoofing artifacts live in high linear bands | Needs a trained back-end | Yes |
| CQT / constant-Q | Resolves vocoder frame-grid periodicity | Expensive; ~30 ms/window | Yes |
| Vocoder frame-rate periodicity | HiFi-GAN etc. leave a periodic residue at the hop rate | Codec re-encoding smears it | Weak |

### 10.2 Phase features

| Feature | Signal | Failure mode | 8 kHz? |
|---|---|---|---|
| Instantaneous-phase-deviation entropy | Human phonation is micro-turbulent (chaotic phase); many vocoders reconstruct minimum-phase → **entropy too low** | Modern diffusion/flow vocoders model phase well; this is weakening over time | Yes |
| Group-delay function | Captures resonance structure the magnitude spectrum hides | Very noise-sensitive | Yes |

**Be honest about this one.** Phase-based detection worked beautifully against 2019-era vocoders and works far less well against 2025-era ones. Saying so is a credibility win.

### 10.3 Prosody & micro-behaviour — *your best narrowband features*

| Feature | Human range | Synthetic tell | 8 kHz? |
|---|---|---|---|
| Jitter (local) | 0.5–1.5% | <0.2% (over-smooth) or >3.5% (artefactual) | **Yes** |
| Shimmer (local) | 3–8% | Often too low | **Yes** |
| HNR | 15–25 dB | Often >28 dB (too clean) | **Yes** |
| **Breath events** | 8–20 /min, pre-phonatory inhalation with characteristic broadband transient | Frequently **zero**; or breaths inserted at grammatically-wrong places | **Yes** |
| Disfluency rate | 2–8 per 100 words in spontaneous speech | Near zero in scripted TTS | **Yes** (via ASR) |
| F0 micro-contour entropy | Rich | Over-regular | **Yes** |
| Pause-duration distribution | Log-normal, heavy tail | Too uniform | **Yes** |

> **Breath absence is your single most explainable feature.** "This caller spoke for 40 seconds and never once inhaled" lands with a non-technical judge, a bank manager, and a grandmother. Make sure it renders prominently in the UI and gets a callout in the demo script. Use `parselmouth` (Praat bindings) for jitter/shimmer/HNR — Praat's algorithms are the reference implementations and hand-rolling them is a classic time sink that produces wrong numbers.

### 10.4 Channel forensics — the dual-profile problem

**This is the most important correction to your existing plan.**

PSTN narrowband speech is sampled at 8 kHz → Nyquist limit 4 kHz → **there is literally no information above 4 kHz.** Your plan's centrepiece — ">8 kHz rolloff analysis" — is undefined on a real phone call. If a judge with a DSP background asks *"how does your 8 kHz feature work on a G.711 call?"* and you have no answer, you lose the room.

**The answer that wins the room instead:**

> *"It doesn't — and that's why we have two operating profiles. On wideband WebRTC or Opus VoIP we run the full acoustic stack. The moment a call arrives narrowband, we detect it from the media negotiation, drop the high-band features, and automatically re-weight the fusion toward prosody, phase, and the contextual families, which are codec-invariant. We report separate EER for each profile rather than one flattering number. And we turn the codec into evidence: synthetic audio played into a phone call has been encoded twice, and double-compression leaves a detectable statistical signature that a live human voice does not have."*

Channel features:

| Feature | What it catches | 8 kHz? |
|---|---|---|
| RIR / T60 reverberation estimate | Injected audio (virtual cable, SIP injection) has near-zero or synthetic reverb; a live mic in a room always has some | Yes |
| Double-compression detection | Synthesised → WAV/MP3 → re-encoded to G.711/AMR leaves dual-quantisation peaks | **Yes — stronger on PSTN** |
| Noise-floor stationarity | Real rooms have non-stationary noise; synthetic "silence" is suspiciously constant | Yes |
| Codec/bandwidth fingerprint | Detects the profile itself; also detects a *change* mid-call (re-INVITE = possible injection) | Yes |
| DC offset / clipping statistics | Generation pipeline signatures | Yes |

### 10.5 Speaker verification

- **ECAPA-TDNN 192-d** via SpeechBrain `spkrec-ecapa-voxceleb`. Real, pretrained, CPU-runnable.
- Cosine vs enrolled passport. Calibrate the threshold on *your* data; do not hardcode 0.65 from a blog post. Typical: >0.70 same-speaker, <0.45 different-speaker, but this shifts hard with channel mismatch — **enrol over the same channel type you'll verify on**, or your legitimate CFO fails his own passport on a phone call.
- **Intra-call drift**: RVC systems adapt as the driver changes pitch/environment. Compute cosine between the first 5 s embedding and each subsequent one; genuine speakers drift <0.10, converted voices often >0.15.

### 10.6 Linguistic intent

Four sub-scores plus an extractor:

| Sub-score | Examples | Note |
|---|---|---|
| **Urgency** | "immediately", "within 15 minutes", "before the market closes", "turant" | Lexicon + transformer; weight recency |
| **Secrecy** | "don't tell your manager", "strictly between us", "kisi ko mat batana", "verbal authorisation only" | **The highest-signal feature in the whole system.** Legitimate business communication almost never asks you to bypass a colleague. |
| **Authority** | "This is the Managing Director", "CBI", "Police Commissioner", "RBI" | Cross-check against directory & CLI |
| **Emotional coercion** | Distress, threat of arrest, "you'll be held responsible" | Critical for the grandparent-scam scenario |
| **The ASK (extraction)** | type, amount, beneficiary, deadline, channel | Feeds `TransactionPolicyService` |

For Hinglish/code-switch: `faster-whisper` handles code-switched Hindi–English reasonably in `small`/`medium`. For the intent classifier, a **lexicon + multilingual embedding hybrid** beats a pure fine-tune when you have no labelled fraud corpus — and you don't. Build the lexicon in English, Hindi (Devanagari **and** romanised — "turant", "jaldi", "mat batana"), Tamil and Telugu.

### 10.7 Watermarks — be precise here

- **AudioSeal (Meta)** — open-source, has a published *detector*, localised to the sample level. **Implement this for real.** `pip install audioseal`.
- **SynthID (Google)** — watermarks Google-generated audio, but detection is available only through Google's own tooling/portal for their content. **You cannot build a SynthID detector.** Listing it as implemented is a factual error a judge may catch.
- **Correct framing:** *"We detect AudioSeal watermarks today. SynthID and other provider schemes require provider-side detection APIs; our scanner is built as a pluggable interface so a provider detector drops in when access is granted. And we're explicit that watermark absence proves nothing — no attacker uses a watermarking TTS on purpose. It's a fast-path attribution signal, never a decision input."*

That last sentence is the important one. **Watermark detection must be an attribution enrichment, never a risk-score input** — otherwise you've built a system that trusts unwatermarked audio.

---

## 11. Call integration — the deep dive

You asked to focus here. This is also what most SIH teams fake with a "play WAV file" button, so doing it properly is a large differentiator.

### 11.1 Four integration paths, ranked by build order

| # | Path | Effort | Needs internet | Demo value | Build? |
|---|---|---|---|---|---|
| 1 | **WebRTC browser tap** | Low | No | Medium — but it's the spine everything else tests against | **Yes, first** |
| 2 | **Asterisk + AudioSocket (Docker, local)** | Medium | No | **Very high** — real SIP, two softphones, works offline at the venue | **Yes, second** |
| 3 | **Twilio Media Streams / Exotel streaming** | Medium | **Yes** | Highest wow — a real phone rings | **Yes, if venue Wi-Fi is trustworthy** |
| 4 | **SIPREC to an enterprise SBC** | High | — | Architecture credibility | **Slide only** |

**Recommendation: build 1 and 2, add 3 as a bonus, present 4 as the enterprise deployment path.** Path 2 is the sweet spot: it is genuinely real telephony, and it runs entirely on your laptop, so venue Wi-Fi cannot kill your demo.

### 11.2 Path 1 — WebRTC browser tap

```
Browser getUserMedia (EC/NS/AGC OFF)
  → AudioWorklet (128-sample quantum)
  → accumulate + resample to 16 kHz mono Int16
  → 500 ms frames
  → binary WebSocket → ws://localhost:8000/ingest/{sessionId}
```
Use this for development and for the "agent workstation" story. It is also how Senior Shield Mode works on a phone (PWA, browser mic during a speakerphone call).

### 11.3 Path 2 — Asterisk + AudioSocket (the one to build)

**Why AudioSocket:** it is the simplest audio-tap protocol in existence. Plain TCP, 3-byte header, raw SLIN16. No RTP stack, no SDP parsing, no jitter buffer to implement. You get real SIP telephony for about 80 lines of Python.

**Setup:**

```
docker run -d --name asterisk --network host andrius/asterisk:latest
```
(or build from the official image; you'll mount your own config)

`pjsip.conf` — two endpoints for two softphones:
```ini
[transport-udp]
type=transport
protocol=udp
bind=0.0.0.0

[agent]
type=endpoint
context=sentinel
disallow=all
allow=ulaw
auth=agent-auth
aors=agent

[caller]
type=endpoint
context=sentinel
disallow=all
allow=ulaw
auth=caller-auth
aors=caller
```

`extensions.conf` — fork media to SentinelVoice, then bridge the call:
```ini
[sentinel]
exten => 1001,1,Answer()
 same => n,Set(SV_SESSION=${UNIQUEID})
 same => n,MixMonitor(...)            ; optional, off by default for privacy
 same => n,Dial(PJSIP/agent,60,U(tap))

[tap]
exten => s,1,AudioSocket(${SV_SESSION},127.0.0.1:9092)
 same => n,Return()
```

**Your bridge (`gateway/asterisk_bridge.py`):**
```python
# AudioSocket frame: [type:1][len:2 BE][payload:len]
# 0x00 terminate | 0x01 UUID (16 bytes) | 0x10 audio (SLIN16 8kHz) | 0xff error
async def handle(reader, writer):
    session_id = None
    while True:
        hdr = await reader.readexactly(3)
        ftype, length = hdr[0], int.from_bytes(hdr[1:3], "big")
        payload = await reader.readexactly(length) if length else b""
        if ftype == 0x01:
            session_id = uuid.UUID(bytes=payload).hex
        elif ftype == 0x10:
            ring.write(normalise(payload, "slin16", 8000, 1))   # → 16k float32
        elif ftype == 0x00:
            break
```

**Softphones:** Zoiper or Linphone (both free, both on desktop and mobile). Register two accounts to your Asterisk. One is "the attacker" playing cloned audio through a virtual audio cable; one is "the bank agent." **This is a genuinely convincing demo** — you are placing a real SIP call.

**Bonus realism:** have the attacker softphone use a virtual audio cable fed by your TTS output. That reproduces the *exact* attack (synthesised audio injected into a call) and it will show up in your channel forensics as absent RIR — your own feature catching your own attack, live. Judges love a closed loop.

### 11.4 Path 3 — PSTN via Twilio or Exotel

**Twilio Media Streams:**
```xml
<Response>
  <Start><Stream url="wss://your-ngrok-host/twilio/{sessionId}"/></Start>
  <Dial>+919876543210</Dial>
</Response>
```
Frames arrive as JSON with base64 µ-law 8 kHz, 20 ms each:
```json
{"event":"media","streamSid":"MZ…","media":{"payload":"…base64 µ-law…"}}
```
Decode with `audioop.ulaw2lin(payload, 2)` → accumulate 25 frames → 500 ms.

**Exotel** is the better SIH story (Indian provider, Indian numbers, no US regulatory bundle needed) and offers an equivalent WebSocket voice-streaming product. Build `gateway/pstn_bridge.py` with a provider adapter interface so Twilio and Exotel are two small classes behind one contract. Then in the pitch you say *"provider-agnostic; we ship adapters for Twilio and Exotel."*

**Practical warnings:**
- You need a public HTTPS/WSS URL → `ngrok http 8000` (have a paid/reserved domain so the URL doesn't change mid-demo).
- Twilio Indian phone numbers require a regulatory bundle with address proof and can take days. **Start this on Day 1 if you want it, or use Exotel, or dial a US/UK trial number.**
- Trial accounts inject a "this call is from a trial account" preamble. Budget for it in the demo script.
- **Have a recorded fallback video of the PSTN demo.** Venue Wi-Fi fails. Every year.

### 11.5 Path 4 — SIPREC (slide only)

SIPREC (RFC 7865/7866) is how real banks tap calls: the SBC (AudioCodes, Oracle/Acme, Ribbon) forks media to a Session Recording Server via a standard SIP interface with full metadata. Your architecture slide shows SentinelVoice implementing the SRS role, deployed beside the recording infrastructure the bank already has. You are not building this. Say *"SIPREC-compatible by design; AudioSocket and Media Streams are our reference adapters"* — that's an honest and impressive claim.

### 11.6 Actuation — how the system *intervenes* on a live call

**This is the difference between your project and a detector, so build at least two of these for real.**

| Action | WebRTC path | Asterisk path | Twilio path |
|---|---|---|---|
| **Whisper warning to agent only** | Play audio in agent's browser tab | `ChanSpy`/`Snoop` + `Playback` on the agent channel via **ARI** | Not natively; use a `<Say>` on a conference leg |
| **Hold the call** | Mute + play hold tone locally | ARI `POST /channels/{id}/hold` | `calls(sid).update({twiml:"<Play loop=0>hold.mp3</Play>"})` |
| **Bridge supervisor** | Add peer to room | ARI: create bridge, add supervisor channel | Add a participant to a conference |
| **Terminate** | Close peer connection | ARI `DELETE /channels/{id}` | `calls(sid).update({status:"completed"})` |
| **Inject announcement** | Local playback | ARI `Playback` | `calls(sid).update({twiml:"<Say>…</Say>"})` |
| **Lock the approve button** | WebSocket message → React disables it | same | same |
| **Out-of-band MFA** | Push/SMS to the *real* executive's registered device | same | same |
| **Freeze beneficiary account** | Webhook to mock CBS | same | same |

Asterisk **ARI** (`http://localhost:8088/ari/`, REST + WebSocket events) gives you all of this. Wire `ActuationService` in Java to ARI over REST. Then your Level 4 is not a coloured banner — **the call actually goes on hold, on stage, and everyone hears it.**

> **Demo beat to engineer deliberately:** the risk gauge crosses 0.75, the agent's "Approve ₹50,00,000" button greys out mid-click, hold music starts, and a supervisor card appears. Three seconds, no narration needed. Rehearse it until it is reliable. That moment is your score.

### 11.7 Latency budget

| Stage | Budget |
|---|---|
| Media capture → 500 ms frame assembled | 500 ms (inherent) |
| Normalise + ring write | 5 ms |
| Fast-path features | 120 ms |
| FeatureFrame → Java (local WS) | 5 ms |
| Fusion + FSM + audit append | 15 ms |
| STOMP → React render | 20 ms |
| **Detection-to-display** | **~165 ms after window close** |
| Actuation (ARI hold round-trip) | +150–400 ms |
| **End-to-end interdiction** | **≈0.7–1.0 s from the fraudulent utterance** |

Present it as *"sub-200 ms decision latency, sub-second interdiction"* and show the measured histogram. Do not claim "sub-150 ms end-to-end" — that ignores the inherent 500 ms window and a judge will notice.

---

## 12. Identity & role verification pipeline

Five stages, each independently scored, feeding `identity` in the TelemetryFrame.

```
[1] TELEPHONY METADATA
    CLI / SIP P-Asserted-Identity / trunk class / CNAP name (if available)
    → trunkProvenance ∈ {INTERNAL_PBX, REGISTERED_EXTERNAL, UNREGISTERED_SIP, WITHHELD}
         │
[2] DIRECTORY RESOLUTION  (LDAP / HRMS / CBS — mock with a seeded H2 table)
    CLI → employeeId, name, role, department, verbalAuthorityLimit,
          permittedChannels, presenceStatus, calendarLocation
         │
[3] SPOKEN CLAIM EXTRACTION  (ASR → NER)
    "this is Rajesh Kumar, your CFO" → {claimedName, claimedRole}
    ⚠ THE CORE CHECK:  claimedRole ≠ directory[CLI].role   →  CLI_CLAIM_MISMATCH (CRITICAL)
    ⚠ claimedRole present AND trunkProvenance = UNREGISTERED_SIP → CRITICAL
         │
[4] VOICE PASSPORT  (only if the claimed identity is enrolled AND consented)
    ECAPA-192d(live) · ECAPA-192d(enrolled) → cosine
       > 0.70  MATCH        |  0.50–0.70  INCONCLUSIVE  |  < 0.50  MISMATCH
    + intra-call drift; + antispoof.spoofProbability
    → verdict ∈ {VERIFIED, INCONCLUSIVE, IMPERSONATION_HUMAN, IMPERSONATION_SYNTHETIC}
         │
[5] CONTEXT & PRESENCE
    • interactionCount(caller → recipient, 365d)      → 0 = first contact
    • hierarchyDistance (CFO ↔ junior teller = 4 levels)
    • presence conflict (calendar says London, trunk says APAC)
    • policy: verbalAuthorityLimit = ₹0 for wire transfers
```

**The distinction that matters and that teams get wrong:** "impersonation by a human" and "impersonation by a clone" are *different verdicts with different responses*. Cosine 0.41 + spoofProbability 0.15 = a different person speaking normally (possibly a legitimate delegate!). Cosine 0.41 + spoofProbability 0.82 = a synthetic clone. Only the second is a deepfake attack. Surfacing both dimensions separately in the UI shows nuance.

**Passport enrolment caveat that will be asked about:** channel mismatch wrecks speaker verification. If the CFO enrols via a studio mic and is verified over G.711, cosine drops sharply on a genuine call. **Enrol per channel profile** (store a wideband and a narrowband embedding), or apply channel compensation. Say this before the judge asks.

---

## 13. Privacy & DPDP Act 2023 architecture

### 13.1 The honest statement of the tension

Lead with this, because pretending it doesn't exist is what weak submissions do:

> *"To know whether a voice is fake, something has to listen. So privacy here cannot mean 'nothing processes the audio.' It means controlling what is extracted, who can see it, how long it survives, and what could leak even from the processed form."*

### 13.2 The seven controls

1. **Fingerprint, don't record.** Raw PCM lives only in the Inference Plane ring buffer (≤8 s, fixed size, overwritten in place, explicitly zeroed on session end). What persists is float vectors and enum labels. An embedding is not losslessly invertible to intelligible speech.
2. **Encrypt in transit and at rest.** TLS/WSS on every hop; SRTP on the media leg; encrypted H2/Postgres at rest with keys held outside the data store.
3. **Minimise and expire.** Analyse in 500 ms windows, discard each after scoring. Hard TTL on everything retained: telemetry 90 days, audit blocks 7 years (RBI), embeddings until consent withdrawal.
4. **Separate the planes by privilege.** The scoring process and the analyst console are different systems with different credentials. An analyst sees *score + reason codes*, not transcript, by default.
5. **Break-glass for transcript access.** Viewing the transcript requires a second approval, writes an audit block naming the approver and the justification, and shows only the flagged ±5 s window — never the whole call.
6. **Redaction by default.** Account numbers, Aadhaar-like patterns, card numbers, and personal identifiers masked in any surfaced text.
7. **Specific consent, real erasure.** Voice Passport enrolment is opt-in with recorded, purpose-limited consent. `DELETE /api/v1/passport/{id}` destroys the embedding and appends a **cryptographic deletion tombstone** to the ledger — proving the deletion happened without retaining what was deleted.

### 13.3 DPDP Act 2023 mapping

| Provision | Requirement | Implementation |
|---|---|---|
| §4, §6 | Lawful basis; free, specific, informed, unambiguous consent with clear affirmative action | Consent record per enrolled principal: purpose, timestamp, version of notice, withdrawal mechanism; consent state gates every read of the embedding |
| §5 | Notice at/before consent | Enrolment UI shows plain-language notice in English + regional language |
| §8(4), §8(7) | Reasonable security safeguards; erase on withdrawal or purpose expiry | Encryption, RBAC, TTL jobs, zero-audio retention |
| §8(5) | Notify on breach | Documented incident procedure + alerting on anomalous access volume |
| §11 | Right to access information about processing | `GET /api/v1/passport/{id}` returns metadata + processing log (not the raw vector) |
| §12 | Right to correction and erasure | Re-enrol endpoint; erase endpoint with tombstone |
| §13 | Right of grievance redressal | Grievance contact + SLA in the compliance portal |

Also map **RBI Master Direction on IT Governance / Cyber Security Framework**: immutable audit logging of automated decisions and human overrides, MFA on financial instructions, and documented model governance.

### 13.4 Advanced controls — mention as roadmap, don't build

These earn credit for maturity even unimplemented, *if* you present them as roadmap:
- **Differential privacy** on aggregate reporting (so "% flagged this month" can't be reverse-engineered to an individual)
- **Federated learning** — train at each bank, share gradients not audio
- **Secure enclaves / homomorphic inference** — score without decrypting
- **Insider-misuse defence** — RBAC, mandatory access logs, volumetric anomaly alerts on analysts themselves

### 13.5 Bias & fairness — the control most teams forget

You are deploying a classifier on Indian voices. **Report per-group performance:**
- By language family: Indo-Aryan (Hindi, Bengali, Marathi, Gujarati, Punjabi) vs Dravidian (Tamil, Telugu, Kannada, Malayalam)
- By gender, by approximate age band, by channel profile
- Metric: **false positive rate parity** — because a false positive means a *legitimate customer gets interrogated*, and if that lands disproportionately on Tamil speakers, you have built a discriminatory system.

Use Mozilla Common Voice (Hindi, Tamil, Telugu, Marathi, Bengali splits) for the bonafide side. A bar chart of FPR by language group, with the gaps visible and honestly discussed, is a genuinely strong compliance slide.

---

## 14. Simulation scenarios (demo content)

Each scenario is a **seeded fixture**: directory state + relationship history + cross-channel events + an audio file (or live SIP call). Store as YAML in `scenarios/`.

| # | Scenario | Channel | Expected trajectory | Teaching point |
|---|---|---|---|---|
| 1 | **Legit CFO** — enrolled, internal PBX, scheduled tax payment | WEBRTC_WIDEBAND | 0.08 → 0.12, stays L1 | Low false-positive rate; the system is quiet |
| 2 | **Deepfake CEO wire fraud** — cloned voice, external SIP, ₹50L, urgency + secrecy | PSTN_NARROWBAND via Asterisk | 0.22 → 0.51 → 0.78 → L4 auto-hold | The full ladder; approve button locks live |
| 3 | **Liveness challenge** — RVC attacker asked to repeat "Amber Falcon 72" | any | latency 3.8 s vs 1.8 s threshold → L5 | Real-time interactivity defeats pre-rendering |
| 4 | **Hinglish grandparent scam** — cloned grandson, "bail ke liye turant ₹25,000 UPI kar do" | PSTN | 0.35 → 0.81, Senior Shield red alert + family SOS | Social impact; code-switch handling |
| 5 | **False-positive stress test** — genuine elderly customer, heavy accent, noisy line, emotional | PSTN | acoustic 0.68 but contextual 0.11 → **stays L2, never escalates** | **Run this one. Deliberately.** It proves the corroboration gate. Showing your system *refusing* to escalate on a legitimate customer is more persuasive than any catch. |
| 6 | **Adversarial evasion** — attacker adds room tone, breath samples, noise at 20 dB SNR | any | acoustic degrades to 0.44, context holds → still L4 | Graceful degradation under evasion |

Scenario 5 is the one nobody else will demo and the one a banking judge cares most about.

---

## 15. Evaluation methodology

### 15.1 Datasets

| Dataset | Use | Access |
|---|---|---|
| **ASVspoof 2019 LA** | Train + in-domain eval | Edinburgh DataShare, free |
| **ASVspoof 2021 DF** | Codec/compression robustness | Free |
| **ASVspoof 5** | Modern attacks, crowdsourced conditions | Free |
| **In-the-Wild (Fraunhofer AISEC)** | **The honest test.** Real-world deepfakes | Free |
| **WaveFake** | Vocoder diversity | Free |
| **Mozilla Common Voice** (hi, ta, te, mr, bn) | Bonafide Indian speech; fairness analysis | Free, CC-0 |
| **Your own codec-degraded set** | Telephony realism | Generate with ffmpeg |

### 15.2 Metrics

- **EER** and **min t-DCF** per dataset per channel profile
- **FPR at fixed TPR** (e.g. FPR @ TPR=0.90) — more operationally meaningful than EER for a bank
- **Expected Calibration Error** + reliability diagram
- **Per-group FPR** (§13.5)
- **Latency**: p50/p95/p99 of fast path, slow path, and end-to-end interdiction
- **System-level**: scenario pass rate, mean time-to-intervention, false-escalation rate on the benign corpus

### 15.3 The codec-degradation study — your genuine contribution

This is a real experiment you can run in a few hours and it produces a number nobody else will have.

```bash
# Build the degraded eval set
for f in eval/*.wav; do
  ffmpeg -i "$f" -ar 8000 -ac 1 -acodec pcm_mulaw   g711/"$(basename $f)"      # G.711 µ-law
  ffmpeg -i "$f" -ar 8000 -ac 1 -acodec amr_nb -b:a 12.2k amr/"$(basename $f .wav).amr"
  ffmpeg -i "$f" -ar 16000 -ac 1 -acodec libopus -b:a 24k opus/"$(basename $f .wav).opus"
done
# Then decode back to 16k wav and evaluate
```

**Report the table:**

| Condition | EER (baseline model) | EER (codec-augmented training) |
|---|---|---|
| Clean 16 kHz | — | — |
| Opus 24 kbps | — | — |
| G.711 µ-law 8 kHz | — | — |
| AMR-NB 12.2 kbps | — | — |
| In-the-Wild | — | — |

Then train a second model with codec round-trips as augmentation and fill the second column. **A before/after robustness table is the single most convincing technical artifact you can produce**, and it directly answers the "does this work on a real phone call?" question that your entire problem statement implies.

Set honest targets: in-domain EER < 5%, In-the-Wild EER < 25% would already be respectable given §3.1. **Do not target or claim 99%.**

---

## 16. Honest limitations & judge Q&A preparation

Rehearse these. The team that answers hard questions calmly wins.

### 16.1 "What's your accuracy?"
> *"It depends on the condition, and we report all of them. Roughly X% EER in-domain on ASVspoof 2019 LA, Y% on In-the-Wild, Z% under G.711. The literature shows detectors that hit 2% in-domain fall past 20% in the wild, and we see the same. That's exactly why the acoustic score is only 24% of our decision weight and why we never escalate on acoustic evidence alone."*

### 16.2 "Can't an attacker just add breath sounds and room tone?"
> *"Yes, and we built that attack ourselves in our red-team module — it degrades our acoustic score from 0.71 to 0.44. What it doesn't do is give the attacker a call history with the branch manager, or authority to approve a ₹50 lakh verbal transfer, or a reason to sound unhurried. Evading the acoustics costs the attacker a lot; evading the context requires actually being the employee."*

### 16.3 "Do you really detect SynthID?"
> *"No. AudioSeal, yes — it's open-source with a published detector and we run it. SynthID detection is only available through Google's own tooling for their content; we've built the scanner as a pluggable interface so a provider detector drops in. We also don't let watermark results move the risk score at all, because no real attacker uses watermarked TTS — absence of a watermark must never look like evidence of authenticity."*

### 16.4 "Your >8 kHz feature — how does that work on a phone call?"
Answer from §10.4. Have the dual-profile weights table ready.

### 16.5 "What stops this from being a surveillance tool?"
> §13. Then show the ring buffer code and the break-glass audit block.

### 16.6 "What if it's wrong and you hold a legitimate customer's call?"
> *"That's why the ladder is graduated. Levels 1 and 2 are invisible or advisory — a false positive there costs nothing. Only levels 4 and 5 are disruptive, and those require cross-group corroboration plus a dwell time plus, at level 5, human confirmation. We also demo a deliberate false-positive stress case — a genuine distressed elderly customer on a bad line — and show the system holding at level 2."*

### 16.7 "Is 500 ms of audio enough to decide?"
> *"No, and we don't pretend otherwise. We emit INSUFFICIENT_EVIDENCE until we've accumulated about 3 seconds of actual speech. The 500 ms cadence is the update rate, not the evidence window — features are computed over a 2 s sliding window with a 500 ms hop."*

### 16.8 Known weaknesses to volunteer before being asked
- Insider fraud by a genuine human is not solved (§5.2 B1)
- Short utterances (<3 s) are undecidable
- Speaker verification degrades on channel mismatch (§12)
- Phase-based detection is weakening against modern diffusion vocoders (§10.2)
- Our intent lexicon is built by hand and will not generalise to an unseen scam script; a fine-tuned model needs labelled fraud data we don't have
- Adversarial perturbation with white-box model access is unsolved

Volunteering these is not weakness. It is the single clearest signal that you understand your own system.

---

## 17. Scope control — what to actually build

**Your plan document describes roughly 6–9 months of work.** You will not finish it. The most common way SIH teams lose is a broad, shallow, half-broken build. Tier ruthlessly.

### TIER 0 — The Demo Spine (if this doesn't work, nothing else matters)
1. WebRTC capture → Python inference → FeatureFrame → Java fusion → STOMP → React gauge, live, end-to-end
2. Real spectral + prosody + channel features (not stubs)
3. Fusion with EMA + corroboration + hysteresis
4. Intervention ladder L1–L5 with the approve-button lock actually working
5. Analyst console: gauge, identity card, evidence panel, transcript, intervention bar
6. Scenarios 1, 2, 5

### TIER 1 — The Differentiators That Land
7. **Asterisk AudioSocket + ARI actuation** (real call, real hold) ← *highest value-per-hour in the whole project*
8. Hash-chained ledger, persisted, with a live tamper demo
9. Challenge-response liveness with **server-side state**
10. Voice Passport + ECAPA verification
11. Codec-degradation robustness study (§15.3)
12. Senior Shield mode + Scenario 4
13. Forensic dossier PDF

### TIER 2 — If time remains
14. Cross-channel correlation timeline
15. Red-team lab UI
16. Compliance portal + fairness charts
17. AudioSeal watermark scanner
18. Twilio/Exotel PSTN path

### TIER 3 — Slides only, explicitly labelled "roadmap"
19. SIPREC / carrier SBC deployment
20. Federated learning, DP, secure enclaves
21. On-device ONNX int8 edge inference
22. CBS (Finacle/BaNCS) production connectors

**Rule: nothing moves from a lower tier to a higher one until the tier above is demo-stable.** And label roadmap items as roadmap on the slide — a judge who discovers an unlabelled fake feature will discount everything else you showed.

---

## 18. Current state of the codebase — audit findings

What actually exists: **37 Java files, 1,404 lines, one commit ("Initial backend setup"), no frontend, no ML engine, no call integration, no docs.** It compiles and boots; it is a well-organised skeleton with almost no working logic inside it.

### 18.1 Blocking defects

| # | Severity | Finding | Impact |
|---|---|---|---|
| 1 | **Critical** | `AudioStreamController` hardcodes `double syntheticLikelihood = 0.68` and passes fixed constants to the risk engine. **The audio is decoded and then ignored.** | There is no detection. The risk score is a constant. |
| 2 | **Critical** | `CallSession.audioBuffer` is an unbounded `ArrayDeque<byte[]>`, appended on every chunk; `drainAudioBuffer()` is never called by anything. | **Your headline "Zero Raw-Audio Retention" claim is false in the current code** — every byte is retained for the session lifetime, and it will OOM on a long call. |
| 3 | **Critical** | `ArrayDeque` is not thread-safe but is mutated from concurrent request threads. | Data race; corrupted buffer or lost chunks under any real load. |
| 4 | **Critical** | `WebSocketConfig` registers a STOMP broker, but there is **no `@MessageMapping` and no `SimpMessagingTemplate` anywhere**. | Zero real-time capability. Nothing is ever published to `/topic`. The entire live-telemetry story is unimplemented. |
| 5 | **Critical** | `HashChainedAuditService.append()` is **never called from anywhere**, stores to an in-memory `ArrayList`, has no genesis block, and takes `previousHash` as a caller-supplied parameter. `AuditBlockRepository` is never injected. | The compliance endpoint returns an empty ledger. The chain is forgeable and non-persistent. |
| 6 | **High** | `ChallengeResponseService` keeps no server-side state — `verify` trusts `challengeText` and `issuedAt` sent by the client. | The liveness check is trivially spoofable and therefore meaningless as a security control. |
| 7 | **High** | No CORS configuration in `SecurityConfig`. | The React dev server on :5173 will be blocked on every request. You will lose an hour to this on Day 1 if you don't fix it now. |
| 8 | **High** | No ML sidecar exists and no bridge service exists. The `RestTemplate` bean in `AppConfig` is never injected anywhere. | The entire inference plane is absent. |
| 9 | **High** | `FusedRiskEngineService` has no EMA, no hysteresis, no corroboration, no calibration, no availability handling. Weights are hardcoded magic numbers. | Missing the core mathematics; scores will flicker and false-positive. |

### 18.2 Design and hygiene issues

| # | Severity | Finding |
|---|---|---|
| 10 | Medium | Five services are **orphaned beans** — instantiated by Spring, injected nowhere, called by nothing: `CrossChannelCorrelationService`, `ForensicDossierService`, `NaturalLanguageFraudService`, `RelationshipGraphService`, `VoicePassportService`. Their controllers bypass them with inline hardcoded responses. |
| 11 | Medium | Intervention thresholds are hardcoded in `InterventionLadderService` **and** duplicated in `application.yml` under `sentinelvoice.risk.*`, which is never read. They also disagree with the design doc (0.35/0.55/0.75/0.90 vs 0.31/0.56/0.76/0.91). |
| 12 | Medium | `schema.sql` and Hibernate `ddl-auto: update` both create tables. For embedded H2 both run. Fragile and duplicated; `forensic_dossiers` is created but has no repository. Pick one — recommend dropping `schema.sql`, keeping `ddl-auto: update`, and adding `data.sql` for seed data only. |
| 13 | Medium | `spring-boot-starter-validation` is a dependency but no `@Valid`/`@NotNull` exists anywhere. No global `@ControllerAdvice` exception handler. |
| 14 | Medium | `POST /session/start` takes four `@RequestParam`s instead of a JSON body. Awkward for the frontend and inconsistent with the rest of the API. |
| 15 | Medium | `ChallengeResponseService` uses `java.util.Random`, not `SecureRandom`, for a security token. |
| 16 | Low | `.withSockJS()` only — a native `WebSocket` client cannot connect. Register both a SockJS and a raw endpoint. |
| 17 | Low | `FusedRiskEngineTest` asserts the score is in [0,1] — which the clamp guarantees unconditionally. The test cannot fail and verifies nothing. |
| 18 | Low | No logging anywhere. No correlation ID. Debugging a live demo will be painful. |
| 19 | Low | Port is 8082; README and the design docs say 8080. Pick one and make all three agree. |
| 20 | Low | `CallSession` is never evicted — a long-running process leaks sessions forever. Needs TTL. |
| 21 | Low | No Docker, no compose, no CI, no `.env`, no run scripts. |

### 18.3 What is genuinely good and should be kept

- Package structure (`model` / `service` / `controller` / `repository` / `config`) is clean and conventional.
- Java 21 + Spring Boot 3.3.4 + virtual threads enabled — correct modern choices.
- `InterventionLevel` enum and the 5-stage concept are right.
- Java records for DTOs (`AudioChunkDTO`, `RiskAssessmentResult`, `TransactionContext`) — idiomatic.
- `InterventionLadderService.resolve()` threshold logic is correct as far as it goes.
- The SHA-256 hex implementation itself is correct; only its usage is broken.

**Verdict: keep the skeleton, replace the internals.** Roughly 300 of the 1,404 lines survive. Do not start over — the structure is sound and rewriting it wastes a day.

---

## 19. Target repository layout

```
sentinelvoice/
├── docs/
│   ├── 00_PROJECT_CONTEXT.md          ← this file (pin in Cursor)
│   ├── 01_EXECUTION_PLAN.md
│   ├── contracts/                      ← JSON Schemas, frozen Day 1
│   │   ├── FeatureFrame.schema.json
│   │   ├── TelemetryFrame.schema.json
│   │   └── AuditBlock.schema.json
│   ├── ARCHITECTURE.md
│   ├── REGULATORY_DPDP_MAPPING.md
│   ├── EVALUATION_REPORT.md            ← the codec table; generated
│   └── PITCH_DECK_SCRIPT.md
│
├── ml-engine/                          ← INFERENCE PLANE (Python)
│   ├── pyproject.toml
│   ├── app/
│   │   ├── main.py                     FastAPI + WS /ingest/{sessionId}
│   │   ├── session.py                  per-session pipeline state
│   │   ├── ring_buffer.py
│   │   ├── normaliser.py               µ-law/A-law/PCM, resample, profile
│   │   ├── vad.py
│   │   ├── fast_path.py                orchestrates fast features
│   │   ├── slow_path.py                ASR + intent, async
│   │   ├── emitter.py                  FeatureFrame → Java WS
│   │   └── modules/
│   │       ├── spectral.py  phase.py  prosody.py  channel.py
│   │       ├── speaker.py   antispoof.py  watermark.py
│   │       ├── asr.py       intent.py     adversarial.py
│   │       └── calibrate.py
│   ├── models/                         ← weights (gitignored / LFS)
│   ├── benchmarks/
│   │   ├── build_codec_set.py
│   │   ├── run_eval.py
│   │   └── fairness.py
│   └── tests/
│
├── gateway/                            ← MEDIA PLANE
│   ├── asterisk_bridge.py              AudioSocket TCP → ring buffer
│   ├── pstn_bridge.py                  Twilio / Exotel adapters
│   └── asterisk/                       pjsip.conf, extensions.conf, ari.conf
│
├── backend/                            ← DECISION PLANE (existing, to be rebuilt)
│   └── src/main/java/com/sentinelvoice/
│       ├── config/     ws, security+CORS, props, jackson
│       ├── model/      domain + DTOs + enums
│       ├── ingest/     FeatureFrame WS endpoint + validation
│       ├── identity/   directory, claim, passport, presence
│       ├── context/    relationship, transaction policy, cross-channel
│       ├── fusion/     weights, EMA, corroboration, calibration
│       ├── intervention/ FSM, dwell, hysteresis, override
│       ├── actuation/  ARI client, txn lock, OOB MFA, CBS webhook
│       ├── audit/      chained ledger + verifier
│       ├── forensics/  dossier + PDF
│       ├── telemetry/  STOMP broadcaster
│       └── controller/ REST
│
├── frontend/                           ← PRESENTATION PLANE
│   └── src/{components,hooks,services,types,views}/
│
├── scenarios/                          ← seeded demo fixtures (YAML + audio)
├── docker-compose.yml                  asterisk + backend + ml + frontend
├── Makefile                            make dev / make demo / make eval
└── scripts/                            run_all.sh, run_all.bat, seed.sh
```

---

## 20. Demo runbook (7 minutes)

| Time | Beat | Screen | What you say |
|---|---|---|---|
| 0:00–0:45 | **The hook** | Play 5 s of a cloned voice. Then the CNAP slide. | *"India just rolled out CNAP — every call now shows a KYC-verified name. It tells you whose number this is. It cannot tell you that the voice is synthetic. That gap is where ₹70,000 crore goes."* |
| 0:45–1:30 | **The reframe** | The 4-claim table | *"Most detectors say '82% fake' after the call. We ask a different question: is this call about to cause fraud, and can we stop it mid-sentence?"* |
| 1:30–2:15 | **Architecture** | 4-plane diagram | *"Java never touches audio. One process holds PCM, in an 8-second ring buffer, and overwrites it. That's how the privacy claim is architecture and not a slide."* |
| 2:15–4:45 | **LIVE DEMO** | Place a real SIP call from a softphone. Cloned CEO audio injected via virtual cable. | Gauge climbs. Evidence panel lights up: *no breath in 22 seconds*, *CLI/claim mismatch*, *policy violation*. **The approve button greys out. Hold music starts. Supervisor card appears.** Then issue the liveness challenge and show 3.8 s vs 1.8 s. |
| 4:45–5:15 | **The refusal** | Scenario 5 | *"Now a genuine elderly customer, heavy accent, terrible line, emotional. Our acoustic score hits 0.68 — and the system stays at an advisory banner, because no contextual family corroborates. A detector alone would have escalated. That's the difference."* |
| 5:15–5:45 | **Social impact** | Senior Shield, Scenario 4 | Hinglish grandparent scam → red alert → one-touch family SOS. |
| 5:45–6:30 | **Proof** | Codec robustness table + reliability diagram + tamper the ledger live in H2 console and show the verifier catch it | *"We report EER under G.711 and AMR, not just on clean studio audio. And here's our audit chain detecting tampering in real time."* |
| 6:30–7:00 | **Close** | Roadmap slide, clearly labelled | *"Most teams tell you if a voice is fake. We tell you if a call is about to cost you money — and we stop it before it does."* |

**Rules for the demo:**
- Everything Tier-0/Tier-1 runs **offline on your laptop**. Asterisk in Docker. No venue Wi-Fi dependency.
- Record a video of the full run the night before as a fallback.
- One person drives, one narrates. Never both.
- Rehearse the auto-hold beat at least twenty times.

---

## 21. Glossary

| Term | Meaning |
|---|---|
| **AASIST** | Graph-attention anti-spoofing architecture; strong ASVspoof baseline |
| **AMR-NB/WB** | Adaptive Multi-Rate, the cellular speech codecs (4.75–12.2 / 6.6–23.85 kbps) |
| **ARI** | Asterisk REST Interface — control live channels over HTTP + WebSocket |
| **AudioSocket** | Asterisk dialplan app streaming raw audio over plain TCP with a 3-byte header |
| **Bonafide** | Genuine (non-spoofed) speech, in ASVspoof terminology |
| **CLI** | Calling Line Identification (the caller's number as signalled) |
| **CNAP** | Calling Name Presentation — India's KYC-verified caller name, national rollout by March 2026 |
| **CQT** | Constant-Q Transform; log-spaced frequency resolution |
| **DPDP Act 2023** | India's Digital Personal Data Protection Act |
| **ECAPA-TDNN** | Speaker-embedding network; 192-dim vectors are the standard output |
| **EER** | Equal Error Rate — the operating point where FAR = FRR. Lower is better |
| **G.711** | 64 kbps µ-law/A-law PCM, the classic PSTN codec, 8 kHz |
| **HNR** | Harmonics-to-Noise Ratio |
| **In-the-Wild** | Fraunhofer AISEC dataset of real-world audio deepfakes; the honest benchmark |
| **Jitter / Shimmer** | Cycle-to-cycle variation in pitch period / amplitude |
| **LFCC** | Linear-Frequency Cepstral Coefficients; better than MFCC for anti-spoofing |
| **min t-DCF** | Tandem Detection Cost Function; ASVspoof's primary metric |
| **Narrowband** | 8 kHz sampling, 300–3400 Hz passband — standard PSTN |
| **RIR / T60** | Room Impulse Response; T60 is reverberation decay time |
| **RVC** | Retrieval-based Voice Conversion — real-time voice cloning driven by a live speaker |
| **SIPREC** | RFC 7865/7866 standard for SIP-based session recording/media forking |
| **SLIN16** | Signed linear 16-bit PCM |
| **STOMP** | Simple Text Oriented Messaging Protocol, over WebSocket, used by Spring |
| **VAD** | Voice Activity Detection |
| **Vishing** | Voice phishing |

---

## 22. Sources

Statistics in §2 and §3, retrieved September 2026. Verify before putting on a slide and cite on the slide itself.

- CrowdStrike 2025 Global Threat Report (442% vishing increase, H1→H2 2024)
- Deloitte — GenAI-enabled fraud loss projection ($40B US by 2027)
- McAfee — 3-second voice cloning study; India voice-scam survey (via Observer Research Foundation analysis)
- Keepnet Labs / DeepStrike — banking deepfake vishing loss survey
- TRAI — *Response to DoT Back-Reference on Introduction of CNAP*, 28 October 2025 — `https://trai.gov.in/sites/default/files/2025-10/Response_CNAP_28102025.pdf`
- PIB Government of India, PRID 2183354, 28 October 2025 (CNAP)
- Department of Telecommunications — International Incoming Spoofed Calls Prevention System launch, October 2024
- Müller et al., *Does Audio Deepfake Detection Generalize?* — arXiv:2203.16263 (the In-the-Wild collapse)
- ASVspoof 5 Challenge (2024) — evaluation plan and results
- Recent generalization literature: arXiv:2501.14240, arXiv:2509.23618, arXiv:2606.16532 (2% in-domain vs >20% in-the-wild EER)

---

*End of context document. Build order and Cursor prompts: see `01_EXECUTION_PLAN.md`.*
