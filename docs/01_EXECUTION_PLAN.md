# SentinelVoice — Execution Plan & Cursor Prompt Book

**Companion to:** `00_PROJECT_CONTEXT.md` (read that first — this plan assumes it)
**Starting point:** existing Java backend skeleton (1,404 LOC, ~8% functional — see Context §18)
**Assumed team:** 4–6 people · **Assumed budget:** 14 working days (compression plans in §D)

---

## A. How to drive Cursor on this project

Getting this right is worth more than any single build step. Most teams lose days to Cursor confidently rewriting working code because it had no idea what the project was.

### A.1 Set up before you write a single prompt

**1. Put both documents in the repo.**
```
docs/00_PROJECT_CONTEXT.md
docs/01_EXECUTION_PLAN.md
```

**2. Create `.cursor/rules/sentinelvoice.mdc`** (or `.cursorrules` in the repo root on older versions):

```markdown
---
description: SentinelVoice project-wide rules
alwaysApply: true
---

# SentinelVoice — Project Rules

## Architecture (non-negotiable)
- FOUR PLANES: Media (audio capture) → Inference (Python/FastAPI) → Decision (Java/Spring) → Presentation (React).
- **Java NEVER handles raw PCM audio.** Java receives only FeatureFrame JSON (floats + enums).
- Raw audio exists ONLY in ml-engine, ONLY in a bounded ring buffer (<=8s), and is overwritten in place.
- Never write audio to disk, to a database, to a log line, or to any network destination other than
  the in-process feature extractor.

## Contracts
- `docs/contracts/*.schema.json` are FROZEN. Never change a contract to make code compile.
  If a field is missing, say so and stop — do not invent fields.
- TypeScript types in `frontend/src/types/` are GENERATED from those schemas. Do not hand-edit.

## Java (backend/)
- Java 21, Spring Boot 3.3.4, constructor injection only, no field `@Autowired`.
- Records for DTOs, classes for entities. No Lombok.
- Every service is injected somewhere. If you create a service, wire it. No orphaned beans.
- Every state change that affects a risk decision MUST append to the audit ledger.
- Never hardcode a risk value, threshold, or weight in a controller or service body.
  All tunables live in `application.yml` under `sentinelvoice.*` and bind via @ConfigurationProperties.

## Python (ml-engine/)
- Python 3.11, FastAPI, type hints on every public function, numpy for DSP.
- Fast path budget: 120ms per 500ms window. Slow path runs async and never blocks the fast path.
- Every feature module exposes: `extract(audio: np.ndarray, sr: int, profile: ChannelProfile) -> dict`
  and MUST return `{"available": False}` rather than raising when the channel can't support it.

## React (frontend/)
- React 18 + TypeScript + Vite + Tailwind. Functional components + hooks.
- No localStorage/sessionStorage.
- All telemetry flows through the `useTelemetrySocket` hook. Components never open their own sockets.

## Style
- Small, reviewable diffs. Change only what the task asks for.
- Do not add features that were not requested.
- If the task is ambiguous or the existing code conflicts with these rules, STOP and ask.
- Write tests for pure logic (fusion math, FSM transitions, hash chaining, DSP feature functions).
```

**3. Add a `.cursorignore`** so Cursor doesn't waste context on junk:
```
backend/target/
node_modules/
ml-engine/models/
ml-engine/.venv/
**/*.wav
**/*.mp3
datasets/
.git/
```

### A.2 The prompting workflow that works

| Do | Don't |
|---|---|
| Start every prompt with `@docs/00_PROJECT_CONTEXT.md` and the specific files to touch | Say "build the ML engine" |
| One step from this plan = one Cursor Composer session | Chain five steps in one prompt |
| Paste the **acceptance criteria** into the prompt verbatim | Assume Cursor knows when it's done |
| Run the **verify command** yourself after every step | Trust "I've implemented this" |
| `git commit` after every green step | Let 8 steps of unreviewed diff pile up |
| Use Composer/Agent mode for multi-file steps, inline chat for single-file edits | Use agent mode for a 3-line fix |
| Use a strong reasoning model for math/architecture steps, a fast one for boilerplate | Use the same model for everything |

**The single most important habit:** after each step, run the verify command. If it fails, paste the *exact* error back into the same session rather than starting a new one. Cursor debugs well with full context and badly without it.

**When Cursor goes off the rails** (invents endpoints, rewrites unrelated files, changes contracts): `git checkout .`, then re-prompt with tighter scope and an explicit "only modify these files" list. Don't argue with it for twenty turns.

### A.3 Prompt template

Every prompt in this document follows this shape. Reuse it for anything not covered here.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md  @<other relevant files>
TRACK: <Java backend | Python ML | React frontend | DevOps | AI/model>
TASK: <one sentence>
FILES TO CREATE/MODIFY: <explicit list — nothing else>
REQUIREMENTS:
  1. ...
  2. ...
DO NOT: <the specific failure modes for this step>
ACCEPTANCE: <exactly how I will verify this works>
```

---

## B. Priority tiering & schedule

Follow Context §17. The order below is chosen so that **you have a working end-to-end demo by Day 5** and everything after that makes it better rather than making it exist.

```
D1  ██ P0 Foundations ────────────── contracts frozen, backend surgery begins
D2  ██ P1 Backend surgery ────────── ██ P2 ML skeleton (parallel)
D3  ██ P3 THE SPINE ──────────────── first end-to-end frame
D4  ██ P4 Real acoustic features ─── ██ P6 Analyst console (parallel)
D5  ██ P5 Fusion math ───────────── ◄── DEMO-ABLE MILESTONE
D6  ██ P7 Asterisk + ARI actuation ← highest value/hour in the project
D7  ██ P7 cont. ─────────────────── ██ P8 Identity & passport
D8  ██ P9 Audit + forensics ─────── ██ P10 ASR + intent (parallel)
D9  ██ P10 cont. ───────────────── ██ P11 Senior Shield + scenarios
D10 ██ P12 Evaluation + codec study ← your unique technical artifact
D11 ██ P13 Tier-2 extras ────────── cross-channel, red-team, compliance
D12 ██ Hardening, latency, failure modes, seed data
D13 ██ P14 Deck, docs, dossier, video fallback
D14 ██ REHEARSE ×20. Freeze code. No new features.
```

**Parallel track ownership (adjust to your team):**

| Track | Owner | Phases |
|---|---|---|
| Java / Decision Plane | Dev A | P1, P5, P8, P9 |
| Python / Inference Plane | Dev B | P2, P4, P10 |
| ML / model & evaluation | Dev C | P4 (models), P12 |
| React / Presentation | Dev D | P6, P11, P13 |
| Telephony / DevOps | Dev E | P0, P3, P7 |
| Pitch, docs, compliance | Dev F | P14, §13 of Context |

---

## C. Step legend

Each step carries:

- **ID** — `P<phase>.<step>` · reference these in commits: `git commit -m "P4.3: prosody features"`
- **Track** — 🟥 Java backend · 🟦 Python ML · 🟩 React frontend · 🟨 DevOps/Telephony · 🟪 AI/model
- **Est** — one developer's working hours
- **Deps** — steps that must be green first
- **Acceptance** — the objective test. Not "looks right."

---

# PHASE 0 — Foundations

## P0.1 — Repo restructure & tooling
**Track:** 🟨 DevOps · **Est:** 1.5 h · **Deps:** none

**What it does:** Reshapes the current single-module repo into the four-plane layout from Context §19, adds the Cursor rules, Docker compose skeleton, and a Makefile. **Why first:** every subsequent step assumes these paths exist. Doing it later means a painful mass-move.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 19 "Target repository layout")

TRACK: DevOps

TASK: Restructure this repository to the four-plane layout in Context §19, without
breaking the existing Java backend.

FILES TO CREATE:
  .cursor/rules/sentinelvoice.mdc   (content: I will paste separately — create the file with a placeholder header)
  .cursorignore
  .gitignore                        (extend the existing one)
  docker-compose.yml
  Makefile
  README.md                         (replace the existing one)
  ml-engine/.gitkeep
  gateway/.gitkeep
  frontend/.gitkeep
  scenarios/.gitkeep
  docs/contracts/.gitkeep

REQUIREMENTS:
 1. Do NOT move or edit any existing file under backend/src. The Java module stays where it is.
 2. .gitignore must additionally exclude: ml-engine/.venv/, ml-engine/models/, ml-engine/__pycache__/,
    frontend/node_modules/, frontend/dist/, *.wav, *.mp3, *.flac, datasets/, .env, .env.*, *.onnx, *.pt
 3. .cursorignore excludes: backend/target/, node_modules/, ml-engine/models/, ml-engine/.venv/,
    **/*.wav, **/*.mp3, datasets/, .git/
 4. docker-compose.yml defines four services with correct depends_on but NO implementations yet —
    just named stubs with build contexts and port mappings:
      asterisk (network_mode: host), ml-engine (8000), backend (8080), frontend (5173)
    Add a comment in each block saying which Phase implements it.
 5. Makefile targets (each printing a TODO where not yet implemented):
      make dev        - start all services for development
      make backend    - run Spring Boot
      make ml         - run FastAPI with reload
      make frontend   - run Vite dev server
      make asterisk   - start the Asterisk container
      make test       - run all test suites
      make eval       - run the ML benchmark suite
      make demo       - seed scenarios and start everything
      make clean
 6. README.md: one-paragraph description, the four-plane diagram from Context §6.2,
    prerequisites (Java 21, Node 20+, Python 3.11, Docker), and quickstart commands.
    Note that full documentation lives in docs/.

DO NOT: create any Python, Java, or React source files. This step is structure only.

ACCEPTANCE:
  - `tree -L 2 -I 'target|.git'` shows ml-engine/, gateway/, frontend/, backend/, docs/, scenarios/
  - `cd backend && ./mvnw -q compile` still succeeds (or mvn compile with your local Maven)
  - `make` with no args prints the target list
  - `git status` shows no deleted files under backend/src
```

**Verify:** `tree -L 2 -I 'target|.git|node_modules'` and `cd backend && mvn -q compile`

---

## P0.2 — Freeze the data contracts
**Track:** 🟨 DevOps + 🟪 Design · **Est:** 2 h · **Deps:** P0.1

**What it does:** Writes `FeatureFrame`, `TelemetryFrame`, and `AuditBlock` as JSON Schema, plus generated TypeScript types. **Why it's critical:** this is what lets five people build four planes in parallel without blocking. Freeze it today and nobody waits on anybody.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 8 "Data contracts")

TRACK: DevOps / contracts

TASK: Turn the JSON examples in Context §8 into formal, frozen JSON Schema (draft 2020-12)
and generate TypeScript types from them.

FILES TO CREATE:
  docs/contracts/FeatureFrame.schema.json
  docs/contracts/TelemetryFrame.schema.json
  docs/contracts/AuditBlock.schema.json
  docs/contracts/SessionStartRequest.schema.json
  docs/contracts/ChallengeIssued.schema.json
  docs/contracts/ChallengeResult.schema.json
  docs/contracts/InterventionOverride.schema.json
  docs/contracts/CrossChannelEvent.schema.json
  docs/contracts/README.md
  frontend/src/types/contracts.ts
  scripts/gen-types.sh

REQUIREMENTS:
 1. Transcribe the FeatureFrame and TelemetryFrame examples from Context §8 EXACTLY —
    same field names, same nesting, same enums. Do not rename, reorder semantics, or "improve" them.
 2. Mark required vs optional precisely. Every evidence-family object in FeatureFrame has a
    required boolean `available`. When available is false, the numeric fields are optional.
 3. Define shared enums as $defs and $ref them:
      ChannelProfile: PSTN_NARROWBAND | VOIP_WIDEBAND | WEBRTC_WIDEBAND
      InterventionLevel: LEVEL_1_SILENT | LEVEL_2_SOFT_NUDGE | LEVEL_3_STEP_UP_MFA
                         | LEVEL_4_AUTO_HOLD | LEVEL_5_TERMINATE
      RiskState: SCORED | INSUFFICIENT_EVIDENCE | DEGRADED
      IdentityVerdict: VERIFIED | INCONCLUSIVE | IMPERSONATION_HUMAN | IMPERSONATION_SYNTHETIC
      Trend: RISING | FALLING | STABLE
      ReasonSeverity: LOW | MEDIUM | HIGH | CRITICAL
 4. All probability/score fields: "type":"number","minimum":0,"maximum":1.
 5. InterventionOverride requires a non-empty `reason` string (min 10 chars) and an `analystId` —
    an override is an audited event and must never be anonymous.
 6. scripts/gen-types.sh uses json-schema-to-typescript to regenerate frontend/src/types/contracts.ts.
    Add a header comment to the generated file: "GENERATED — DO NOT EDIT. Run scripts/gen-types.sh".
 7. docs/contracts/README.md states the freeze policy: changing a schema requires bumping the
    `schema` version string and updating all four planes in the same commit.

DO NOT: add any field that is not in Context §8. Do not make anything optional "for flexibility."

ACCEPTANCE:
  - `npx ajv-cli compile -s docs/contracts/FeatureFrame.schema.json` succeeds for all schemas
  - `bash scripts/gen-types.sh` produces frontend/src/types/contracts.ts with a FeatureFrame
    and TelemetryFrame interface
  - The example JSON blocks from Context §8, saved as fixtures, validate against their schemas
```

**Verify:** `npx ajv-cli@5 compile -s docs/contracts/*.schema.json && bash scripts/gen-types.sh`

---

# PHASE 1 — Backend surgery

> These steps fix the defects catalogued in Context §18. Do them before building anything new on top. Every one of them is a bug that will otherwise cost you a day later, usually during a demo.

## P1.1 — Config, CORS, properties binding, exception handling
**Track:** 🟥 Java · **Est:** 2 h · **Deps:** P0.1

**What it does:** Fixes defects #7 (no CORS → frontend blocked), #11 (thresholds hardcoded *and* duplicated in unread YAML), #13 (validation dependency unused, no error handler), #16 (SockJS-only), #18 (no logging), #19 (port inconsistency).

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 9.5, 18)
FILES: @backend/src/main/java/com/sentinelvoice/config/SecurityConfig.java
       @backend/src/main/java/com/sentinelvoice/config/WebSocketConfig.java
       @backend/src/main/resources/application.yml

TRACK: Java backend

TASK: Fix the configuration layer: CORS, typed configuration properties, WebSocket endpoints,
global exception handling, and structured logging.

FILES TO CREATE/MODIFY:
  MODIFY  config/SecurityConfig.java
  MODIFY  config/WebSocketConfig.java
  MODIFY  src/main/resources/application.yml
  CREATE  config/SentinelProperties.java
  CREATE  config/JacksonConfig.java
  CREATE  web/GlobalExceptionHandler.java
  CREATE  web/ApiError.java
  CREATE  web/CorrelationIdFilter.java

REQUIREMENTS:
 1. SecurityConfig: add a CorsConfigurationSource bean allowing origins
    http://localhost:5173 and http://localhost:3000, methods GET/POST/PUT/DELETE/OPTIONS,
    all headers, credentials true, maxAge 3600. Wire it with http.cors(Customizer.withDefaults()).
    Keep csrf disabled and permitAll for now, but add a TODO comment that P13 adds real auth.
 2. WebSocketConfig: register the STOMP endpoint TWICE — once with .withSockJS() and once without —
    so both SockJS and native WebSocket clients can connect. Set allowed origin patterns to the
    same list as CORS (not "*"). Increase message size limits: setMessageSizeLimit(256*1024),
    setSendBufferSizeLimit(1024*1024), setSendTimeLimit(20000).
 3. CREATE SentinelProperties as an @ConfigurationProperties(prefix="sentinelvoice") record/class
    binding ALL tunables. Nested groups:
      fusion:  weights (wideband + narrowband maps), lambdaUp, lambdaDown, familyThresholds,
               linguisticStalenessTauMs, minSpeechMsForScoring
      intervention: per-transition upThreshold, downThreshold, dwellMs (see Context §9.5 table)
      ml:      baseUrl, websocketUrl, connectTimeoutMs, frameStalenessMs
      session: ttlMinutes, maxConcurrent
      audit:   genesisPrefix
    Populate application.yml with EXACTLY the values from Context §9.1 and §9.5. Add @Validated
    with constraints so a bad config fails at startup, not at runtime.
 4. GlobalExceptionHandler (@RestControllerAdvice): handle MethodArgumentNotValidException,
    ConstraintViolationException, IllegalArgumentException, NoSuchElementException, and a catch-all.
    Return a consistent ApiError record {timestamp, status, error, message, path, correlationId}.
    Never leak a stack trace in the response body; log it at ERROR with the correlation ID.
 5. CorrelationIdFilter: a OncePerRequestFilter that reads X-Correlation-Id or generates a UUID,
    puts it in the SLF4J MDC as "cid", and echoes it on the response. Add %X{cid} to the
    logging pattern in application.yml.
 6. JacksonConfig: ObjectMapper with JavaTimeModule, WRITE_DATES_AS_TIMESTAMPS disabled,
    FAIL_ON_UNKNOWN_PROPERTIES disabled, and a canonical writer bean (sorted map keys,
    used later by the audit ledger for stable hashing).
 7. application.yml: change server.port to 8080 for consistency with the docs. Set
    logging.level.com.sentinelvoice=DEBUG. REMOVE the schema.sql-vs-ddl-auto conflict by
    deleting src/main/resources/schema.sql and keeping jpa.hibernate.ddl-auto=update.

DO NOT: touch any service or controller class in this step. Config layer only.

ACCEPTANCE:
  - Application starts on port 8080
  - `curl -i -X OPTIONS http://localhost:8080/api/v1/session/start -H "Origin: http://localhost:5173"
     -H "Access-Control-Request-Method: POST"` returns 200 with Access-Control-Allow-Origin
  - Every log line contains a correlation ID
  - Deliberately setting sentinelvoice.fusion.lambdaUp: 5.0 in application.yml fails startup with
    a clear validation message
  - schema.sql no longer exists and the app still creates its tables
```

**Verify:**
```bash
cd backend && mvn spring-boot:run
curl -i -X OPTIONS localhost:8080/api/v1/session/start -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: POST"
```

---

## P1.2 — Purge audio from the Decision Plane
**Track:** 🟥 Java · **Est:** 2 h · **Deps:** P1.1

**What it does:** Fixes defects #1, #2, #3 — the three critical ones. Removes the unbounded, non-thread-safe, never-drained audio buffer from `CallSession`, deletes the fake hardcoded risk score in `AudioStreamController`, and makes the "zero raw-audio retention" claim **actually true in code**. **This is the most important single step in Phase 1.** Right now your central privacy claim is false and a judge who reads the repo will find it.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 6.1, 6.3, 18.1)
FILES: @backend/src/main/java/com/sentinelvoice/model/CallSession.java
       @backend/src/main/java/com/sentinelvoice/service/CallSessionManager.java
       @backend/src/main/java/com/sentinelvoice/controller/AudioStreamController.java

TRACK: Java backend

TASK: Remove ALL raw-audio handling from the Java backend. Java is the Decision Plane and must
never hold PCM. Replace the session model with a telemetry-history model.

FILES TO CREATE/MODIFY:
  MODIFY  model/CallSession.java
  MODIFY  service/CallSessionManager.java
  DELETE  controller/AudioStreamController.java
  DELETE  model/AudioChunkDTO.java
  CREATE  model/SessionState.java            (enum)
  CREATE  model/TelemetryHistory.java
  CREATE  service/SessionEvictionScheduler.java

REQUIREMENTS:
 1. CallSession: DELETE the audioBuffer Deque, appendAudioChunk(), and drainAudioBuffer().
    Replace with:
      - sessionId, callerId (CLI), calleeId (recipient), createdAt, lastFrameAt
      - SessionState: INITIALISING | ACTIVE | ON_HOLD | TERMINATED | CLOSED
      - ChannelProfile channelProfile
      - a bounded TelemetryHistory (ArrayDeque capped at 600 entries = 5 minutes at 500ms)
        storing only {seq, tsMs, instantaneousRisk, smoothedRisk, level, familyScores}
      - smoothedRisk (volatile double), currentLevel (volatile), levelChangedAtMs
      - cumulativeSpeechMs
      - Map<String,Object> metadata as a ConcurrentHashMap
      - identity/context fields will be added in P8 — leave a TODO
    Make every mutable collection thread-safe. Document with a class-level Javadoc:
    "Contains NO audio. Raw PCM never enters the Decision Plane. See Context §6.3."
 2. CallSessionManager: delete appendAudio() and drainAudio(). Add:
      createSession(SessionStartRequest) -> CallSession
      getSession(String) -> Optional<CallSession>
      requireSession(String) -> CallSession  (throws NoSuchElementException)
      recordTelemetry(String sessionId, TelemetryEntry)
      closeSession(String sessionId)
      activeSessionCount()
    Enforce sentinelvoice.session.maxConcurrent — reject beyond it with a clear exception.
 3. DELETE AudioStreamController.java and AudioChunkDTO.java entirely. Audio ingestion moves to
    the Python Inference Plane (P2/P3). Leave NO stub, NO deprecated endpoint.
 4. SessionEvictionScheduler: @Scheduled every 60s, closes sessions idle longer than
    sentinelvoice.session.ttlMinutes. Log each eviction at INFO with sessionId and reason.
    Enable @EnableScheduling on the application class.
 5. Update CallSessionController to use the new manager API and a JSON @RequestBody
    SessionStartRequest record {sessionId (optional - generate if absent), callerId, calleeId,
    channelProfile, scenarioId (optional)} with @Valid. Remove the @RequestParam signature.
 6. Add a unit test SessionRetentionTest asserting by reflection that CallSession declares no
    field of type byte[], Deque<byte[]>, or anything named *audio*. This test is the executable
    form of our privacy claim — add a comment saying so.

DO NOT: keep any "just in case" audio path. Do not leave a commented-out buffer.

ACCEPTANCE:
  - `grep -ri "byte\[\]" backend/src/main/java` returns nothing outside the audit hashing code
  - `grep -ri "audio" backend/src/main/java` returns only comments/Javadoc, no fields or logic
  - SessionRetentionTest passes
  - POST /api/v1/session/start with a JSON body returns 200 and a session descriptor
  - A session idle past the TTL disappears from GET /api/v1/session/{id} within 60s
```

**Verify:**
```bash
cd backend && mvn -q test
grep -rn "byte\[\]\|audioBuffer\|appendAudio" src/main/java | grep -v audit
```

---

## P1.3 — Repair the audit ledger
**Track:** 🟥 Java · **Est:** 3 h · **Deps:** P1.1

**What it does:** Fixes defect #5. Today `append()` is never called, there's no genesis block, `previousHash` is supplied by the caller (so the chain is forgeable), the ledger is a non-persistent `ArrayList`, and `AuditBlockRepository` is never injected. Makes it a real tamper-evident ledger with a verification endpoint — **and a live tamper demo**, which is a 30-second beat worth more than five slides.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 9.7, 18.1 defect #5)
FILES: @backend/src/main/java/com/sentinelvoice/service/HashChainedAuditService.java
       @backend/src/main/java/com/sentinelvoice/model/AuditBlock.java
       @backend/src/main/java/com/sentinelvoice/repository/AuditBlockRepository.java
       @backend/src/main/java/com/sentinelvoice/controller/ComplianceAuditController.java

TRACK: Java backend

TASK: Rebuild the hash-chained audit ledger so it is server-authoritative, persisted,
canonically hashed, and independently verifiable.

FILES TO CREATE/MODIFY:
  MODIFY  audit/AuditLedgerService.java        (move + rename from service/HashChainedAuditService.java)
  MODIFY  model/AuditBlock.java
  MODIFY  repository/AuditBlockRepository.java
  MODIFY  controller/ComplianceAuditController.java
  CREATE  audit/AuditEventType.java            (enum)
  CREATE  audit/ChainVerificationResult.java
  CREATE  audit/CanonicalJson.java
  CREATE  test/.../AuditLedgerTest.java

REQUIREMENTS:
 1. The service OWNS the chain. Signature becomes:
        AuditBlock append(String sessionId, AuditEventType type, Map<String,Object> payload)
    `previousHash` is NEVER a parameter. The service resolves the tail hash for that session
    from an in-memory ConcurrentHashMap<String,String> tailHashes, seeded from the repository
    on first touch (so it survives a restart).
 2. Genesis: on the first block for a session, previousHash =
        SHA256("SENTINELVOICE-GENESIS-v1|" + sessionId + "|" + createdAtEpochMs)
    Persist the genesis-derived block with eventType SESSION_OPENED.
 3. CanonicalJson: serialise payload with sorted keys, no whitespace, doubles formatted to
    exactly 6 decimal places, so the same event hashes identically on any machine and any JVM.
    Unit-test that two maps with different insertion order produce the same hash.
 4. Hash: SHA256(previousHash | tsEpochMs | sessionId | eventType | canonicalJson(payload)),
    hex lowercase. Store blockIndex (per session, 0-based) on the entity.
 5. AuditEventType enum, at minimum:
      SESSION_OPENED, FEATURE_FRAME_SCORED, RISK_LEVEL_CHANGED, INTERVENTION_ACTION_FIRED,
      ANALYST_OVERRIDE, CHALLENGE_ISSUED, CHALLENGE_RESULT, PASSPORT_ENROLLED,
      PASSPORT_ERASED, TRANSCRIPT_BREAK_GLASS_ACCESS, DOSSIER_GENERATED, SESSION_CLOSED
 6. AuditBlock entity: add blockIndex, make (sessionId, blockIndex) a unique constraint.
    Repository: findBySessionIdOrderByBlockIndexAsc, findTopBySessionIdOrderByBlockIndexDesc.
 7. Persist every block via the repository inside a @Transactional method. The in-memory map is
    a cache, not the source of truth.
 8. Verification: `ChainVerificationResult verify(String sessionId)` recomputes every hash from
    genesis and returns {valid, blockCount, brokenAtIndex, expectedHash, actualHash}.
    Expose GET /api/v1/compliance/verify/{sessionId} and
           GET /api/v1/compliance/audit-chain/{sessionId} (paged).
 9. IMPORTANT: do NOT hash-append from inside a hot loop synchronously if it would block the
    fusion path. Use a small bounded queue + @Async writer, but guarantee ordering per session.
10. AuditLedgerTest must cover: genesis determinism, chain validity over 100 blocks,
    canonical-JSON order independence, and TAMPER DETECTION — mutate one persisted block's
    payload and assert verify() reports the correct brokenAtIndex.

DO NOT: allow any caller to pass in a previous hash. Do not keep the old ArrayList ledger.

ACCEPTANCE:
  - AuditLedgerTest passes including the tamper case
  - Start the app, create a session, then:
      curl localhost:8080/api/v1/compliance/audit-chain/{id}   -> at least one block, index 0
      curl localhost:8080/api/v1/compliance/verify/{id}        -> {"valid":true,...}
  - Open the H2 console, UPDATE one audit_blocks row's details column, re-run verify
      -> {"valid":false,"brokenAtIndex":N}
```

**Verify:**
```bash
mvn -q -Dtest=AuditLedgerTest test
# then manual tamper test via http://localhost:8080/h2-console
```

---

## P1.4 — Delete the stubs, wire the orphans
**Track:** 🟥 Java · **Est:** 2 h · **Deps:** P1.2

**What it does:** Fixes defect #10 — five services exist as Spring beans that nothing injects, while their controllers return inline hardcoded JSON. Also removes the useless test (#17) and the `Random`-not-`SecureRandom` issue (#15). This is a cleanup step; it stops Cursor (and your teammates) from building on top of dead code.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 18.2, defects #10, #15, #17)
FILES: @backend/src/main/java/com/sentinelvoice/service/
       @backend/src/main/java/com/sentinelvoice/controller/

TRACK: Java backend

TASK: Eliminate orphaned beans and hardcoded controller responses. Every service must be injected
and called; every controller must delegate to a service.

REQUIREMENTS:
 1. AUDIT FIRST: list every @Service class and report, as a comment block at the top of your
    response, which classes inject it. Do not change anything until that list is produced.
 2. CrossChannelController currently returns an inline hardcoded List. Make it inject and delegate
    to CrossChannelCorrelationService. Move the placeholder data INTO the service, clearly marked
    `// SEEDED DEMO DATA - replaced by real correlation in P13.1`.
 3. ForensicReportController: inject and delegate to ForensicDossierService.
 4. RelationshipGraphService: delete the `if ("CFO".equalsIgnoreCase(claimedRole))` hardcoded branch.
    Replace with a method that takes a RelationshipQuery record and returns a
    RelationshipAssessment record {interactionCount365d, firstContact, hierarchyDistance, score,
    reasonCodes}. Back it with an in-memory seeded map for now and a TODO for P8.3.
 5. NaturalLanguageFraudService: delete the `contains("immediately") -> return 0.91` logic
    entirely. This service becomes a thin consumer of the `linguistic` block in the incoming
    FeatureFrame (arriving in P3). For now it maps FeatureFrame.linguistic -> a LinguisticAssessment
    record. No keyword matching in Java — that belongs in Python.
 6. ChallengeResponseService: replace java.util.Random with java.security.SecureRandom.
    Do not restructure it further — P8.4 rebuilds it properly with server-side state.
 7. DELETE FusedRiskEngineTest.java. It only asserts the score is within [0,1], which the clamp
    guarantees unconditionally, so it can never fail. P5.4 writes real fusion tests.
 8. Add a new ArchitectureTest using ArchUnit that FAILS the build if:
      - any class in `controller` package contains a numeric literal used as a risk score
        (heuristic: a double literal between 0.0 and 1.0 exclusive)
      - any @Service bean is not referenced by at least one other class
    If ArchUnit is too heavy, write the second check as a simple reflection-based test instead.

DO NOT: delete any service class. They are all needed; they just need wiring.

ACCEPTANCE:
  - Every @Service is injected somewhere (the ArchitectureTest proves it)
  - `grep -rn "0\.[0-9]" backend/src/main/java/com/sentinelvoice/controller/` returns nothing
  - mvn test is green
```

**Verify:** `mvn -q test && grep -rn "0\.[0-9]" src/main/java/com/sentinelvoice/controller/`

---

# PHASE 2 — Inference Plane skeleton

## P2.1 — ML engine scaffold + ring buffer + normaliser
**Track:** 🟦 Python · **Est:** 3 h · **Deps:** P0.1, P0.2

**What it does:** Creates the Python service, and — critically — the **bounded ring buffer and codec normaliser** that make the privacy claim real and let one codebase handle 8 kHz µ-law from a phone and 48 kHz float from a browser. Runs in parallel with Phase 1.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 6.3, 7.1, 7.2)
        @docs/contracts/FeatureFrame.schema.json

TRACK: Python ML

TASK: Scaffold the ml-engine FastAPI service with the audio ring buffer and the codec normaliser.
No feature extraction yet — this step is the audio plumbing only.

FILES TO CREATE:
  ml-engine/pyproject.toml
  ml-engine/app/__init__.py
  ml-engine/app/config.py
  ml-engine/app/types.py
  ml-engine/app/ring_buffer.py
  ml-engine/app/normaliser.py
  ml-engine/app/vad.py
  ml-engine/app/session.py
  ml-engine/app/main.py
  ml-engine/tests/test_ring_buffer.py
  ml-engine/tests/test_normaliser.py
  ml-engine/README.md

REQUIREMENTS:
 1. Python 3.11. Dependencies: fastapi, uvicorn[standard], numpy, scipy, soundfile,
    librosa, websockets, pydantic>=2, pydantic-settings, python-multipart, webrtcvad.
    Pin major versions. Include a [tool.pytest] section.

 2. types.py: Pydantic models mirroring the FROZEN contracts. ChannelProfile as a str Enum.
    An AudioFrame dataclass {session_id, seq, pcm: np.ndarray (float32, 16kHz, mono),
    profile, received_at_ms}.

 3. ring_buffer.py — RingBuffer class, THE privacy-critical component:
      - __init__(capacity_seconds: float = 8.0, sample_rate: int = 16000)
      - preallocated np.zeros(capacity, dtype=np.float32). NEVER grows.
      - write(samples) overwrites in place, wrapping. Never appends, never reallocates.
      - read_window(duration_s, hop_offset_s=0) -> np.ndarray copy of the most recent window
      - zeroise() fills the buffer with zeros in place (buf[:] = 0.0) for end-of-session
      - a `total_samples_written` counter for sequencing
      - Module docstring: "Raw PCM exists ONLY here. Fixed capacity, overwritten in place,
        never persisted. This class is the enforcement point for Context §6.3."
    Tests must assert: buffer.nbytes is constant after 10,000 writes; wraparound preserves the
    most recent N samples correctly; zeroise() leaves no nonzero sample.

 4. normaliser.py — one function, heavily tested, that everything else depends on:
        normalise(raw: bytes, encoding: str, in_rate: int, channels: int)
            -> tuple[np.ndarray, ChannelProfile]
      Supported encodings: "pcm_s16le", "pcm_f32le", "mulaw", "alaw", "slin16".
      - mulaw/alaw decode via audioop (stdlib) or a numpy lookup table
      - downmix stereo to mono by averaging
      - resample to 16000 Hz using scipy.signal.resample_poly (NOT naive decimation —
        it aliases and will corrupt your spectral features)
      - normalise to float32 in [-1, 1]
      - infer ChannelProfile: in_rate <= 8000 -> PSTN_NARROWBAND;
        encoding in (mulaw, alaw) -> PSTN_NARROWBAND; else if in_rate >= 16000 and the source is
        a browser -> WEBRTC_WIDEBAND; else VOIP_WIDEBAND. Accept an explicit profile override.
      Tests: round-trip a known sine through mulaw and assert SNR > 25 dB; assert 8k->16k
      resampling preserves a 1 kHz tone's peak bin; assert output dtype/shape/range invariants.

 5. vad.py: thin wrapper over webrtcvad (30 ms frames, aggressiveness 2) returning
    is_speech(window) -> bool and speech_ratio(window) -> float. The fusion engine uses this
    to freeze the EMA during silence.

 6. session.py: PipelineSession holding {session_id, ring_buffer, profile, seq counter,
    cumulative_speech_ms, created_at}. A SessionRegistry dict with create/get/close;
    close() MUST call ring_buffer.zeroise().

 7. main.py: FastAPI app with
      GET  /health                       -> {status, version, active_sessions}
      POST /session/{sid}/open           -> creates a PipelineSession
      POST /session/{sid}/close          -> zeroises and removes
      WS   /ingest/{sid}                 -> accepts a JSON "hello" frame then binary audio frames;
                                            writes to the ring buffer; returns an ack every 10 frames
    Add structured logging. NEVER log audio bytes or their content — log only lengths and counters.

DO NOT: implement any feature extraction. Do not write audio to disk anywhere, including temp files.

ACCEPTANCE:
  - `cd ml-engine && pip install -e . && pytest` all green
  - `uvicorn app.main:app --reload` starts; GET /health returns 200
  - A test client can open a session, stream 100 frames of 500ms PCM over the WS, and close it
  - `grep -rn "open(" ml-engine/app/ | grep -v "#"` shows no file writes of audio
```

**Verify:** `cd ml-engine && pytest -q && uvicorn app.main:app --port 8000 &  curl localhost:8000/health`

---

## P2.2 — FeatureFrame emitter + Java ingest endpoint
**Track:** 🟦 Python + 🟥 Java · **Est:** 3 h · **Deps:** P2.1, P1.2

**What it does:** Builds the bridge between the planes — Python emits `FeatureFrame` over a persistent WebSocket, Java validates and ingests it. This closes defects #4 and #8. Use **stub feature values** here; real features arrive in Phase 4. Getting the pipe working before the payload is what makes Day 3's end-to-end milestone achievable.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 6.2, 8.1)
        @docs/contracts/FeatureFrame.schema.json
        @ml-engine/app/main.py @ml-engine/app/session.py

TRACK: Python ML + Java backend  (do the Python side first, then the Java side)

TASK: Build the FeatureFrame transport: Python emits a schema-valid FeatureFrame every 500ms
over a persistent WebSocket; Java receives, validates, and hands it to the session manager.

PYTHON FILES TO CREATE:
  ml-engine/app/emitter.py
  ml-engine/app/fast_path.py         (STUB features for now)
  ml-engine/app/scheduler.py
  ml-engine/tests/test_emitter.py

JAVA FILES TO CREATE:
  backend/.../ingest/FeatureFrameSocketHandler.java
  backend/.../ingest/FeatureFrameIngestService.java
  backend/.../model/FeatureFrame.java          (records, mirroring the schema exactly)
  backend/.../ingest/FrameValidationException.java
  backend/.../test/FeatureFrameIngestTest.java

PYTHON REQUIREMENTS:
 1. scheduler.py: per session, an asyncio task firing every 500ms that reads a 2.0s window from
    the ring buffer (hop 500ms), runs fast_path.extract(), builds a FeatureFrame, and emits it.
    Skip emission if fewer than 2.0s of audio have been written. Track and report fastPath latency.
 2. fast_path.py: for THIS step return deterministic stub values derived cheaply from the audio
    so the pipe is testable and visibly responsive — e.g. voice.spoofProbability = a function of
    RMS energy, prosody fields = zeros with available=false. Mark every stub with
    `# STUB - replaced in P4.x`. Set `available` honestly: false for anything not computed.
 3. emitter.py: a resilient WS client to the Java ingest endpoint with
    exponential-backoff reconnection, a bounded outbound queue (drop OLDEST on overflow —
    stale risk data is worse than missing data), and a metrics counter for dropped frames.
 4. Validate the frame against the JSON schema in DEBUG mode before sending (jsonschema lib),
    and fail loudly in tests. Do not validate in production mode (too slow at 2 Hz × N sessions —
    actually it's fine, but gate it behind config anyway).

JAVA REQUIREMENTS:
 5. FeatureFrame as nested Java records mirroring the schema field-for-field, with @JsonProperty
    where names differ. Use Optional/nullable for the fields that are absent when available=false.
 6. FeatureFrameSocketHandler: a raw Spring WebSocketHandler at /ws/features (NOT STOMP — this is
    a machine-to-machine pipe and doesn't need a broker). Register it in WebSocketConfig via
    WebSocketConfigurer. Validate: sessionId exists, seq is monotonic per session (log + drop
    out-of-order), frame age < sentinelvoice.ml.frameStalenessMs (drop stale with a WARN).
 7. FeatureFrameIngestService: updates CallSession.lastFrameAt and cumulativeSpeechMs, then
    (for now) logs the frame and stores it on the session. P5 wires it to the fusion engine.
 8. Add a Micrometer counter for frames received / dropped / stale, exposed on
    /actuator/metrics (add spring-boot-starter-actuator).
 9. FeatureFrameIngestTest: post the exact example JSON from Context §8.1 and assert it
    deserialises with every field populated correctly. This is the contract regression test.

DO NOT: put any DSP in Java. Do not change the FeatureFrame schema to make Java deserialisation
easier — fix the Java records instead.

ACCEPTANCE:
  - With both services up, streaming audio into /ingest/{sid} produces a Java log line roughly
    every 500ms showing seq incrementing
  - Killing the Java backend and restarting it: Python reconnects automatically within 5s
  - FeatureFrameIngestTest passes against the Context §8.1 example verbatim
  - curl localhost:8080/actuator/metrics/sentinel.frames.received shows a rising count
```

**Verify:** run both, stream a WAV through a test client, watch `seq` increment in the Java logs.

---

# PHASE 3 — The Spine (first end-to-end frame)

> **Milestone:** by the end of this phase, speaking into a browser microphone moves a number on a React dashboard, through real infrastructure, with nothing faked in between. Everything after this makes the spine better. Nothing after this makes it exist.

## P3.1 — WebRTC browser capture
**Track:** 🟩 React + 🟨 DevOps · **Est:** 3 h · **Deps:** P2.1

**What it does:** The first media source. Browser mic → AudioWorklet → 16 kHz mono Int16 → 500 ms frames → WebSocket to Python. The single most common failure here is leaving browser audio processing on, which silently destroys the artifacts you're trying to detect.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 11.2 "WebRTC browser tap")

TRACK: React frontend / media capture

TASK: Build the browser audio capture pipeline that streams 16kHz mono PCM to the ml-engine
WebSocket.

FILES TO CREATE:
  frontend/  (initialise with: npm create vite@latest frontend -- --template react-ts)
  frontend/src/audio/capture-worklet.js
  frontend/src/audio/AudioCapture.ts
  frontend/src/audio/resample.ts
  frontend/src/hooks/useAudioCapture.ts
  frontend/src/components/MicControl.tsx
  frontend/vite.config.ts        (proxy /ws to the ml-engine)
  frontend/tailwind.config.js    (Tailwind + PostCSS setup)

REQUIREMENTS:
 1. getUserMedia constraints — THIS IS CRITICAL AND EASY TO GET WRONG:
        audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false,
                 autoGainControl: false, sampleRate: 48000 }
    Browser noise suppression is a neural audio enhancer. Leaving it on strips the micro-artifacts
    we detect AND fabricates new ones, invalidating every acoustic feature. Add a prominent
    code comment saying exactly this, and surface a UI warning if
    track.getSettings().noiseSuppression is true.
 2. capture-worklet.js: an AudioWorkletProcessor that accumulates 128-sample quantums into
    a buffer and posts Float32Array chunks to the main thread. Do NOT use the deprecated
    ScriptProcessorNode.
 3. resample.ts: polyphase or windowed-sinc resampling from the device rate (usually 48000)
    to 16000. A naive "take every 3rd sample" decimation aliases badly — implement a proper
    low-pass + decimate, or use an OfflineAudioContext at 16000 Hz. Unit-test with a
    known 1 kHz sine and assert the output spectrum peak is still at 1 kHz.
 4. AudioCapture.ts: manages the AudioContext lifecycle, accumulates resampled Float32 into
    500ms frames (8000 samples), converts to Int16LE, and sends as a binary WebSocket frame.
    First message after connect is JSON:
      {"type":"hello","sessionId":"...","sampleRate":16000,"encoding":"pcm_s16le",
       "channelProfile":"WEBRTC_WIDEBAND"}
    Handle: permission denied, device change mid-stream, tab backgrounding (AudioContext
    suspends — detect and warn), and WS disconnect with backoff reconnect.
 5. useAudioCapture hook: {start, stop, isCapturing, level, error, framesSent}. Expose an
    RMS level so the UI can show a live input meter — you will need this to debug "is audio
    actually flowing" a dozen times during the build.
 6. MicControl.tsx: start/stop button, live level meter, frames-sent counter, and a red badge
    if any browser audio processing is detected as enabled.
 7. Tailwind: dark cybersecurity palette. Define CSS custom properties for the risk colour ramp
    (green #10b981 -> amber #f59e0b -> orange #f97316 -> red #ef4444) — every risk component
    will use these, so centralise them now.

DO NOT: send audio to the Java backend. Audio goes to ml-engine only (ws://localhost:8000/ingest/{sid}).

ACCEPTANCE:
  - Clicking Start produces a rising framesSent counter and a moving level meter
  - ml-engine logs show frames arriving with correct byte length (16000 bytes per 500ms frame)
  - The 1 kHz sine resampling test passes
  - Muting the mic drops the level meter to ~0 and the VAD in ml-engine reports speech=false
```

**Verify:** speak into the mic; `ml-engine` log should show `seq` incrementing and `speechPresent=true`.

---

## P3.2 — Telemetry broadcast + minimal console
**Track:** 🟥 Java + 🟩 React · **Est:** 3 h · **Deps:** P2.2, P3.1

**What it does:** Closes the loop. Java publishes `TelemetryFrame` to STOMP; React subscribes and renders a live risk gauge. **This is the end-to-end milestone.**

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 6.2, 8.2)
        @docs/contracts/TelemetryFrame.schema.json
        @backend/.../ingest/FeatureFrameIngestService.java

TRACK: Java backend + React frontend

TASK: Publish TelemetryFrames over STOMP and render a live risk gauge in React. Close the loop
from microphone to dashboard.

JAVA FILES:
  CREATE  telemetry/TelemetryBroadcaster.java
  CREATE  telemetry/TelemetryFrameBuilder.java
  CREATE  model/TelemetryFrame.java  (records mirroring the schema)
  MODIFY  ingest/FeatureFrameIngestService.java

REACT FILES:
  CREATE  frontend/src/services/stompClient.ts
  CREATE  frontend/src/hooks/useTelemetrySocket.ts
  CREATE  frontend/src/components/RiskGauge.tsx
  CREATE  frontend/src/views/AnalystConsole.tsx
  MODIFY  frontend/src/App.tsx

JAVA REQUIREMENTS:
 1. TelemetryBroadcaster wraps SimpMessagingTemplate and publishes to
    /topic/telemetry/{sessionId}. Add a guard: never publish more than once per 400ms per
    session (coalesce), so a burst can't flood the browser.
 2. TelemetryFrameBuilder converts (CallSession + FeatureFrame + fusion output) into a
    TelemetryFrame. For THIS step, fusion is a placeholder simple weighted sum — P5 replaces it.
    Populate families[], topReasons[] (empty for now), and intervention.level from the existing
    InterventionLadderService.
 3. FeatureFrameIngestService calls the builder and the broadcaster on every frame.
 4. Add a REST fallback GET /api/v1/session/{id}/telemetry/latest returning the last frame —
    invaluable for debugging when the socket misbehaves, and a safety net during the demo.

REACT REQUIREMENTS:
 5. stompClient.ts: @stomp/stompjs + sockjs-client. Auto-reconnect with backoff, connection
    state exposed. Connect to http://localhost:8080/ws-sentinel.
 6. useTelemetrySocket(sessionId): subscribes to /topic/telemetry/{sessionId}, returns
    {latest, history (last 300 frames), connectionState, error}. Every telemetry-consuming
    component uses THIS hook — no component opens its own socket.
 7. RiskGauge.tsx: an animated SVG arc, 0-100%, using the Tailwind risk colour ramp. Show the
    smoothed score as the primary arc and the instantaneous score as a thin secondary needle
    (this makes the EMA visible and is a nice detail judges notice). Animate transitions over
    ~300ms so it looks smooth at a 2 Hz update rate. Show the intervention level as a label.
 8. AnalystConsole.tsx: dark layout, MicControl + RiskGauge + a raw JSON telemetry panel
    (collapsible — you will live in this panel while debugging).
 9. Show a connection-state indicator prominently. A silent dead socket during a demo is the
    worst possible failure and you want it visible at a glance.

ACCEPTANCE:
  - Speaking into the mic moves the gauge within ~700ms of speaking
  - Stopping the Java backend shows a disconnected indicator; restarting reconnects automatically
  - The JSON panel shows seq incrementing and the family breakdown populated
  - *** THIS IS THE END-TO-END MILESTONE. Record a screen capture of it working. ***
```

**Verify:** mic → gauge moves. Commit and tag: `git tag milestone-spine`.

---

# PHASE 4 — Real acoustic features

> Replace every stub from P2.2. Each step is independently testable against audio files before it ever touches the live pipeline — build and test offline first, then plug in.

## P4.1 — Spectral & phase features
**Track:** 🟦 Python · **Est:** 4 h · **Deps:** P2.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 10.1, 10.2, 10.4)
FILES: @ml-engine/app/fast_path.py @ml-engine/app/normaliser.py

TRACK: Python ML / DSP

TASK: Implement real spectral and phase feature extraction with dual channel-profile support.

FILES TO CREATE:
  ml-engine/app/modules/spectral.py
  ml-engine/app/modules/phase.py
  ml-engine/tests/test_spectral.py
  ml-engine/tests/test_phase.py
  ml-engine/scripts/inspect_features.py     (CLI: feature dump for a WAV, for debugging)

REQUIREMENTS:
 1. spectral.py — extract(audio, sr, profile) -> dict. Compute:
      - LFCC (20 coeffs + delta + delta-delta), linear-frequency filterbank.
        LFCC outperforms MFCC for anti-spoofing because spoofing artifacts concentrate in
        high linear-frequency bands that mel-warping compresses away. Add that as a comment.
      - log-magnitude STFT stats: spectral centroid, rolloff(0.85, 0.95), flatness, bandwidth,
        flux, and per-band energy ratios
      - band definitions MUST depend on profile:
          WIDEBAND:   bands at 0-1k, 1-4k, 4-6k, 6-8k; high_band_ratio = E(>4k)/E(total)
          NARROWBAND: bands at 0-1k, 1-2k, 2-3k, 3-4k; high_band_ratio = E(>2.8k)/E(total)
          and set "high_band_available": profile != PSTN_NARROWBAND
      - CQT-based frame-grid periodicity: autocorrelation of the frame-energy envelope,
        looking for a peak at typical vocoder hop rates (256/512 samples)
    Return a flat dict of floats plus "available": True.
    IMPORTANT: if profile is PSTN_NARROWBAND, do NOT compute or return features above 4kHz.
    Return them as None with an explicit reason string, never as 0.0 — a zero would be
    interpreted by the fusion engine as a real measurement.

 2. phase.py — extract(audio, sr) -> dict. Compute:
      - STFT phase, unwrapped along the time axis
      - instantaneous phase deviation: delta_phi(k,m) = unwrap(phi(k,m) - phi(k,m-1))
      - phase randomness entropy: histogram delta_phi per band (32 bins), Shannon entropy,
        normalised to [0,1] by log2(32). Return mean and per-band values.
      - modified group delay function (tau_m) statistics
    Docstring MUST note the honest caveat from Context §10.2: phase-based detection was strong
    against 2019-era vocoders and is weakening against modern diffusion/flow vocoders. This
    feature is evidence, not proof.

 3. Performance: the combined spectral+phase extraction on a 2.0s 16kHz window must complete in
    < 60ms on a laptop CPU. Measure it in the test. If CQT is the bottleneck, compute it every
    4th window and carry forward.

 4. inspect_features.py: `python -m scripts.inspect_features path/to.wav --profile narrowband`
    prints every feature as a table. You will use this constantly. Add a --compare flag that
    takes two files (bonafide, spoof) and prints them side by side with the delta.

 5. Tests: use synthetic signals with known properties — a pure sine (flatness ~0, centroid at
    the tone), white noise (flatness ~1), a lowpassed signal (rolloff at the cutoff). Assert the
    narrowband profile returns None (not 0.0) for high-band features. Assert determinism.

DO NOT: train or load any model here. This step is pure signal processing.

ACCEPTANCE:
  - pytest green; timing test proves < 60ms per window
  - inspect_features.py on a real speech WAV vs a TTS WAV shows visibly different high_band_ratio
    and phase_entropy (record these numbers — they become a slide)
  - Narrowband profile returns None with a reason for >4kHz features
```

---

## P4.2 — Prosody, breath & micro-behaviour
**Track:** 🟦 Python · **Est:** 4 h · **Deps:** P2.1

**Why this step matters most:** these are your best narrowband features *and* your most explainable ones. "He spoke for 40 seconds and never inhaled" is the line that lands with every audience.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 10.3)

TRACK: Python ML / DSP

TASK: Implement prosodic and micro-behavioural feature extraction, including breath detection.

FILES TO CREATE:
  ml-engine/app/modules/prosody.py
  ml-engine/app/modules/breath.py
  ml-engine/tests/test_prosody.py
  ml-engine/tests/test_breath.py

REQUIREMENTS:
 1. Add `praat-parselmouth` to dependencies. Use Praat's implementations for jitter, shimmer and
    HNR rather than hand-rolling them — Praat's algorithms are the field reference and
    hand-rolled versions are a classic time sink that produces subtly wrong numbers.
    Provide a pure-librosa fallback path if parselmouth fails to install, gated by a config flag.

 2. prosody.py — extract(audio, sr) -> dict:
      - F0 contour via pYIN (librosa.pyin, fmin=60, fmax=400), voiced flags
      - f0_mean, f0_std, f0_range, f0_contour_entropy (histogram of first differences)
      - jitter: local, rap, ppq5   (Praat: PointProcess -> "Get jitter (local)")
      - shimmer: local, apq3, apq5
      - HNR (harmonicity, dB)
      - articulation rate proxy: syllable-nucleus count / voiced duration
      - pause statistics: count, mean/std duration, and a uniformity score
        (low variance in pause duration is a synthetic tell)
      - voiced_fraction
      Return available: False with a reason if the window has < 0.5s of voiced speech —
      jitter on 3 pitch periods is meaningless and will produce garbage that the fusion
      engine would treat as evidence.

 3. Add an `unnaturalness_score` in [0,1] computed from deviation from published human ranges:
      jitter_local outside [0.005, 0.015] -> penalty scaled by distance
      shimmer_local outside [0.03, 0.08]  -> penalty
      HNR > 28 dB                          -> penalty (too clean)
      f0_std very low relative to f0_mean  -> penalty (over-smooth)
    Document each range and its source in comments. Make the ranges configurable, not magic
    numbers in the function body.

 4. breath.py — detect_breaths(audio, sr) -> dict. Approach:
      - find low-energy, unvoiced segments (energy below an adaptive threshold, F0 absent)
      - within those, look for the breath signature: broadband noise with a spectral centroid
        in roughly 500-2500 Hz, duration 150-600ms, and a characteristic slow attack/decay
      - return: breath_events, breath_events_per_min, mean_breath_duration_ms,
        time_since_last_breath_ms, and pre_phonatory_ratio (fraction of breaths immediately
        preceding a voiced onset — real inhalations precede speech; misplaced synthetic
        "breath tokens" often don't)
    Humans: roughly 8-20 breath events/min in conversational speech. Zero breaths across 20+
    seconds of continuous speech is a strong synthetic indicator. Encode this as a
    `breath_absence_score` that only becomes meaningful after >= 15s of cumulative speech —
    before that, return available: False.

 5. Tests: synthesise a signal with known jitter by modulating pitch periods and assert the
    measured jitter is within 15% of ground truth. For breath, hand-label 5 short clips in
    tests/fixtures and assert detection recall >= 0.6 (be realistic — this is a heuristic detector,
    not a trained model, and claiming better in the test is lying to yourself).

DO NOT: report jitter/shimmer on windows with insufficient voiced speech. Return available: False.

ACCEPTANCE:
  - pytest green including the synthetic-jitter accuracy test
  - inspect_features.py on a real speech clip shows jitter in 0.5-1.5% and breaths detected
  - On a TTS clip, breath_events_per_min is near 0 and unnaturalness_score is elevated
  - Record both numbers — they go directly into your pitch deck
```

---

## P4.3 — Channel forensics & dual-profile logic
**Track:** 🟦 Python · **Est:** 4 h · **Deps:** P4.1

**Why:** this is your answer to the ">8 kHz doesn't exist on a phone call" question (Context §10.4), and double-compression is the one acoustic feature that gets *stronger* on PSTN.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 10.4, 3.2)

TRACK: Python ML / DSP

TASK: Implement channel forensics: reverberation plausibility, double-compression detection,
noise-floor analysis, and codec fingerprinting.

FILES TO CREATE:
  ml-engine/app/modules/channel.py
  ml-engine/tests/test_channel.py
  ml-engine/scripts/build_codec_fixtures.sh

REQUIREMENTS:
 1. RIR / reverberation estimate:
      - blind T60 estimation from the decay of the energy envelope after speech offsets
        (Schroeder backward integration on offset regions, or a simpler decay-rate fit)
      - return t60_ms, t60_confidence, and rir_plausible (bool)
      - PLAUSIBILITY: a live microphone in any real room yields T60 roughly 100-800ms.
        Near-zero T60 (< 30ms) indicates digitally injected audio (virtual audio cable,
        SIP media injection) with no acoustic path at all.
      - CAVEAT to encode: a close-talking headset in a treated room can be genuinely low, and
        a phone codec's noise suppression can flatten the tail. Return a confidence, and
        make the fusion engine weight by it. Never assert on T60 alone.

 2. Double-compression detection:
      - synthesised audio is typically rendered to WAV/MP3, then re-encoded by the telephony
        codec. Two quantisation stages leave a detectable signature.
      - approach: compute the histogram of first-digit / quantised MDCT-like coefficients
        and measure deviation from the expected smooth distribution (a Benford-style or
        periodicity test on the coefficient histogram)
      - return double_compression_score in [0,1] and the detected primary codec family
      - This feature is STRONGER on narrowband. Note that in the docstring.

 3. Noise floor analysis:
      - measure the noise floor in non-speech segments
      - noise_floor_db, noise_floor_stationarity (1 = perfectly constant across the window)
      - real rooms are non-stationary; synthetic "silence" or comfort noise is suspiciously
        constant. Beware: codec comfort-noise generation ALSO produces stationary silence,
        so this must not fire alone — note the confound in the docstring.

 4. Codec / bandwidth fingerprint:
      - effective bandwidth (highest frequency with meaningful energy)
      - detect the narrowband brick wall at ~3.4kHz, the wideband edge at ~7kHz
      - detect a MID-CALL bandwidth CHANGE (a re-INVITE / codec renegotiation) and flag it —
        an abrupt profile switch mid-call can indicate media injection
      - dc_offset, clipping_ratio

 5. build_codec_fixtures.sh: uses ffmpeg to produce, from a set of source WAVs, the codec-degraded
    variants for testing AND for the P12 evaluation:
        pcm_mulaw 8k, pcm_alaw 8k, amr_nb 12.2k, amr_nb 4.75k, libopus 24k, libopus 12k, mp3 64k
    Each round-tripped back to 16k WAV. This script is reused verbatim in P12.2.

 6. Tests: take a clean WAV, run it through ffmpeg mulaw round-trip, and assert
    double_compression_score rises measurably vs the original. Assert T60 on a synthetically
    reverberated signal (convolve with a generated exponential-decay RIR) recovers the
    known T60 within 30%.

ACCEPTANCE:
  - pytest green
  - bash scripts/build_codec_fixtures.sh produces all 7 variants
  - double_compression_score is measurably higher on a double-encoded file than a single-encoded one
  - A signal convolved with a synthetic 300ms RIR reports t60 in 200-400ms and rir_plausible=true
```

---

## P4.4 — Speaker embeddings (ECAPA-TDNN)
**Track:** 🟪 AI/model + 🟦 Python · **Est:** 3 h · **Deps:** P2.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 10.5, 12)

TRACK: AI / model integration

TASK: Integrate ECAPA-TDNN speaker embeddings for Voice Passport verification and intra-call
drift detection.

FILES TO CREATE:
  ml-engine/app/modules/speaker.py
  ml-engine/tests/test_speaker.py
  ml-engine/scripts/enrol_speaker.py

REQUIREMENTS:
 1. Use speechbrain's pretrained `speechbrain/spkrec-ecapa-voxceleb` (192-dim output).
    Load ONCE at startup into a module-level singleton — loading per window will destroy your
    latency budget. Add it to a `warmup()` called on FastAPI startup, and log the load time.
 2. embed(audio, sr) -> np.ndarray(192,), L2-normalised. Require >= 1.5s of speech;
    return None with a reason below that (short-utterance embeddings are unreliable and
    will produce false mismatches on a genuine speaker).
 3. compare(live_emb, enrolled_emb) -> {cosine_similarity, verdict}.
    Thresholds MUST be configurable, not hardcoded. Defaults: >0.70 MATCH,
    0.50-0.70 INCONCLUSIVE, <0.50 MISMATCH. Add a comment citing Context §10.5 that these
    must be recalibrated on your own data and that channel mismatch shifts them sharply.
 4. Intra-call drift: keep a reference embedding from the first 5s of the call; compute
    cosine against each subsequent window; return drift = 1 - cosine and a rolling max.
    Genuine speakers drift < 0.10; real-time voice conversion often exceeds 0.15.
 5. CHANNEL MISMATCH — handle this explicitly, it is the #1 cause of false rejections:
    store enrolled embeddings PER CHANNEL PROFILE. If the live profile differs from the
    enrolled profile, either use the matching enrolment or return verdict INCONCLUSIVE with
    reason "CHANNEL_MISMATCH" rather than a misleading low score.
 6. enrol_speaker.py: CLI taking a WAV (or several) + a profileId, producing a 192-d embedding
    and POSTing it to the Java passport endpoint. Support --profile narrowband to enrol from a
    codec-degraded version of the same audio, so a CFO has both a wideband and a narrowband passport.
 7. Latency: embedding extraction must be < 40ms for a 2s window on CPU. If it exceeds that,
    compute it every 2nd or 4th window rather than every window, and interpolate. Measure it.

ACCEPTANCE:
  - Two clips of the SAME speaker score cosine > 0.70; two DIFFERENT speakers score < 0.50
  - The same speaker, one clean and one mulaw-degraded, demonstrates the channel-mismatch drop —
    document the measured drop, it is an honest and impressive slide
  - Embedding latency measured and logged at startup
```

---

## P4.5 — The anti-spoofing model
**Track:** 🟪 AI/model · **Est:** 6 h · **Deps:** P4.1

**The honest approach:** don't chase state-of-the-art. Build a solid, *calibrated*, codec-robust baseline and report its real numbers. Context §3 explains why a modest honest detector inside a good system beats a great detector in a bad one.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 3, 10.1, 15)

TRACK: AI / model

TASK: Build the learned anti-spoofing classifier with calibrated probability output and
codec-augmented training.

FILES TO CREATE:
  ml-engine/app/modules/antispoof.py
  ml-engine/training/dataset.py
  ml-engine/training/augment.py
  ml-engine/training/train_antispoof.py
  ml-engine/training/calibrate.py
  ml-engine/tests/test_antispoof.py
  ml-engine/models/README.md

REQUIREMENTS:
 1. TIERED APPROACH — implement tier 1 first and make it work end to end before attempting tier 2:
      TIER 1 (baseline, must work): LFCC features -> GMM or a small CNN/LCNN on the
              LFCC-delta-deltadelta stack. Trains in minutes on CPU. Gives a real number.
      TIER 2 (if time): a self-supervised front end (wav2vec2 / WavLM base) with a lightweight
              classification head, or an AASIST-style graph-attention back end.
    Put a clear comment at the top of antispoof.py stating which tier is currently active.

 2. dataset.py: loaders for ASVspoof 2019 LA (train/dev/eval), ASVspoof 2021 DF eval, and
    In-the-Wild. Expect the data under datasets/ (gitignored). Provide a --limit flag so
    someone can smoke-test the pipeline on 500 files before downloading 30GB.

 3. augment.py — THIS IS THE DIFFERENTIATOR. Codec-round-trip augmentation applied during training:
      - ffmpeg round trips: mulaw 8k, alaw 8k, amr_nb 12.2k, amr_nb 4.75k, opus 24k, opus 12k
      - additive noise at SNR sampled from 5-25 dB (babble, office, street)
      - packet-loss simulation: zero or repeat random 20-60ms segments at 0-5% loss
      - mild reverb convolution with generated RIRs
      - random gain, mild pitch shift (+/-2%)
    Apply with probability p per sample. Cache the ffmpeg round trips to disk once rather than
    re-running ffmpeg every epoch — otherwise training will be I/O bound and take all night.

 4. train_antispoof.py: trains, checkpoints, and writes a metrics JSON with EER + min t-DCF on
    each eval set. Train TWO models: one WITHOUT codec augmentation, one WITH. You need both to
    produce the before/after table in P12 — that table is your strongest technical artifact.

 5. calibrate.py: fit Platt scaling (sklearn LogisticRegression on the score) on the DEV set,
    SEPARATELY per channel profile. Save the (A, B) coefficients. Produce a reliability diagram
    PNG and report Expected Calibration Error.

 6. antispoof.py at inference: load the checkpoint + calibration, expose
    `score(audio, sr, profile) -> {"spoofProbability": float, "modelId": str,
    "confidence": float, "available": bool}`.
    spoofProbability is the CALIBRATED probability, not a raw logit — this matters, because the
    fusion engine treats it as a probability and a raw logit would silently break the weighting.
    confidence = 1 - normalised predictive entropy.

 7. Inference budget: < 50ms per 2s window on CPU. If Tier 2 exceeds this, quantise to ONNX int8
    (also gives you a genuine "edge-ready" claim) or fall back to Tier 1 for the live path and
    use Tier 2 only for the offline evaluation.

 8. models/README.md: document exactly which model is shipped, what it was trained on, its
    measured EER per condition, and its known failure modes. Do not ship a model you cannot
    describe. A judge asking "what is this trained on?" and getting a precise answer is worth
    a lot.

DO NOT: report accuracy on the training distribution as if it were general performance.
DO NOT: tune thresholds on the evaluation set.

ACCEPTANCE:
  - train_antispoof.py runs to completion on a --limit subset and writes metrics.json
  - The metrics table shows in-domain EER and In-the-Wild EER (expect the In-the-Wild number
    to be much worse — that is the honest, expected result per Context §3.1)
  - The calibrated model's reliability diagram exists as a PNG
  - Inference latency measured and within budget
```

---

## P4.6 — Wire real features into the fast path
**Track:** 🟦 Python · **Est:** 2 h · **Deps:** P4.1–P4.5

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 8.1)
FILES: @ml-engine/app/fast_path.py @ml-engine/app/scheduler.py
       @ml-engine/app/modules/

TRACK: Python ML

TASK: Replace every stub in fast_path.py with the real feature modules, enforce the latency
budget, and guarantee graceful degradation.

REQUIREMENTS:
 1. fast_path.extract(window, sr, profile, session_state) orchestrates spectral, phase, prosody,
    breath, channel, speaker, antispoof, watermark. Assemble into the FeatureFrame `voice`,
    `channel`, `prosody`, `speaker`, `watermark` blocks exactly per the frozen schema.
 2. EVERY module call is wrapped in try/except. A module that raises returns
    {"available": False, "reason": "<exception class>"} and the pipeline CONTINUES.
    One bad feature must never kill a live call. Log the exception once per session per module,
    not per frame (otherwise a persistent failure floods your logs during the demo).
 3. Enforce the 120ms budget: run independent modules concurrently where possible
    (asyncio.to_thread / a ThreadPoolExecutor — numpy releases the GIL for most operations).
    Measure per-module timing and include it in FeatureFrame.latencyMs as a breakdown.
    If total exceeds 120ms for 5 consecutive frames, log a WARNING and automatically drop the
    most expensive optional module (CQT first, then speaker embedding every-Nth).
 4. Add `GET /diagnostics/{sessionId}` returning per-module availability, last error,
    and rolling p50/p95 latency. You will use this constantly and it's a good demo artifact.
 5. Delete every `# STUB` comment and any remaining stub code path.

ACCEPTANCE:
  - `grep -rn "STUB" ml-engine/app/` returns nothing
  - Live stream: FeatureFrames carry real varying values for all families
  - Deliberately raise an exception inside prosody.extract -> the frame still arrives with
    prosody.available=false and everything else populated
  - p95 fast-path latency < 120ms, visible on /diagnostics
```

---

# PHASE 5 — Fusion & intervention

## P5.1 — The fusion engine
**Track:** 🟥 Java · **Est:** 4 h · **Deps:** P2.2, P1.1

**What it does:** Implements Context §9.1–§9.4 — availability renormalisation, dual-profile weights, asymmetric EMA, the corroboration gate, and emergency bypass. Your current engine has none of this.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 9.1, 9.2, 9.3, 9.4)
FILES: @backend/.../service/FusedRiskEngineService.java
       @backend/.../config/SentinelProperties.java

TRACK: Java backend

TASK: Rebuild the fusion engine with availability renormalisation, dual-profile weights,
asymmetric EMA, the corroboration gate, and emergency bypass.

FILES TO CREATE/MODIFY:
  MOVE+REWRITE  service/FusedRiskEngineService.java -> fusion/FusionEngineService.java
  CREATE  fusion/FamilyScore.java
  CREATE  fusion/FusionResult.java
  CREATE  fusion/EvidenceFamily.java        (enum with group: ACOUSTIC | CONTEXTUAL)
  CREATE  fusion/CorroborationGate.java
  CREATE  fusion/EmaSmoother.java
  CREATE  fusion/EmergencyTrigger.java
  CREATE  test/fusion/FusionEngineTest.java
  CREATE  test/fusion/CorroborationGateTest.java

REQUIREMENTS:
 1. EvidenceFamily enum: VOICE, CHANNEL, PROSODY (group ACOUSTIC);
    LINGUISTIC, TRANSACTION, RELATIONSHIP (group CONTEXTUAL). Each carries its base weight per
    profile and its corroboration threshold, all read from SentinelProperties — NO magic numbers
    in the class.

 2. Implement Context §9.1 EXACTLY:
        S(t) = SUM_{i in A} w_i * c_i * S_i   /   SUM_{i in A} w_i * c_i
    where A is the set of AVAILABLE families. A family with available=false is EXCLUDED from
    both numerator and denominator — it must NOT be treated as zero. Write a test that proves
    this: a frame with only linguistic available and S_linguistic=0.9 must yield S(t)=0.9,
    not 0.9 * 0.25.

 3. Confidence multipliers c_i:
      VOICE:      the model's own confidence field
      LINGUISTIC: staleness decay exp(-ageMs / tau), tau from config (default 3000ms)
      others:     1.0 when available
 4. Profile-dependent weights: use the wideband or narrowband weight map from
    SentinelProperties based on FeatureFrame.channelProfile. Assert at startup that each map
    sums to 1.0 +/- 0.001, and fail fast if not.

 5. EmaSmoother implementing Context §9.2 asymmetric EMA:
        lambda = lambdaUp (0.55) when rising, lambdaDown (0.88) when falling
        FREEZE (return previous value unchanged) when speechPresent == false
        lambda = 0 when an emergency trigger fires
    Per-session state. Unit-test: a step input from 0.1 to 0.9 must reach 0.8 in fewer windows
    than a step from 0.9 to 0.1 takes to reach 0.2.

 6. CorroborationGate implementing Context §9.3: returns satisfied=true only when at least one
    ACOUSTIC family AND at least one CONTEXTUAL family exceed their own thresholds.
    Return the list of qualifying families for the TelemetryFrame.corroboration block.

 7. EmergencyTrigger implementing Context §9.4 — the three named conditions. Return the
    matched condition's reason code. Emergency bypasses the corroboration gate AND sets lambda=0.

 8. FusionResult carries: instantaneous, smoothed, trend, state
    (SCORED | INSUFFICIENT_EVIDENCE | DEGRADED), per-family {score, weight, contribution,
    available}, corroboration detail, and emergency reason if any.
    state = INSUFFICIENT_EVIDENCE when cumulativeSpeechMs < minSpeechMsForScoring (default 3000).
    state = DEGRADED when fewer than 3 families are available.

 9. TESTS ARE THE POINT OF THIS STEP. Cover at minimum:
      - availability renormalisation (the single-family case above)
      - weights summing to 1 in both profiles
      - asymmetric EMA rise vs fall asymmetry
      - EMA frozen during silence
      - corroboration blocks escalation when only acoustic families fire
      - corroboration blocks escalation when only contextual families fire
      - each of the three emergency triggers individually
      - INSUFFICIENT_EVIDENCE below the speech threshold
      - a golden-vector test: a fixed FeatureFrame produces an exact expected score
        (protects the math from accidental regression later)

DO NOT: hardcode any weight, threshold, or lambda in Java source.

ACCEPTANCE:
  - All fusion tests green, including the golden-vector test
  - Changing a weight in application.yml changes the output with no code change
  - Setting weights that don't sum to 1.0 fails application startup
```

---

## P5.2 — Intervention FSM with hysteresis & dwell
**Track:** 🟥 Java · **Est:** 3 h · **Deps:** P5.1

**What it does:** Implements Context §9.5. Without dwell and hysteresis, a score hovering near a threshold makes the UI strobe between levels — which looks broken on stage and *is* broken in production.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 9.5)
FILES: @backend/.../service/InterventionLadderService.java

TRACK: Java backend

TASK: Turn the stateless threshold lookup into a proper finite state machine with hysteresis,
dwell times, manual override, and audited transitions.

FILES TO CREATE/MODIFY:
  REWRITE  intervention/InterventionLadderService.java
  CREATE   intervention/InterventionStateMachine.java
  CREATE   intervention/TransitionRule.java
  CREATE   intervention/InterventionDecision.java
  CREATE   intervention/OverrideRecord.java
  CREATE   test/intervention/InterventionStateMachineTest.java

REQUIREMENTS:
 1. Per-session state: currentLevel, levelEnteredAtMs, lastEvaluatedAtMs, overrideActive.
 2. Transition rules from Context §9.5, all read from SentinelProperties:
      UP:   requires smoothed >= upThreshold AND corroborationSatisfied
            AND (now - levelEnteredAtMs) >= dwellMs for the current level
      DOWN: requires smoothed <= downThreshold (which is strictly below the up threshold —
            this IS the hysteresis band) AND the dwell time for the current level has elapsed
    Escalation may skip levels (0.2 -> 0.95 goes straight toward L5 subject to the gates);
    de-escalation must be ONE LEVEL AT A TIME.
 3. L5_TERMINATE is TERMINAL. No automatic de-escalation, ever. Only an explicit analyst reset
    (an audited ANALYST_OVERRIDE event) can leave it. Encode this as an explicit rule with a
    comment explaining why: once a call is disconnected and an account frozen, a fluctuating
    score must never silently undo it.
 4. Emergency bypass (from FusionResult) jumps directly to L4, ignoring dwell, but still
    requires an analyst confirmation to reach L5.
 5. Manual override: an analyst can force a level up or down. Requires a reason string
    (>= 10 chars) and an analystId. Override pins the level for a configurable duration
    (default 120s) during which automatic transitions are suppressed but still LOGGED
    ("would have escalated to L4"). Every override appends an ANALYST_OVERRIDE audit block.
 6. EVERY transition appends a RISK_LEVEL_CHANGED audit block with {from, to, smoothedScore,
    corroboratingFamilies, trigger: AUTOMATIC | EMERGENCY | MANUAL}.
 7. InterventionDecision returns the new level, whether it changed, dwellRemainingMs,
    the actions to fire, and a human-readable rationale string.
 8. Tests — this is a state machine, so test it like one:
      - oscillating input around 0.55 produces AT MOST ONE transition in 10 seconds
        (this is the flicker regression test; name it that)
      - dwell enforced: an immediate second escalation is suppressed
      - de-escalation is one level at a time
      - L5 never auto-de-escalates
      - override suppresses automatic transitions but logs the suppressed intent
      - a full L1->L5 escalation sequence produces exactly the expected audit block sequence

ACCEPTANCE:
  - All FSM tests green, especially the flicker regression test
  - Manually driving the score up and down in the UI shows smooth, non-flickering level changes
  - Every level change appears in GET /api/v1/compliance/audit-chain/{id}
```

---

## P5.3 — Reason codes & explainability payload
**Track:** 🟥 Java · **Est:** 2 h · **Deps:** P5.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 8.2 "topReasons")

TRACK: Java backend

TASK: Generate human-readable reason codes so the analyst sees WHY, not just how much.

FILES TO CREATE:
  fusion/ReasonCode.java          (enum with code, severity, template)
  fusion/ReasonGenerator.java
  test/fusion/ReasonGeneratorTest.java

REQUIREMENTS:
 1. ReasonCode enum, each with a severity and a message template with placeholders:
      CLI_CLAIM_MISMATCH (CRITICAL), VOICEPRINT_FAIL (CRITICAL), SYNTHETIC_ARTIFACTS (HIGH),
      NO_BREATH (MEDIUM), OVERSMOOTH_PROSODY (MEDIUM), NO_ROOM_ACOUSTICS (HIGH),
      DOUBLE_COMPRESSION (MEDIUM), SECRECY_DEMAND (HIGH), URGENCY_PRESSURE (MEDIUM),
      AUTHORITY_INVOCATION (HIGH), POLICY_VIOLATION (HIGH), FIRST_CONTACT (MEDIUM),
      HIERARCHY_ANOMALY (MEDIUM), PRESENCE_CONFLICT (HIGH), CROSS_CHANNEL_PRECURSOR (HIGH),
      VOICE_DRIFT (MEDIUM), CHALLENGE_LATENCY_FAIL (CRITICAL), WATERMARK_DETECTED (INFO)
 2. ReasonGenerator inspects the FeatureFrame + context assessments and emits the applicable
    reasons, sorted by severity then by contribution to the score, capped at the top 5.
 3. Messages must be PLAIN ENGLISH and quantified. Not "prosody anomaly detected" but
    "No breath sounds in 24 seconds of continuous speech (human baseline: 8-20 per minute)".
    A bank manager and a judge must both understand it without asking a follow-up question.
 4. NO_BREATH must only fire once cumulativeSpeechMs >= 15000. Encode the guard in the generator,
    not in the UI.
 5. Each reason carries the contributing family so the UI can link a reason to a radar axis.

ACCEPTANCE:
  - A synthetic-audio session produces at least 3 distinct quantified reasons
  - A genuine-audio session produces zero reasons above MEDIUM severity
  - Every message is a complete grammatical sentence containing at least one number
```

---

## P5.4 — Wire fusion into the live pipeline
**Track:** 🟥 Java · **Est:** 2 h · **Deps:** P5.1, P5.2, P5.3, P3.2

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 8.2, 9)
FILES: @backend/.../ingest/FeatureFrameIngestService.java
       @backend/.../telemetry/TelemetryFrameBuilder.java

TRACK: Java backend

TASK: Connect FeatureFrame ingest -> fusion -> FSM -> reasons -> audit -> STOMP broadcast
into one coherent pipeline. Remove the placeholder scoring from P3.2.

REQUIREMENTS:
 1. The per-frame pipeline, in order:
      validate -> update session -> fuse -> evaluate FSM -> generate reasons ->
      append audit (async, ordered) -> build TelemetryFrame -> broadcast
 2. Total pipeline time must be < 15ms p95. Measure with a Micrometer timer. The audit append
    must NOT block the broadcast — queue it.
 3. Delete the placeholder weighted sum introduced in P3.2.
 4. Backpressure: if the STOMP outbound queue for a session is full, drop the OLDEST frame,
    never the newest. Stale risk information is worse than a skipped update.
 5. On any exception in the pipeline, still broadcast a TelemetryFrame with
    risk.state = DEGRADED and an error reason, then log. A silent dead dashboard during a
    demo is the worst failure mode; a visibly degraded one is recoverable.
 6. Add an end-to-end integration test: feed 20 synthetic FeatureFrames with a rising
    linguistic + transaction score and assert the session walks L1 -> L2 -> L3 -> L4 and
    that the audit chain for that session verifies as valid.

ACCEPTANCE:
  - Live mic -> gauge now driven by REAL features and REAL fusion math
  - The integration test proves the full escalation path plus a valid audit chain
  - p95 pipeline latency < 15ms on /actuator/metrics
  - *** TIER-0 COMPLETE. Tag this commit. ***
```

**Verify:** `git tag milestone-tier0` — you now have a defensible demo.

---

# PHASE 6 — Analyst console

> Runs in parallel with Phases 4–5 against the P3.2 telemetry stream. The frontend developer is never blocked as long as the contracts from P0.2 hold.

## P6.1 — Console shell & layout
**Track:** 🟩 React · **Est:** 3 h · **Deps:** P3.2

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 7.4)
        @frontend/src/types/contracts.ts
        @frontend/src/hooks/useTelemetrySocket.ts

TRACK: React frontend

TASK: Build the analyst console shell: navigation, layout grid, session control, connection
status, and the design system.

FILES TO CREATE:
  frontend/src/views/AnalystConsole.tsx      (rewrite)
  frontend/src/views/SeniorShield.tsx        (placeholder)
  frontend/src/views/CompliancePortal.tsx    (placeholder)
  frontend/src/views/RedTeamLab.tsx          (placeholder)
  frontend/src/components/layout/AppShell.tsx
  frontend/src/components/layout/StatusBar.tsx
  frontend/src/components/SessionControl.tsx
  frontend/src/components/ui/{Card,Badge,Panel,Stat,Toggle}.tsx
  frontend/src/theme.ts

REQUIREMENTS:
 1. Dark operations-centre aesthetic: near-black background (#0a0e14), elevated panels (#131922),
    a single accent, and the risk ramp (green/amber/orange/red) reserved EXCLUSIVELY for risk.
    Never use red for a non-risk UI element — colour must carry one meaning only, or the
    demo audience will misread the screen.
 2. Typography: a monospace face for all numbers and identifiers (JetBrains Mono / IBM Plex Mono),
    a clean sans for prose. Numbers must be tabular-figure aligned so the gauge doesn't jitter
    horizontally as digits change.
 3. AppShell: left rail switching Analyst / Senior Shield / Compliance / Red Team.
    StatusBar (bottom): STOMP connection state, ml-engine health (poll /health), active session
    count, current latency p95, and a clock. This bar is your live debugging surface AND it
    reassures the demo audience that real infrastructure is running.
 4. SessionControl: start/stop a session, pick a channel profile, pick a pre-seeded scenario,
    show the session ID and elapsed timer.
 5. Grid layout for the analyst view with named slots so later steps drop components in without
    re-laying-out:
        [ identity card ][ risk gauge ][ intervention bar ]
        [ spectrogram            ][ evidence panel      ]
        [ live transcript        ][ reasons list        ]
 6. Every panel handles three states explicitly: loading, empty ("waiting for audio"), and error.
    An empty dashboard with no explanation looks broken on stage.
 7. Responsive down to 1280x720 — that is often the projector resolution at a hackathon venue.
    Check it.

DO NOT: use localStorage or sessionStorage. Keep all state in React.

ACCEPTANCE:
  - All four views reachable; three are labelled placeholders
  - Status bar reflects real connection state (kill the backend, watch it go red)
  - Layout holds at 1280x720 with no horizontal scroll
```

---

## P6.2 — Risk gauge, identity card, reasons list
**Track:** 🟩 React · **Est:** 4 h · **Deps:** P6.1, P5.3

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 8.2, 12)

TRACK: React frontend

TASK: Build the three primary analyst widgets: the risk gauge, the caller identity card,
and the reasons list.

FILES TO CREATE:
  frontend/src/components/RiskGauge.tsx        (upgrade from P3.2)
  frontend/src/components/RiskTimeline.tsx
  frontend/src/components/IdentityCard.tsx
  frontend/src/components/ReasonsList.tsx

REQUIREMENTS:
 1. RiskGauge: SVG arc, smoothed score as the filled arc, instantaneous as a thin tick
    (making the EMA visible is a nice touch that judges notice and ask about — be ready to
    explain it). Colour from the ramp. Show the level name, the trend arrow, and a
    "INSUFFICIENT EVIDENCE" state that is visually distinct from a genuine low score —
    grey with a hatched pattern, not green. Conflating "we don't know" with "it's safe" is a
    real safety bug and a judge may probe it.
 2. RiskTimeline: a sparkline of the last 120 seconds with level bands shaded in the background
    and markers where the level changed. Judges love seeing the trajectory, and it makes the
    escalation legible in a way a single number cannot.
 3. IdentityCard — implement the full Context §12 output:
      - CLI + trunk class badge (INTERNAL_PBX green / REGISTERED_EXTERNAL amber /
        UNREGISTERED_SIP red / WITHHELD red)
      - Directory record for the CLI (or "No directory match")
      - Spoken claimed identity + role, shown as a SEPARATE field
      - A prominent MISMATCH banner when cliVsClaimMismatch is true
      - Voice Passport: enrolled?, cosine value, verdict badge
      - CRITICAL UX DETAIL: show IMPERSONATION_HUMAN and IMPERSONATION_SYNTHETIC as visually
        distinct verdicts. They mean different things and warrant different responses
        (see Context §12). Do not collapse them into one "FAILED" badge.
      - Presence conflict line when present
 4. ReasonsList: ordered by severity, each with a severity chip, the plain-English sentence,
    and the contributing family as a small tag. Clicking a reason highlights the corresponding
    axis in the evidence panel (P6.3) — wire that via a shared context or a callback prop.
 5. Animate value changes over ~300ms. At a 2 Hz update rate, un-animated numbers look like
    they are glitching.

ACCEPTANCE:
  - Speaking into the mic moves the gauge and populates reasons in real time
  - Forcing cliVsClaimMismatch via a seeded scenario shows the mismatch banner
  - INSUFFICIENT_EVIDENCE renders visually distinct from a low score (verify by starting a
    session and watching the first 3 seconds)
```

---

## P6.3 — Evidence panel & spectrogram
**Track:** 🟩 React · **Est:** 4 h · **Deps:** P6.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 8.2, 9.1, 10.4)

TRACK: React frontend

TASK: Build the explainability panel (radar + waterfall) and the live spectrogram.

FILES TO CREATE:
  frontend/src/components/EvidencePanel.tsx
  frontend/src/components/FamilyRadar.tsx
  frontend/src/components/ContributionWaterfall.tsx
  frontend/src/components/SpectrogramCanvas.tsx
  frontend/src/audio/spectrogram.ts

REQUIREMENTS:
 1. FamilyRadar: a 6-axis radar (voice, channel, prosody, linguistic, transaction, relationship).
    Two overlaid polygons: the current frame and a "benign baseline" reference. An unavailable
    family renders as a dashed axis with a slash, NOT as a zero point — a zero would mislead the
    viewer into thinking we measured it and found nothing.
 2. ContributionWaterfall: shows how each family's weight x score builds the total. Bars ordered
    by contribution. This is the single clearest explainability artifact you will produce —
    give it room and make it legible from the back of a room.
 3. Show the ACTIVE WEIGHT PROFILE as a label ("Narrowband profile — acoustic weight reduced").
    When the profile switches mid-call, animate the weight change. This makes Context §10.4's
    dual-profile design visible rather than just claimed, and it is a great thing to point at
    during the pitch.
 4. SpectrogramCanvas: HTML5 Canvas, scrolling waterfall, computed IN THE BROWSER from the same
    audio the capture pipeline produces (do not send spectrogram data over the wire — it is large
    and unnecessary). Use a Web Worker + an FFT so the main thread stays responsive.
      - draw an annotated horizontal line at 4kHz and, for wideband, at 8kHz
      - for a narrowband session, grey out the region above 4kHz with the label
        "No signal above 4 kHz — PSTN narrowband". That single visual makes the whole
        dual-profile argument self-evident to anyone looking at the screen.
 5. Performance: spectrogram at >= 20 fps without dropping the telemetry render. Profile it.

ACCEPTANCE:
  - Radar and waterfall update live and sum visibly to the gauge value
  - Switching from a WebRTC session to a narrowband session visibly changes the weight labels
    and greys the >4kHz spectrogram region
  - Spectrogram runs at >= 20 fps with the rest of the console responsive
```

---

## P6.4 — Intervention bar & the locking approve button
**Track:** 🟩 React + 🟥 Java · **Est:** 3 h · **Deps:** P5.2, P6.1

**This component is the demo.** The moment the approve button greys out mid-click is your highest-value three seconds.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 9.5, 11.6, 20)

TRACK: React frontend + Java backend

TASK: Build the intervention ladder UI and the mock banking transaction panel whose approve
button the system actually locks.

FILES TO CREATE:
  frontend/src/components/InterventionBar.tsx
  frontend/src/components/TransactionPanel.tsx
  frontend/src/components/OverrideDialog.tsx
  frontend/src/components/SupervisorAlert.tsx
  backend/.../controller/InterventionController.java
  backend/.../actuation/TransactionLockService.java

REQUIREMENTS:
 1. InterventionBar: five stages rendered as a horizontal ladder. The current stage is
    highlighted; passed stages are marked; the dwell countdown is shown when a transition is
    pending. Each stage lists the actions it fires.
 2. TransactionPanel: a realistic mock banking form — beneficiary, IFSC, amount (₹50,00,000),
    a purpose field, and an "Approve Transfer" button.
      - at L1/L2 the button is enabled
      - at L3+ the button is DISABLED, greyed, with an overlay: "Locked by SentinelVoice —
        step-up authentication required"
      - the lock state is driven by the TelemetryFrame, NOT by local component state.
        The server is the authority. A judge asking "is that button really locked by the
        backend or is it a CSS class?" must get the right answer — and if you also expose
        POST /api/v1/transaction/approve returning 423 LOCKED, you can prove it with curl on stage.
 3. TransactionLockService (Java): tracks lock state per session, exposes
    POST /api/v1/transaction/{sessionId}/approve which returns 423 LOCKED with the reason when
    the level is >= L3. Appends an audit block on every approve attempt, successful or blocked.
    This makes the lock server-enforced rather than cosmetic.
 4. L3 additionally shows a mock OOB MFA card: "Push notification sent to Rajesh Kumar's
    registered device +91-XXXXX-43210" with a pending/approved/denied state and a countdown.
 5. L4 shows SupervisorAlert: a slide-in panel with the risk summary, top reasons, and
    Accept / Release buttons. L5 shows a full-screen red terminal state.
 6. OverrideDialog: analyst raises or lowers the level. REQUIRES a reason (>= 10 chars) and
    shows a warning that the override is permanently recorded in the audit ledger. Posts to
    POST /api/v1/intervention/{sessionId}/override.
 7. Transitions animate. An abrupt jump is easy to miss on a projector; a 400ms animated
    escalation with a subtle flash is not.

ACCEPTANCE:
  - Driving a session past 0.55 disables the approve button within one telemetry frame
  - `curl -X POST localhost:8080/api/v1/transaction/{sid}/approve` returns 423 while locked
  - An override with a blank reason is rejected by both UI and API
  - Every override appears in the audit chain
```

---

# PHASE 7 — Call integration (the differentiator)

> **Highest value-per-hour in the project.** Everything before this is a dashboard. This makes it telephony. Budget two full days and protect them.

## P7.1 — Asterisk in Docker + two softphones
**Track:** 🟨 DevOps/Telephony · **Est:** 4 h · **Deps:** P0.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 11.3)

TRACK: DevOps / Telephony

TASK: Stand up a local Asterisk PBX in Docker with two SIP endpoints, so two softphones can
place a real call to each other entirely offline.

FILES TO CREATE:
  gateway/asterisk/Dockerfile
  gateway/asterisk/config/pjsip.conf
  gateway/asterisk/config/extensions.conf
  gateway/asterisk/config/ari.conf
  gateway/asterisk/config/http.conf
  gateway/asterisk/config/modules.conf
  gateway/asterisk/config/logger.conf
  gateway/asterisk/README.md
  MODIFY docker-compose.yml
  scripts/test-sip-call.sh

REQUIREMENTS:
 1. Asterisk 20 LTS or 21. network_mode: host (SIP + RTP port ranges through Docker NAT is a
    time sink you do not need; host networking avoids it entirely).
 2. pjsip.conf: a UDP transport on 5060 and two endpoints, `caller` (ext 1001) and
    `agent` (ext 1002), each with auth and an aor, allow=ulaw,alaw,slin16.
    Use obvious demo passwords and say so in the README — this is a lab, not production,
    and pretending otherwise is worse than labelling it.
 3. extensions.conf: a [sentinel] context where dialling 1002 rings the agent. Leave a clearly
    marked TODO comment where P7.2 inserts the AudioSocket tap so the next step has an obvious seam.
 4. ari.conf + http.conf: enable ARI on 8088 with a user `sentinel`. This is what P7.3 uses to
    hold and terminate calls. Bind to 127.0.0.1.
 5. logger.conf: verbose console logging. You will need it — SIP debugging without logs is misery.
 6. README.md: exact softphone setup steps for Zoiper and Linphone (server address, port,
    username, password, transport), a troubleshooting section covering the three failures you
    WILL hit (registration fails, one-way audio, NAT), and the `asterisk -rvvv` CLI commands
    for `pjsip show endpoints` and `core show channels`.
 7. test-sip-call.sh: uses `sipp` or the Asterisk CLI to verify the PBX answers, so you can
    confirm the PBX works before blaming your bridge code.

ACCEPTANCE:
  - `docker compose up asterisk` starts cleanly
  - Two softphones on the same laptop/LAN register successfully
    (`asterisk -rx "pjsip show endpoints"` shows both Avail)
  - Calling 1002 from the caller softphone rings and connects with two-way audio
  - ARI reachable: `curl -u sentinel:xxx http://localhost:8088/ari/asterisk/info` returns JSON
```

---

## P7.2 — AudioSocket bridge into the inference plane
**Track:** 🟨 + 🟦 Python · **Est:** 4 h · **Deps:** P7.1, P2.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 11.3)
FILES: @ml-engine/app/normaliser.py @ml-engine/app/session.py

TRACK: Telephony / Python

TASK: Implement the Asterisk AudioSocket bridge so a real SIP call's media flows into the
inference plane.

FILES TO CREATE:
  gateway/asterisk_bridge.py
  gateway/protocol/audiosocket.py
  gateway/tests/test_audiosocket.py
  MODIFY gateway/asterisk/config/extensions.conf

REQUIREMENTS:
 1. audiosocket.py: the wire protocol. Frames are [type:1][length:2 big-endian][payload:length].
      0x00 TERMINATE | 0x01 UUID (16 raw bytes) | 0x10 AUDIO (SLIN16, 8kHz mono) | 0xff ERROR
    Implement an async frame reader that handles partial reads correctly (readexactly, not read)
    and a writer for sending audio back if you ever want to inject a whisper warning.
    Unit-test the parser against hand-constructed byte sequences including a split-across-reads case.
 2. asterisk_bridge.py: an asyncio TCP server on 9092.
      - on connect, wait for the UUID frame -> that is the Asterisk channel UNIQUEID
      - call POST /session/{sid}/open on the ml-engine with
        channelProfile=PSTN_NARROWBAND, and record the mapping uuid -> sessionId
      - for each AUDIO frame: normalise(payload, "slin16", 8000, 1) -> write to the ring buffer
      - on TERMINATE or socket close: POST /session/{sid}/close (which zeroises the buffer)
      - handle a client that dies without TERMINATE (the common case) via a read timeout
 3. extensions.conf: replace the TODO from P7.1 with the tap. Use a pre-dial handler or a
    Local channel so the tap does not break the audio path to the agent:
        exten => 1002,1,Answer()
         same => n,Set(SV_SESSION=${UNIQUEID})
         same => n,Dial(PJSIP/agent,60,b(sentinel-tap^s^1))
        [sentinel-tap]
        exten => s,1,AudioSocket(${SV_SESSION},127.0.0.1:9092)
         same => n,Return()
    Verify the call still has clean two-way audio WITH the tap active — a tap that degrades
    the call is worse than no tap. Test this explicitly.
 4. Resilience: if the ml-engine is down, the bridge must accept and DISCARD audio rather than
    tearing down the call. A monitoring system must never be able to drop a bank's calls.
    Log loudly, expose the degraded state, but let the call through. Put a comment saying so —
    "fail open, not closed" is a design decision a judge may well ask about.
 5. Metrics: frames received, bytes, sessions active, drops. Log a summary every 10 seconds.

ACCEPTANCE:
  - Placing a softphone call to 1002 creates a session in ml-engine with PSTN_NARROWBAND
  - The risk gauge in the React console moves in response to speech on a REAL SIP CALL
  - Call audio quality is unaffected by the tap (verify by ear, both directions)
  - Killing the ml-engine mid-call does not drop the call
  - *** This is the moment the project stops being a dashboard. Record a video. ***
```

---

## P7.3 — ARI actuation: hold, whisper, terminate
**Track:** 🟥 Java + 🟨 · **Est:** 5 h · **Deps:** P7.2, P5.2

**This is the "intervene before the money moves" claim made literal.** Without it the intervention ladder is a coloured banner; with it, the call actually goes on hold on stage.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 11.6, 9.5)

TRACK: Java backend + Telephony

TASK: Implement real call actuation through the Asterisk REST Interface so intervention levels
take physical effect on a live call.

FILES TO CREATE:
  backend/.../actuation/ActuationService.java
  backend/.../actuation/CallControlPort.java          (interface)
  backend/.../actuation/AsteriskAriAdapter.java
  backend/.../actuation/WebRtcAdapter.java
  backend/.../actuation/NoopAdapter.java
  backend/.../actuation/ActuationAction.java          (enum)
  backend/.../actuation/OobMfaService.java
  backend/.../actuation/CoreBankingWebhookService.java
  backend/.../config/ActuationConfig.java
  gateway/asterisk/sounds/sentinel-hold.wav
  gateway/asterisk/sounds/sentinel-whisper-warning.wav
  backend/test/.../ActuationServiceTest.java

REQUIREMENTS:
 1. CallControlPort interface — this abstraction is what makes the architecture credible:
        hold(sessionId) / unhold(sessionId)
        whisperToAgent(sessionId, soundId)
        bridgeSupervisor(sessionId, supervisorEndpoint)
        announce(sessionId, soundId)
        terminate(sessionId, reason)
        capabilities() -> Set<ActuationAction>
    Three implementations: AsteriskAriAdapter (full), WebRtcAdapter (partial — signals the
    browser over STOMP), NoopAdapter (logs only; used in tests and when no telephony is present).
    Selected by config `sentinelvoice.actuation.adapter`.
 2. AsteriskAriAdapter: REST client against http://localhost:8088/ari with basic auth.
        hold       -> POST /channels/{channelId}/hold
        unhold     -> DELETE /channels/{channelId}/hold
        announce   -> POST /channels/{channelId}/play?media=sound:sentinel-hold
        whisper    -> create a Snoop channel (POST /channels/{id}/snoop with whisper direction)
                      and play the warning to the AGENT ONLY — the caller must not hear it
        terminate  -> DELETE /channels/{channelId}
    Maintain a sessionId -> channelId map populated by the AudioSocket bridge (the AudioSocket
    UUID IS the Asterisk channel UNIQUEID — use it).
    Timeouts: 2s connect, 3s read. A hung ARI call must never stall the fusion pipeline —
    run actuation on a separate executor.
 3. ActuationService maps intervention levels to actions:
        L2 -> UI_BANNER
        L3 -> TXN_APPROVE_LOCKED, OOB_MFA_SENT
        L4 -> CALL_HELD, SUPERVISOR_BRIDGED, TXN_APPROVE_LOCKED
        L5 -> CALL_TERMINATED, BENEFICIARY_FROZEN, DOSSIER_GENERATED
    IDEMPOTENCY IS ESSENTIAL: firing hold twice must not error, and a level re-entered after
    a brief de-escalation must not re-fire actions already active. Track fired actions per
    session and diff on transition.
 4. Every action appends an INTERVENTION_ACTION_FIRED audit block with the action, the adapter,
    the result (success/failure/unsupported), and the latency.
 5. OobMfaService: mock push/SMS to the genuine executive's registered device. Generates a
    6-digit code, exposes POST /api/v1/mfa/{sessionId}/respond so you can approve or deny it
    live in the demo. Times out after 90s -> treated as a denial.
 6. CoreBankingWebhookService: POSTs a freeze instruction to a configurable URL. Ship a tiny
    mock CBS endpoint inside the backend at /mock-cbs/freeze that records calls, so the demo
    works with zero external dependencies. Label it clearly as a mock in the code and on screen.
 7. Sound files: generate sentinel-hold.wav (a neutral hold tone or standard message) and
    sentinel-whisper-warning.wav ("Caution: this caller's identity could not be verified") with
    any TTS, at 8kHz mono for Asterisk. Commit them.
 8. GRACEFUL DEGRADATION: if the configured adapter does not support an action, log it,
    record it in the audit as UNSUPPORTED, and continue with the actions it does support.
    Never throw out of the intervention path.

ACCEPTANCE:
  - On a live SIP call, driving the risk past 0.75 audibly places the call on hold
  - Driving past 0.90 with analyst confirmation disconnects the call
  - The whisper warning is heard by the agent softphone ONLY, not the caller — verify with two
    headsets, this is easy to get wrong
  - Every action appears in the audit chain with its latency
  - With adapter=noop, everything still runs and logs, nothing crashes
  - *** THE CORE DEMO BEAT IS NOW REAL. Rehearse it. ***
```

---

## P7.4 — PSTN path via Twilio / Exotel (optional)
**Track:** 🟨 · **Est:** 4 h · **Deps:** P7.2 · **Tier 2**

> Start the account/number provisioning on **Day 1** if you want this — Twilio Indian numbers need a regulatory bundle and can take days. Exotel is the better Indian story.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 11.4)
FILES: @gateway/asterisk_bridge.py (for the ingest pattern)

TRACK: Telephony / DevOps

TASK: Add a real PSTN path via a provider-agnostic media-streaming bridge with Twilio and
Exotel adapters.

FILES TO CREATE:
  gateway/pstn_bridge.py
  gateway/providers/base.py           (ProviderAdapter ABC)
  gateway/providers/twilio.py
  gateway/providers/exotel.py
  gateway/twiml/stream.xml
  gateway/README-PSTN.md

REQUIREMENTS:
 1. ProviderAdapter ABC: parse_frame(raw) -> (pcm_bytes, encoding, rate),
    extract_session_id(handshake), build_hold_instruction(), build_terminate_instruction().
    Both providers implement it. The bridge code is provider-agnostic — this is what lets you
    say "provider-agnostic, adapters for Twilio and Exotel" honestly in the pitch.
 2. Twilio adapter: WebSocket receiving JSON events (connected / start / media / stop).
    media.payload is base64 mu-law 8kHz in 20ms frames. Decode with audioop.ulaw2lin(p, 2),
    accumulate 25 frames -> 500ms -> normalise -> ring buffer.
    Actuation via the REST API: calls(sid).update(twiml=...) for hold/announce,
    update(status="completed") for terminate.
 3. Exotel adapter: same shape against Exotel's voice-streaming WebSocket envelope.
 4. stream.xml: the TwiML that starts the fork and dials the destination.
 5. README-PSTN.md must cover, because these will each cost you an hour otherwise:
      - ngrok setup and why you want a RESERVED domain (the URL changing mid-demo is fatal)
      - Twilio Indian number regulatory bundle requirements and lead time
      - trial-account call preamble and how it affects the demo script
      - the exact webhook URLs to configure in the console
      - a pre-demo checklist
 6. FALLBACK PLAN, written down: if venue internet fails, switch
    `sentinelvoice.actuation.adapter=asterisk` and run the Asterisk demo instead. Both paths
    must be tested and both must be in the runbook. Record a video of the PSTN demo as a
    second fallback.

ACCEPTANCE:
  - Calling the provisioned number streams audio into ml-engine and moves the gauge
  - Risk past 0.75 injects a hold announcement on the real call
  - Risk past 0.90 disconnects the real call
  - Switching adapters via config requires no code change
```

---

# PHASE 8 — Identity, passport & liveness

## P8.1 — Directory & identity resolution
**Track:** 🟥 Java · **Est:** 4 h · **Deps:** P1.2

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 12)

TRACK: Java backend

TASK: Implement the five-stage identity resolution pipeline: CLI -> directory -> spoken claim
-> voiceprint -> context.

FILES TO CREATE:
  identity/IdentityResolutionService.java
  identity/DirectoryService.java
  identity/TrunkClassifier.java
  identity/IdentityVerdict.java
  identity/model/DirectoryRecord.java       (JPA entity)
  identity/model/IdentityAssessment.java
  repository/DirectoryRecordRepository.java
  resources/data.sql                        (seeded directory)
  test/identity/IdentityResolutionTest.java

REQUIREMENTS:
 1. DirectoryRecord entity: employeeId, name, role, department, primaryCli, extension,
    verbalAuthorityLimitInr, permittedChannels (CSV), presenceStatus, calendarLocation,
    managerEmployeeId, hierarchyLevel, passportEnrolled.
 2. data.sql seeds a realistic bank directory (~15 people) including the demo cast:
    Rajesh Kumar (CFO, hierarchyLevel 2, verbalAuthorityLimit 0),
    Arvind Mehta (CEO, level 1, verbalAuthorityLimit 0),
    Sunita Rao (Branch Teller, level 6, limit 100000),
    plus a supervisor and a few others. Every CXO gets verbalAuthorityLimitInr = 0 — that is the
    corporate policy the attack violates, and it is what turns a suspicion into a policy breach.
 3. TrunkClassifier: maps a CLI / SIP header to
    INTERNAL_PBX | REGISTERED_EXTERNAL | UNREGISTERED_SIP | WITHHELD.
    Rules configurable: an internal extension pattern, a set of known external numbers,
    everything else unregistered.
 4. IdentityResolutionService.resolve(session, featureFrame) -> IdentityAssessment:
      a. look up the CLI in the directory
      b. read claimedIdentity/claimedRole from featureFrame.linguistic
      c. THE CORE CHECK: if a claimed role exists and either (no directory match for the CLI)
         or (directory role for the CLI != claimed role), set cliVsClaimMismatch = true
         and emit CLI_CLAIM_MISMATCH at CRITICAL
      d. if the CLAIMED identity is enrolled, compare the live speaker embedding to that
         person's passport
      e. presence conflict: directory calendarLocation vs trunk class/region
    Return the full Context §8.2 `identity` block plus an identityRiskScore.
 5. Distinguish the verdicts precisely (Context §12):
      cosine low + spoof low   -> IMPERSONATION_HUMAN
      cosine low + spoof high  -> IMPERSONATION_SYNTHETIC
      cosine high + spoof high -> IMPERSONATION_SYNTHETIC (a clone of the right person)
      cosine high + spoof low  -> VERIFIED
      insufficient data / channel mismatch -> INCONCLUSIVE
    Write a test table covering all five.
 6. Feed identityRiskScore into the fusion engine as part of the RELATIONSHIP family
    (or add it as a weighted sub-component — document which you chose and why).

ACCEPTANCE:
  - Seeded directory loads on startup
  - A session with CLI = unregistered SIP + claimed role "CFO" produces cliVsClaimMismatch
    and a CRITICAL reason
  - All five verdict combinations covered by tests
```

---

## P8.2 — Voice Passport with consent & erasure
**Track:** 🟥 Java + 🟦 Python · **Est:** 4 h · **Deps:** P8.1, P4.4

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 13.2, 13.3, 12)

TRACK: Java backend + Python ML

TASK: Implement consent-gated voiceprint enrolment, per-profile storage, verification, and
DPDP-compliant erasure with a cryptographic tombstone.

FILES TO CREATE/MODIFY:
  REWRITE  passport/VoicePassportService.java
  CREATE   passport/model/VoicePassport.java     (JPA entity)
  CREATE   passport/model/ConsentRecord.java     (JPA entity)
  CREATE   passport/EmbeddingCodec.java
  MODIFY   controller/VoicePassportController.java
  CREATE   test/passport/VoicePassportTest.java
  CREATE   ml-engine/app/routes/enrol.py

REQUIREMENTS:
 1. VoicePassport entity: profileId, employeeId, channelProfile, embedding (192 floats stored
    as a base64 byte[] via EmbeddingCodec), embeddingModelId, enrolledAt, lastVerifiedAt,
    active. UNIQUE on (employeeId, channelProfile) — an employee has one passport PER CHANNEL
    PROFILE, which is the fix for the channel-mismatch problem in Context §12.
 2. ConsentRecord entity: employeeId, purpose, noticeVersion, grantedAt, grantedBy, withdrawnAt,
    method. Enrolment is REJECTED with 403 if no active consent exists. Write a test proving it.
 3. Enrolment flow: POST /api/v1/passport/enrol with {employeeId, channelProfile, audioRef}.
    Java calls ml-engine POST /enrol which returns the 192-d embedding. Java stores the
    EMBEDDING ONLY. The audio is never stored, never proxied through Java, and the ml-engine
    zeroises its buffer immediately. Append a PASSPORT_ENROLLED audit block.
 4. Erasure: DELETE /api/v1/passport/{profileId} must
      a. hard-delete the embedding row (not a soft delete — DPDP §12 means erasure)
      b. append a PASSPORT_ERASED audit block containing a SHA-256 of the erased embedding
         (a tombstone proving WHAT was deleted without retaining it — explain this in a code
         comment, it is a genuinely elegant point for the compliance slide)
      c. return a deletion certificate {profileId, erasedAt, tombstoneHash, auditBlockIndex}
 5. GET /api/v1/passport/{profileId} returns metadata and the processing log, NEVER the vector.
    DPDP §11 is a right to information about processing, not a right to the biometric template
    itself — returning the vector would be a security regression. Comment this.
 6. Verification helper: verify(employeeId, liveEmbedding, channelProfile) -> {cosine, verdict}.
    If no passport exists for that channel profile, return INCONCLUSIVE with reason
    CHANNEL_MISMATCH rather than falling back to the other profile's passport and producing a
    misleadingly low score.
 7. ml-engine /enrol endpoint: accepts a WAV upload (or a path from the seeded fixtures),
    computes the ECAPA embedding, and ALSO computes a codec-degraded variant so wideband and
    narrowband passports can be created from one recording. Never writes the upload to disk.

ACCEPTANCE:
  - Enrolling without consent returns 403
  - Enrolling with consent stores wideband and narrowband passports
  - Verifying the same speaker returns cosine > 0.70 on the matching profile
  - Deleting returns a certificate; the row is gone; the tombstone is in the audit chain;
    verify() on the chain still returns valid
```

---

## P8.3 — Relationship graph & transaction policy
**Track:** 🟥 Java · **Est:** 3 h · **Deps:** P8.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 10.6, 12)

TRACK: Java backend

TASK: Implement the relationship-graph and transaction-policy evidence families with real logic
replacing the P1.4 placeholders.

FILES TO CREATE:
  context/RelationshipGraphService.java      (rewrite)
  context/TransactionPolicyService.java
  context/model/InteractionEdge.java         (JPA entity)
  context/model/TransactionAssessment.java
  context/model/RelationshipAssessment.java
  repository/InteractionEdgeRepository.java
  MODIFY resources/data.sql
  test/context/TransactionPolicyTest.java

REQUIREMENTS:
 1. InteractionEdge: callerEmployeeId, calleeEmployeeId, interactionCount, firstSeenAt,
    lastSeenAt, typicalHourOfDay, typicalDurationSec. Seed a realistic graph in data.sql:
    the CFO talks to the Treasury Head weekly; the CFO has NEVER called Sunita Rao the teller
    (no edge at all). That absent edge is the anomaly the demo exploits.
 2. RelationshipGraphService returns RelationshipAssessment:
      interactionCount365d, firstContact (bool), hierarchyDistance (|levelA - levelB|),
      offHoursCall (bool), durationAnomaly, score, reasonCodes.
    Scoring must be a documented, configurable formula — not a chain of if-statements.
    Put the coefficients in SentinelProperties.
 3. TransactionPolicyService takes the extracted `ask` from featureFrame.linguistic plus the
    directory record for the CLAIMED identity and returns TransactionAssessment:
      - amount vs verbalAuthorityLimitInr for the claimed role -> POLICY_VIOLATION
      - channel permitted for this transaction type?
      - beneficiary novelty (never-seen account -> elevated)
      - velocity (Nth high-value request today)
      - urgency-vs-amount interaction: a large amount AND an immediate deadline is
        disproportionately suspicious — model it as a multiplicative term, not additive,
        and comment why
      score + reasonCodes
 4. THE KEY INSIGHT to encode explicitly and comment: an ask of ₹50,00,000 by voice from a role
    whose verbal authority limit is ₹0 is a POLICY VIOLATION, not a probabilistic suspicion.
    It should push the transaction family near 1.0 deterministically. This is why the contextual
    families are so much more reliable than the acoustic ones — policy is a fact, not an inference.
 5. Wire both into the fusion engine as the TRANSACTION and RELATIONSHIP families.

ACCEPTANCE:
  - Scenario 2 (CFO -> teller, ₹50L) yields relationship score > 0.8 and transaction score > 0.9
  - Scenario 1 (CFO -> Treasury Head, scheduled payment) yields both < 0.2
  - The policy-violation path is deterministic and unit-tested
```

---

## P8.4 — Challenge-response liveness (server-authoritative)
**Track:** 🟥 Java + 🟦 Python · **Est:** 4 h · **Deps:** P8.1, P10.1

**Fixes defect #6.** Today the challenge is client-stateful and therefore spoofable, which makes it a security theatre control rather than a security control.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 7.3, 18.1 defect #6)
FILES: @backend/.../service/ChallengeResponseService.java

TRACK: Java backend + Python ML

TASK: Rebuild challenge-response liveness with server-side state, server-measured latency,
and ASR-verified content.

FILES TO CREATE/MODIFY:
  REWRITE  challenge/ChallengeService.java
  CREATE   challenge/model/ActiveChallenge.java
  CREATE   challenge/ChallengePhraseGenerator.java
  CREATE   challenge/ChallengeEvaluator.java
  MODIFY   controller/ChallengeResponseController.java
  CREATE   frontend/src/components/ChallengePanel.tsx
  CREATE   test/challenge/ChallengeServiceTest.java

REQUIREMENTS:
 1. ALL challenge state lives SERVER-SIDE in a ConcurrentHashMap<sessionId, ActiveChallenge>
    {nonce, phrase, issuedAtMonotonicNanos, expiresAt, status}. The client receives ONLY the
    phrase text and a nonce. It NEVER sends back issuedAt — the server measures elapsed time
    itself using System.nanoTime(). Add a comment explaining that the previous design let the
    client dictate the measured latency, which made the control meaningless.
 2. ChallengePhraseGenerator: SecureRandom, phonetically diverse, unpredictable.
    Format: [colour/adjective] [noun] [2-digit number], e.g. "Amber Falcon 72".
    Word lists of >= 40 entries each so the space is large (40*40*90 = 144,000). Never repeat
    within a session. Support Hindi and Tamil phrase sets for multilingual demos.
 3. Latency measurement — this is the subtle part, get it right:
    The clock starts when the phrase is DISPLAYED (the client confirms render via
    POST /challenge/{nonce}/displayed), and stops at the FIRST SPEECH ONSET after that,
    detected by the ml-engine VAD, not at the end of the utterance.
    ml-engine pushes a CHALLENGE_SPEECH_ONSET event with its own monotonic timestamp.
    Document the clock-skew assumption (both processes on one host) in a comment.
 4. ChallengeEvaluator combines three signals, not one:
      a. LATENCY: human < 1.8s, suspicious > 3.5s (configurable). A real-time TTS pipeline
         must transcribe the prompt, generate, and play it back.
      b. CONTENT: the ml-engine ASR transcript of the response vs the expected phrase,
         using normalised token overlap with fuzzy matching (a genuine human may mispronounce
         "Falcon" or add "uh, Amber Falcon seven two" — do NOT require an exact string match,
         you will fail every real human)
      c. ACOUSTIC CONSISTENCY: the speaker embedding of the response vs the embedding of the
         preceding call audio. A pre-rendered clip spliced in will differ.
    Verdict: PASS | FAIL_LATENCY | FAIL_CONTENT | FAIL_ACOUSTIC | TIMEOUT.
 5. Expiry: 15s, then TIMEOUT (treated as a failure). One active challenge per session.
 6. Audit: CHALLENGE_ISSUED and CHALLENGE_RESULT blocks with the full evaluation detail.
    A FAIL feeds the fusion engine as a hard CHALLENGE_LATENCY_FAIL / CHALLENGE_CONTENT_FAIL
    reason at CRITICAL and triggers the emergency path.
 7. ChallengePanel.tsx: an "Issue Challenge" button, a large display of the phrase, a live
    countdown, a latency bar with the human/bot threshold marked, and the verdict with a
    breakdown of all three signals. Make the latency bar the visual centrepiece — the gap
    between 1.8s and 3.8s is the whole point and it must be obvious from the back of the room.

ACCEPTANCE:
  - A human responding normally passes on all three signals
  - Delaying the response past 3.5s produces FAIL_LATENCY
  - Saying a different phrase produces FAIL_CONTENT
  - A client attempting to forge issuedAt has NO EFFECT on the measured latency — test this
    explicitly, it is the whole point of the rewrite
  - Both events land in the audit chain
```

---

# PHASE 9 — Forensics & compliance

## P9.1 — Forensic dossier with PDF export
**Track:** 🟥 Java · **Est:** 4 h · **Deps:** P1.3, P5.4

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 7.3, 13)
FILES: @backend/.../service/ForensicDossierService.java

TRACK: Java backend

TASK: Replace the hardcoded dossier map with a real evidence package and a court-ready PDF.

FILES TO CREATE/MODIFY:
  REWRITE  forensics/ForensicDossierService.java
  CREATE   forensics/DossierPdfRenderer.java
  CREATE   forensics/model/ForensicDossier.java   (rewrite entity)
  CREATE   forensics/model/EvidenceItem.java
  MODIFY   controller/ForensicReportController.java
  CREATE   test/forensics/DossierTest.java

REQUIREMENTS:
 1. Add openpdf or Apache PDFBox to pom.xml (avoid iText 7 — AGPL licensing is a real issue if
    anyone asks about commercialisation, and a judge might).
 2. The dossier assembles, from data already retained (never from audio):
      - Case header: session ID, start/end, duration, channel profile, adapter used
      - Identity section: the full Context §8.2 identity block, with the mismatch analysis
      - Risk timeline: a rendered chart of smoothed risk vs time with level-change markers
        (render server-side to PNG with JFreeChart or draw it directly into the PDF)
      - Evidence table: every reason code that fired, when, its severity, its measured value
        and the human baseline it deviated from
      - Intervention log: every level change and every action fired, with timestamps and latency
      - Analyst actions: overrides with reasons and analyst IDs
      - Challenge results if any
      - Audit chain section: block count, genesis hash, final hash, the verification result,
        and a table of the first and last five blocks
      - Methodology appendix: which models produced which scores, their versions, and their
        measured EER under the relevant channel profile. THIS SECTION IS WHAT MAKES IT
        "court-ready" — evidence without stated methodology and known error rates is not
        admissible-grade material. Say so in the PDF itself.
      - A clear statement that NO AUDIO RECORDING EXISTS, with the reason (DPDP §8 data
        minimisation), so an investigator does not waste time requesting it.
 3. Every dossier generation appends a DOSSIER_GENERATED audit block including a SHA-256 of
    the produced PDF bytes, so the document itself is chain-anchored. Print that hash in the
    PDF footer along with the generation timestamp and generating user.
 4. Endpoints:
      GET /api/v1/forensics/{sessionId}/dossier         -> JSON
      GET /api/v1/forensics/{sessionId}/dossier.pdf     -> application/pdf
 5. The PDF must be professional: header/footer on every page, page numbers, a fixed-width font
    for hashes, and a cover page. It will be screenshotted into your deck — make it look like
    something a bank would actually file.
 6. Handle the empty case gracefully: a session with no risk events produces a valid "no
    findings" dossier rather than an error.

ACCEPTANCE:
  - After running Scenario 2, the PDF downloads and contains every section
  - The PDF's own hash is in the audit chain and matches a recomputation of the file
  - The risk timeline chart is legible when printed
  - A clean session produces a valid no-findings dossier
```

---

## P9.2 — Compliance portal
**Track:** 🟩 React + 🟥 Java · **Est:** 4 h · **Deps:** P9.1, P8.2 · **Tier 1/2**

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 13.3, 13.5)

TRACK: React frontend + Java backend

TASK: Build the DPDP/RBI compliance portal: audit chain explorer, consent register, erasure log,
retention dashboard, and fairness metrics.

FILES TO CREATE:
  frontend/src/views/CompliancePortal.tsx     (rewrite)
  frontend/src/components/compliance/AuditChainExplorer.tsx
  frontend/src/components/compliance/ConsentRegister.tsx
  frontend/src/components/compliance/RetentionDashboard.tsx
  frontend/src/components/compliance/FairnessChart.tsx
  frontend/src/components/compliance/DpdpMappingTable.tsx
  backend/.../controller/ComplianceAuditController.java   (extend)
  backend/.../compliance/ComplianceMetricsService.java

REQUIREMENTS:
 1. AuditChainExplorer: paged block list per session, each showing index, timestamp, event type,
    truncated hashes, and an expandable payload. A prominent "Verify Chain" button calling
    /api/v1/compliance/verify/{id} and showing a green VALID or a red INVALID with the broken
    index. THE LIVE TAMPER DEMO RUNS THROUGH THIS SCREEN — make the valid/invalid state large
    and unmissable from the back of a room.
 2. ConsentRegister: every ConsentRecord with purpose, notice version, grant date, status,
    and a withdraw action. Show DPDP section references inline.
 3. RetentionDashboard, driven by real counters, not mock numbers:
      - "Raw audio bytes persisted: 0" with a link to the enforcing code path
      - telemetry rows and their age distribution against the 90-day TTL
      - embeddings stored, erasures performed, tombstones recorded
      - next scheduled purge
 4. FairnessChart: false-positive rate by language group, gender, and channel profile, from
    the P12 evaluation output loaded as a JSON file. Show the gaps honestly — a chart with
    a visible disparity plus a written explanation of what you are doing about it is far more
    credible than a suspiciously flat one.
 5. DpdpMappingTable: the Context §13.3 table, with each row linking to the implementing
    endpoint or class. This is the artifact that answers "how exactly are you compliant?"
    in ten seconds.
 6. ComplianceMetricsService supplies all the counters. No hardcoded numbers anywhere in
    this view — every figure must be traceable to a real query.

ACCEPTANCE:
  - The chain explorer shows real blocks and verifies them live
  - Tampering a row in the H2 console flips the badge to INVALID with the correct index
  - Every number in the retention dashboard comes from a real query
  - The fairness chart renders the real P12 output (or an explicit "evaluation not yet run" state)
```

---

# PHASE 10 — ASR & linguistic intent

## P10.1 — Streaming multilingual ASR
**Track:** 🟦 Python + 🟪 AI · **Est:** 4 h · **Deps:** P2.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 7.2, 10.6)

TRACK: Python ML / AI

TASK: Implement the slow-path streaming ASR with Hindi/English code-switch support, running
asynchronously without blocking the fast path.

FILES TO CREATE:
  ml-engine/app/modules/asr.py
  ml-engine/app/slow_path.py
  ml-engine/tests/test_asr.py

REQUIREMENTS:
 1. Use faster-whisper (CTranslate2). Model size by budget: `small` is the sweet spot for
    CPU (roughly 4x real-time with int8); `medium` if you have a GPU. Make it configurable and
    log which model loaded at startup.
 2. The slow path runs on a SEPARATE asyncio task with its own cadence (every 2.5s over a 6s
    context window with overlap). It must NEVER block the 500ms fast path. Use a thread pool;
    verify with a test that fast-path latency is unaffected while ASR is running — this is the
    single most likely performance regression in the whole project.
 3. Code-switch handling: run with language=None (auto-detect) and set
    condition_on_previous_text=True for continuity. Hinglish typically detects as `hi` or `en`
    depending on the segment — record the detected language per segment and expose a
    `codeSwitchDetected` flag when it changes within a call.
 4. Maintain a rolling transcript per session with word-level timestamps where available.
    Emit only a DELTA (the new text since the last emission) plus a redacted running snippet.
 5. REDACTION IS MANDATORY before the transcript leaves Python (Context §13.2 control 6).
    Regex-mask: account numbers (>= 9 consecutive digits), Aadhaar-like 12-digit groups,
    card numbers (13-19 digits, Luhn-check them to reduce false masking), phone numbers,
    and email addresses. The REDACTED text is what goes in the FeatureFrame. The unredacted
    text never leaves the process and is not persisted.
 6. Handle silence gracefully: Whisper hallucinates confidently on silence (it will emit
    "Thank you." or subtitle-corpus artifacts). Gate ASR on VAD speech_ratio > 0.3 and
    filter known hallucination strings. This WILL bite you during a demo if you skip it.
 7. Measure and log slow-path latency; target < 900ms per 2.5s window with `small` on CPU.

ACCEPTANCE:
  - An English clip transcribes accurately
  - A Hinglish clip ("dadi, main musibat mein hoon, turant paise bhejo") transcribes
    recognisably and sets codeSwitchDetected
  - Silence produces an empty transcript, not a hallucination
  - Fast-path p95 latency is unchanged with ASR running (measured, not assumed)
  - Account numbers in speech appear masked in the FeatureFrame
```

---

## P10.2 — Social-engineering intent classifier
**Track:** 🟦 Python + 🟪 AI · **Est:** 5 h · **Deps:** P10.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 10.6)

TRACK: Python ML / AI

TASK: Build the linguistic intent scorer: urgency, secrecy, authority, emotional coercion,
plus structured extraction of the "ask".

FILES TO CREATE:
  ml-engine/app/modules/intent.py
  ml-engine/app/modules/lexicon/{en,hi,hi_roman,ta,te}.yaml
  ml-engine/app/modules/extractor.py
  ml-engine/tests/test_intent.py
  ml-engine/tests/fixtures/transcripts/     (labelled test transcripts)

REQUIREMENTS:
 1. HYBRID approach, and be explicit about why: you have no labelled fraud corpus, so a pure
    fine-tune is not available. Combine
      (a) a weighted multilingual LEXICON with phrase-level patterns, and
      (b) SEMANTIC SIMILARITY using multilingual sentence embeddings
          (sentence-transformers `paraphrase-multilingual-MiniLM-L12-v2`) against a set of
          canonical fraud utterances, so paraphrases the lexicon misses still score.
    Final score = max(lexicon_score, semantic_score) with the contributing evidence returned.
 2. Lexicon YAML per language, each entry {pattern, weight, category}. Cover:
      URGENCY: immediately, right now, within N minutes, before close of business,
               turant, abhi ke abhi, jaldi, udane, don't delay
      SECRECY: don't tell, keep this between us, confidential, bypass, don't check with,
               kisi ko mat batana, manager ko mat bolna, verbal approval only
      AUTHORITY: this is the CEO/CFO/MD, CBI, police, RBI, income tax, income tax department,
               main CEO bol raha hoon, court case, arrest warrant, digital arrest
      COERCION: you'll be held responsible, your account will be frozen, arrest,
               emergency, accident, hospital, bail, musibat, police station
    Devanagari AND romanised Hindi are BOTH required — Whisper output for Hinglish is
    inconsistent between them and a lexicon that only handles one will miss half your hits.
 3. SECRECY IS THE HIGHEST-SIGNAL CATEGORY (Context §10.6). Give it the largest weights and a
    lower firing threshold. Legitimate business speech essentially never asks the listener to
    conceal the interaction from a colleague. Comment this reasoning in the code — it is a good
    thing to be able to explain out loud.
 4. extractor.py pulls the structured ask: transaction type, amount (handle "50 lakh",
   "50,00,000", "fifty lakhs", "पचास लाख", "5 million"), currency, beneficiary hint,
    deadline phrase, plus claimedIdentity and claimedRole via NER or pattern matching on
    "this is X" / "main X bol raha hoon" / "I am the X".
    Indian number words are a real parsing problem — lakh and crore — write dedicated tests.
 5. Recency weighting: a pressure phrase in the last 10 seconds counts more than one 90 seconds
    ago. Apply exponential decay over the transcript history.
 6. Test fixtures: at least 20 labelled transcripts (10 fraudulent across en/hi/hinglish/tamil,
    10 benign including EMOTIONALLY URGENT BUT LEGITIMATE ones — "my card is blocked and I'm
    stranded at the airport, please help urgently"). That last category is the false-positive
    trap and Scenario 5 depends on it. Assert benign-urgent transcripts score high on urgency
    but LOW on secrecy and authority — proving the sub-scores are genuinely independent.

ACCEPTANCE:
  - All 20 fixtures classified correctly on the category level
  - The benign-urgent transcripts score urgency > 0.6 but secrecy < 0.2
  - "50 lakh", "50,00,000" and "fifty lakhs" all extract to 5000000
  - Hinglish grandparent-scam text triggers coercion + urgency
```

---

# PHASE 11 — Senior Shield & scenarios

## P11.1 — Scenario engine
**Track:** 🟥 Java + 🟨 · **Est:** 3 h · **Deps:** P8.3

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 14)

TRACK: Java backend + DevOps

TASK: Build the scenario engine that seeds directory, relationship, cross-channel and audio
state so any of the six demo scenarios can be run reproducibly with one click.

FILES TO CREATE:
  scenarios/*.yaml                  (six scenario definitions)
  backend/.../scenario/ScenarioService.java
  backend/.../scenario/model/Scenario.java
  backend/.../controller/ScenarioController.java
  gateway/replay_audio.py
  scenarios/README.md

REQUIREMENTS:
 1. Scenario YAML defines: id, title, description, expected trajectory, channelProfile,
    caller {cli, trunkClass, claimedIdentity, claimedRole}, callee, directory overrides,
    relationship edges, cross-channel events, the audio source (a file path or "live"),
    and the expected final intervention level.
 2. The SIX scenarios from Context §14. Scenario 5 (the false-positive stress test) and
    Scenario 6 (adversarial evasion) are NOT optional — they are what distinguish your demo.
 3. POST /api/v1/scenario/{id}/load applies the seed state and returns a session descriptor.
    GET /api/v1/scenario lists them with their descriptions.
 4. replay_audio.py streams a WAV file into the ml-engine ingest WebSocket at REAL TIME PACE
    (not as fast as possible — the whole demo depends on the risk score evolving at a watchable
    speed). Support --profile to force codec degradation on the fly via the P4.3 fixtures,
    and --loop for rehearsal.
 5. Every scenario must be runnable through EITHER the replay path OR a live Asterisk call,
    selected by a flag. You need the replay path for reliability and the live path for impact.
 6. Assert the expected trajectory in an integration test — a scenario that silently stops
    escalating correctly after a refactor must fail the build, not fail on stage.

ACCEPTANCE:
  - All six scenarios load and run to their expected final level
  - The integration test asserts each scenario's trajectory
  - replay_audio.py plays at real-time pace and the gauge evolves watchably
```

---

## P11.2 — Senior Citizen Shield mode
**Track:** 🟩 React · **Est:** 4 h · **Deps:** P6.1, P11.1

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 7.4, 14 scenario 4)

TRACK: React frontend

TASK: Build the simplified, high-contrast protective interface for elderly and vulnerable users.

FILES TO CREATE:
  frontend/src/views/SeniorShield.tsx         (rewrite)
  frontend/src/components/senior/BigAlert.tsx
  frontend/src/components/senior/SosButton.tsx
  frontend/src/components/senior/SimpleStatus.tsx
  frontend/src/components/senior/LanguageToggle.tsx

REQUIREMENTS:
 1. COMPLETELY different design language from the analyst console. Design for someone who is
    70, possibly anxious, possibly without glasses to hand, on a phone:
      - minimum 24px body text, 48px+ for the status
      - maximum contrast, no dark-on-dark
      - THREE states only: green "This call looks normal" / amber "Be careful" /
        red "STOP — this may be a fake voice"
      - NO numbers, NO percentages, NO jargon. Never show "0.81" or "LEVEL_4_AUTO_HOLD".
      - every touch target >= 64px
 2. BigAlert at high risk: full-screen red, the message in the chosen language, and ONE
    instruction: "DO NOT SEND MONEY. Hang up and call your family."
    Add a gentle pulse animation and (optionally) a spoken alert via the Web Speech API —
    an elderly user may not be looking at the screen.
 3. LanguageToggle: English, Hindi, Tamil, Telugu at minimum. Translate the three status
    messages and the alert. Use real translations, not machine output you can't read —
    ask a native speaker on your team. A mistranslated safety warning is worse than English.
 4. SosButton: a large "Alert My Family" button that (mock) notifies a pre-registered contact.
    Show a confirmation the user cannot miss. Include the reason in the mock notification
    ("Your mother received a suspicious call at 3:42 PM that our system flagged as a possible
    fake voice").
 5. Add the ONE piece of advice that actually works and costs nothing, from the security
    literature: a family safe word. Put a permanent, calm banner in the app:
    "Agree a family password. Ask for it if a call feels wrong."
    This shows you know that the best defence against a grandparent scam is social, not
    technical — a nuance that will land well with judges.
 6. Must look right on a 390px-wide phone viewport. Test at that width specifically, and be
    ready to show it on an actual phone.

ACCEPTANCE:
  - Running Scenario 4 shows the full-screen red alert with the Hindi message
  - Every element is legible from two metres away at 390px width
  - The language toggle changes all user-facing strings
  - No number or technical term appears anywhere in this view
```

---

# PHASE 12 — Evaluation

## P12.1 — Benchmark harness
**Track:** 🟪 AI/model · **Est:** 4 h · **Deps:** P4.5

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 15)

TRACK: AI / model evaluation

TASK: Build the evaluation harness producing EER, min t-DCF, calibration, latency and fairness
metrics across all datasets and channel profiles.

FILES TO CREATE:
  ml-engine/benchmarks/run_eval.py
  ml-engine/benchmarks/metrics.py
  ml-engine/benchmarks/datasets.py
  ml-engine/benchmarks/report.py
  ml-engine/benchmarks/README.md

REQUIREMENTS:
 1. metrics.py: EER (with the exact threshold), min t-DCF (use the official ASVspoof formulation
    and cite it), FPR at fixed TPR (0.80/0.90/0.95), AUC, Expected Calibration Error,
    and a reliability-diagram plotter.
 2. datasets.py: loaders for ASVspoof 2019 LA eval, ASVspoof 2021 DF eval, In-the-Wild,
    and your own codec-degraded sets. A --limit flag for smoke testing.
 3. run_eval.py evaluates each model checkpoint on every dataset x every channel condition,
    writing results to docs/EVALUATION_REPORT.md AND a machine-readable
    benchmarks/results.json (consumed by the compliance portal's fairness chart).
 4. report.py generates the markdown tables and the PNG plots. Output must be reproducible:
    fix the random seed and record the git commit hash, model ID and dataset versions in the report.
 5. Report latency separately: p50/p95/p99 for the fast path, slow path, and end-to-end.
 6. BE HONEST. If In-the-Wild EER is 28%, the report says 28%. Context §3.1 shows that this is
    the expected result for the whole field, and your system architecture is DESIGNED around
    that fact. A bad number honestly reported and correctly explained is a strength here; a
    suspiciously good number will be probed and will not survive the probing.

ACCEPTANCE:
  - `make eval` produces docs/EVALUATION_REPORT.md with populated tables
  - Reliability diagram PNGs generated
  - results.json is valid and loads in the compliance portal
  - The report records the commit hash and model versions
```

---

## P12.2 — The codec robustness study
**Track:** 🟪 AI/model · **Est:** 5 h · **Deps:** P12.1, P4.5

**This is your unique technical contribution.** No other team will have a before/after robustness table under real telephony codecs.

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 3.2, 15.3)

TRACK: AI / model evaluation

TASK: Run the codec-degradation study and produce the before/after robustness table that
directly answers "does this work on a real phone call?"

FILES TO CREATE:
  ml-engine/benchmarks/build_codec_set.py
  ml-engine/benchmarks/codec_study.py
  docs/CODEC_ROBUSTNESS_STUDY.md

REQUIREMENTS:
 1. build_codec_set.py generates, from each eval set, these conditions via ffmpeg:
      clean 16k | opus 24k | opus 12k | g711 mulaw 8k | g711 alaw 8k |
      amr_nb 12.2k | amr_nb 4.75k | mp3 64k | + packet loss 1% and 3% on top of g711
    All decoded back to 16k WAV. Cache aggressively — this takes a while and you will rerun it.
 2. codec_study.py evaluates BOTH models (baseline and codec-augmented, from P4.5) across
    ALL conditions and produces the central table:

      | Condition | Baseline EER | Codec-augmented EER | Delta |

    plus per-condition FPR@TPR=0.90, and a grouped bar chart.
 3. Also report the DUAL-PROFILE result: what does the full fusion system's end-to-end
    false-escalation rate look like on narrowband vs wideband? This connects the model-level
    finding to the system-level claim and is the more persuasive number of the two.
 4. Write docs/CODEC_ROBUSTNESS_STUDY.md as a short, proper experimental report:
    motivation, method, results, discussion, limitations. Two pages. Judges who read it will
    take you seriously; judges who only see the table will still get the point.
 5. State the limitations explicitly: ffmpeg codec simulation is not identical to a real carrier
    path (no jitter buffer behaviour, no real network loss patterns, no handset acoustics,
    no AGC). Say so. Then note that the Asterisk path in P7 gives you a real G.711 leg as a
    sanity check, and report a small spot-check on it.

ACCEPTANCE:
  - The full condition matrix is generated and evaluated
  - The before/after table shows a measurable improvement from codec augmentation
    (if it does NOT, report that honestly and investigate — a null result reported well is
    still far better than a fabricated one)
  - The study document reads as a short scientific report with stated limitations
  - The grouped bar chart is deck-ready
```

---

# PHASE 13 — Tier-2 extras

> Only start these when Tiers 0 and 1 are demo-stable. Each is self-contained; drop any of them without consequence.

## P13.1 — Cross-channel correlation
**Track:** 🟥 Java + 🟩 React · **Est:** 4 h

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 7.3)

TRACK: Java backend + React frontend

TASK: Implement cross-channel correlation linking prior phishing email and smishing SMS events
to the active call, with a timeline visualisation.

FILES TO CREATE:
  context/CrossChannelCorrelationService.java   (rewrite from the P1.4 seeded version)
  context/model/CrossChannelEvent.java          (JPA entity)
  repository/CrossChannelEventRepository.java
  controller/CrossChannelController.java        (rewrite)
  frontend/src/components/CrossChannelTimeline.tsx

REQUIREMENTS:
 1. CrossChannelEvent: channel (EMAIL|SMS|AUTH|WEB), targetEmployeeId, occurredAt,
    severity, indicator (sender, sending domain, URL, or number), campaignId, description.
 2. POST /api/v1/cross-channel/ingest accepts events (this is where a real SIEM would feed in).
    Seed realistic events in data.sql for the demo: a BEC email to Sunita Rao 36 hours before
    the call, purportedly from the CFO, followed by an SMS 4 hours before.
 3. Correlation within a configurable window (default 48h) on the CALLEE, plus optional
    indicator matching (the same spoofed display name, the same campaign ID).
    Return a correlation score and the matched events. Emit CROSS_CHANNEL_PRECURSOR at HIGH
    when a matching campaign exists — that is a genuinely strong signal, because a voice call
    preceded by a targeted BEC email against the same person is a multi-stage campaign, not
    a coincidence.
 4. CrossChannelTimeline: a horizontal timeline, email -> SMS -> call, with severity colouring
    and the time gaps labelled. Make the narrative legible at a glance: "this attack started
    36 hours ago."
 5. Feed the correlation score into the fusion engine as a sub-component of the RELATIONSHIP
    family (or as its own family with re-normalised weights — document the choice).

ACCEPTANCE:
  - Scenario 2 shows two correlated precursor events on the timeline
  - A scenario with no precursors shows an empty timeline, not fabricated events
  - The correlation raises the relationship family score measurably
```

## P13.2 — Adversarial red-team lab
**Track:** 🟦 Python + 🟩 React · **Est:** 4 h

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 5.1 A7, 16.2)

TRACK: Python ML + React frontend

TASK: Build an interactive evasion playground that applies perturbations to a live or replayed
stream and shows the effect on detection in real time.

FILES TO CREATE:
  ml-engine/app/modules/adversarial.py
  ml-engine/app/routes/redteam.py
  frontend/src/views/RedTeamLab.tsx          (rewrite)
  frontend/src/components/redteam/PerturbationControls.tsx
  frontend/src/components/redteam/RobustnessChart.tsx

REQUIREMENTS:
 1. Perturbations, each with a live-adjustable intensity:
      additive noise (SNR slider 5-30 dB), pitch shift (+/-5%), time stretch,
      codec round-trip (pick a codec), synthetic reverb (T60 slider),
      inserted breath samples at a chosen rate, packet-loss simulation, band-limiting
 2. Apply in-line to the ingest stream so the analyst console reacts live. Both panels
    side by side is the demo: perturbation on the left, the risk gauge dropping on the right.
 3. RobustnessChart: a live plot of each family's score as the intensity slider moves. THE
    POINT to make visible: acoustic families degrade under evasion while CONTEXTUAL families
    hold flat. That single chart is the visual proof of your corroboration argument from
    Context §9.3 — arguably the best slide in the whole deck.
 4. A "Run Sweep" button that automatically steps through intensities and produces a
    degradation curve, exportable as a PNG for the deck.
 5. Label the view clearly as a DEFENSIVE TESTING TOOL. Do not include anything that generates
    or improves a voice clone — this is a robustness evaluator that perturbs existing audio,
    not an attack toolkit. State that in the UI and in the README.

ACCEPTANCE:
  - Moving the noise slider visibly drops the voice family score and holds the contextual ones
  - The sweep produces an exportable degradation curve
  - No clone-generation capability exists anywhere in the module
```

## P13.3 — AudioSeal watermark scanner
**Track:** 🟦 Python · **Est:** 2 h

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (section 10.7)

TRACK: Python ML

TASK: Implement the AudioSeal watermark detector behind a pluggable provider interface, with
the correct semantics: attribution only, never a risk input.

FILES TO CREATE:
  ml-engine/app/modules/watermark.py
  ml-engine/app/modules/watermark_providers/{base.py,audioseal.py,stub_synthid.py}
  ml-engine/tests/test_watermark.py

REQUIREMENTS:
 1. `pip install audioseal`. Use the released detector. Report detection plus the
    sample-level localisation the detector provides.
 2. WatermarkProvider ABC with detect(audio, sr) -> {detected, provider, confidence, spans}.
    audioseal.py implements it for real. stub_synthid.py returns
    {available: False, reason: "SynthID detection requires provider-side tooling"} —
    an HONEST stub with an explanatory docstring. Do NOT fake a SynthID detector.
    Context §16.3 has the exact wording for when a judge asks.
 3. CRITICAL SEMANTICS, and make it a code-level guarantee not a convention:
    the watermark result MUST NOT feed the risk score. It is attribution enrichment only.
    Add an assertion or an architecture test proving no fusion weight references the
    watermark block. Reasoning to put in the comment: no real attacker uses a watermarking
    TTS, so absence of a watermark carries zero information, and wiring it into the score
    would make unwatermarked audio look safer than it is.
 4. When a watermark IS detected, emit a WATERMARK_DETECTED reason at INFO severity with the
    provider name, and surface it in the identity card as an attribution note.

ACCEPTANCE:
  - Audio watermarked with AudioSeal is detected; clean audio is not
  - The SynthID provider returns the honest unavailable response
  - The architecture test proves the watermark cannot influence the risk score
```

---

# PHASE 14 — Documentation, packaging & rehearsal

## P14.1 — Docs & architecture diagrams
**Track:** 🟨 · **Est:** 3 h

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (all)

TRACK: Documentation

TASK: Produce the submission documentation set.

FILES TO CREATE:
  docs/ARCHITECTURE.md
  docs/REGULATORY_DPDP_MAPPING.md
  docs/API_REFERENCE.md
  docs/CALL_INTEGRATION_GUIDE.md
  docs/DEMO_RUNBOOK.md
  README.md   (rewrite)

REQUIREMENTS:
 1. ARCHITECTURE.md: Mermaid diagrams (renderable on GitHub) for the four planes, the per-frame
    sequence diagram, the intervention FSM as a state diagram, and the call-integration topology.
    Keep the ASCII versions too — they survive copy-paste into a deck.
 2. REGULATORY_DPDP_MAPPING.md: expand Context §13.3 with, for each provision, the exact class
    or endpoint implementing it. Include the RBI Cyber Security Framework mapping.
 3. API_REFERENCE.md: every REST endpoint and STOMP topic with request/response examples.
    Generate from springdoc-openapi if you add it; otherwise write it by hand from the controllers.
 4. CALL_INTEGRATION_GUIDE.md: the definitive guide to the four integration paths from
    Context §11 — setup steps, protocol details, troubleshooting, and which actuation actions
    each path supports. This is the document that proves the telephony work is real and not
    a slide.
 5. DEMO_RUNBOOK.md: minute-by-minute from Context §20, plus a PRE-FLIGHT CHECKLIST
    (services up, softphones registered, scenarios seeded, audio devices selected, browser
    permissions granted, ngrok up if used, fallback video located) and a FAILURE PLAYBOOK
    (what to do when the socket drops, when Asterisk won't register, when the mic is muted,
    when the venue blocks a port). Rehearse against the checklist.
 6. README.md: what it is, the architecture diagram, a 5-minute quickstart, the demo commands,
    and links to everything else.

ACCEPTANCE:
  - All Mermaid diagrams render on GitHub
  - A teammate can follow the quickstart on a clean machine and reach a working demo
  - The runbook checklist is complete enough to follow while nervous
```

## P14.2 — Packaging & one-command demo
**Track:** 🟨 · **Est:** 3 h

```
TRACK: DevOps

TASK: Make the entire system start with one command, on Windows and Linux, and survive a
hackathon venue.

FILES TO CREATE/MODIFY:
  docker-compose.yml       (complete it)
  ml-engine/Dockerfile
  backend/Dockerfile
  frontend/Dockerfile
  scripts/run_all.sh
  scripts/run_all.bat
  scripts/preflight.sh
  scripts/seed_demo.sh
  .env.example

REQUIREMENTS:
 1. `docker compose up` brings up Asterisk, ml-engine, backend and frontend with correct
    dependency ordering and healthchecks. The backend must WAIT for ml-engine health, not
    crash-loop against it.
 2. Multi-stage Dockerfiles. Bake the ML model weights into the ml-engine image or mount them
    from a volume — do NOT download models at container start. Venue Wi-Fi will fail and a
    container that downloads a 500MB model on boot will end your demo.
 3. run_all.bat for Windows (the team is on Windows per the original plan) launching all four
    services in separate terminals with clear titles, and run_all.sh for Linux/macOS.
 4. preflight.sh checks every prerequisite and prints a pass/fail list: Java 21, Node 20+,
    Python 3.11, Docker running, ports 8080/8000/5173/5060/8088/9092 free, model files present,
    audio device available. Run this before every rehearsal and before walking on stage.
 5. seed_demo.sh loads the directory, relationship graph, cross-channel events, passports and
    all six scenarios in one shot, then prints the URLs to open.
 6. `make demo` = preflight + up + seed + open browser.
 7. EVERYTHING must work with NO internet access. Verify by disabling networking and running
    the full demo. This is not optional — it is the single highest-probability failure mode
    at any hackathon venue.

ACCEPTANCE:
  - `make demo` on a clean checkout reaches a working demo in under 5 minutes
  - The full Tier-0 + Tier-1 demo runs with networking disabled
  - preflight.sh catches a deliberately occupied port
```

## P14.3 — Pitch deck & rehearsal
**Track:** 🟨 · **Est:** 4 h + rehearsal time

```
CONTEXT: @docs/00_PROJECT_CONTEXT.md (sections 2, 3, 4, 16, 20)

TRACK: Presentation

TASK: Build the pitch deck and rehearse the demo to the point of boredom.

FILES TO CREATE:
  docs/PITCH_DECK_SCRIPT.md
  docs/JUDGE_QA_PREP.md

REQUIREMENTS:
 1. Deck outline, following Context §20 timing:
      1. The hook — cloned voice + the CNAP gap (Context §2.3). Lead with CNAP: it is current,
         Indian, and almost no other team will know about it.
      2. The problem, with the India numbers from Context §2.2 — every stat cited on-slide
      3. Why detection alone fails — the generalization-gap evidence from Context §3.1.
         This is the slide that establishes you as the serious team.
      4. The reframe + the four claims
      5. Architecture: four planes, and "Java never touches audio"
      6. LIVE DEMO (the largest time block by far)
      7. The refusal — Scenario 5, the system declining to escalate on a real customer
      8. Social impact — Senior Shield
      9. Proof — the codec robustness table, the reliability diagram, the live ledger tamper
     10. Compliance — the DPDP mapping table
     11. Roadmap, EXPLICITLY LABELLED as roadmap (SIPREC, federated learning, edge ONNX, CBS)
     12. Close on the one-liner
 2. JUDGE_QA_PREP.md: every question in Context §16 with the rehearsed answer, PLUS the
    volunteered limitations from §16.8. Assign an owner per question area so nobody freezes
    and two people don't answer at once.
 3. REHEARSAL DISCIPLINE:
      - run the full demo end to end at least 20 times
      - run it at least 3 times with a deliberately broken component to practise recovery
      - time it; you will overrun on your first five attempts
      - one person drives, one narrates, never both
      - record a video of a perfect run as a fallback and have it open in a background tab
 4. FREEZE THE CODE 24 HOURS BEFORE. No new features on demo day. Every hackathon team that
    ships a feature the night before regrets it.

ACCEPTANCE:
  - Full run completes in 7 minutes with 30 seconds of slack
  - Every team member can answer any question in the prep doc
  - The fallback video exists and is accessible offline
```

---

# D. Risk register & compression plans

## D.1 Risks, ranked by expected damage

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| 1 | **Scope overrun — half of everything, none of it working** | **High** | Tier discipline (Context §17). Tier 0 done by Day 5 or cut hard. |
| 2 | **Venue internet fails** | **High** | Everything Tier-0/1 runs offline. Asterisk in Docker. Models baked into images. Fallback video. |
| 3 | Asterisk registration / NAT / one-way audio eats a day | High | Use `network_mode: host`. Budget a full day for P7.1. Test on the actual demo laptop early. |
| 4 | ASR latency starves the fast path | Medium | Separate task + thread pool; P10.1 explicitly tests for this regression |
| 5 | Model download / dataset size blocks training | Medium | `--limit` smoke path; start downloads on Day 1 in the background |
| 6 | Twilio Indian number regulatory bundle doesn't arrive | Medium | Start Day 1 or use Exotel; Asterisk is the primary path regardless |
| 7 | Cursor rewrites working code | Medium | `.cursor/rules`, explicit file lists, commit after every green step |
| 8 | Speaker verification fails on genuine speakers (channel mismatch) | Medium | Per-profile passports (P8.2); INCONCLUSIVE rather than a misleading score |
| 9 | Whisper hallucinates on silence during the demo | Medium | VAD gating + hallucination filter in P10.1 |
| 10 | Risk score flickers between levels on stage | Medium | Hysteresis + dwell (P5.2), with an explicit flicker regression test |
| 11 | A judge finds an unimplemented feature presented as working | Medium | Label roadmap items. Never demo a mock without saying it is one. |
| 12 | Demo laptop audio device chaos (virtual cable, wrong input) | High | preflight.sh checks it; rehearse on the exact machine |

## D.2 If you only have 7 days

Cut to: **P0.1, P0.2, P1.1, P1.2, P1.3, P2.1, P2.2, P3.1, P3.2, P4.1, P4.2, P4.6, P5.1, P5.2, P5.3, P5.4, P6.1, P6.2, P6.3, P6.4, P7.1, P7.2, P7.3, P11.1, P14.1, P14.2, P14.3.**

You lose: the trained anti-spoof model (use the prosody + channel heuristics and say so honestly), ASR/NLP (hand-seed the linguistic block per scenario and label it as seeded), the evaluation study, Senior Shield, forensics, compliance portal, and all of Tier 2.

**Keep P7 (Asterisk) even in this cut.** The real-call demo is worth more than three dashboard features.

## D.3 If you only have 3 days

**P0.1, P0.2, P1.1, P1.2, P2.1, P2.2, P3.1, P3.2, P4.2, P4.6, P5.1, P5.2, P5.4, P6.1, P6.2, P6.4, P11.1, P14.3.**

A working WebRTC-to-dashboard spine with real prosody features, real fusion math, a working intervention ladder and a locking approve button. Present the rest as architecture. **This is still a better submission than a broad, broken build** — and be upfront in the pitch about exactly what is built versus designed.

## D.4 The rules that save the project

1. **Tier 0 working end-to-end beats Tier 2 half-built.** Always.
2. **Commit after every green step.** Tag milestones.
3. **Never demo something you cannot explain.** If you can't say how it works, cut it.
4. **Never present a mock as real.** Label it and move on — labelling costs nothing and getting caught costs everything.
5. **Freeze 24 hours before.** Rehearse instead.
6. **The honest number beats the impressive number.** Context §3 is your armour: the whole field has this problem, you measured it, and you designed around it.

---

# E. Quick reference — steps by track

| Track | Steps |
|---|---|
| 🟥 **Java backend** | P1.1, P1.2, P1.3, P1.4, P2.2(b), P3.2(a), P5.1, P5.2, P5.3, P5.4, P6.4(b), P7.3, P8.1, P8.2(a), P8.3, P8.4(a), P9.1, P9.2(b), P13.1(a) |
| 🟦 **Python ML** | P2.1, P2.2(a), P4.1, P4.2, P4.3, P4.6, P7.2(b), P8.2(b), P10.1, P10.2, P13.2(a), P13.3 |
| 🟪 **AI / model** | P4.4, P4.5, P10.1, P10.2, P12.1, P12.2 |
| 🟩 **React frontend** | P3.1, P3.2(b), P6.1, P6.2, P6.3, P6.4(a), P9.2(a), P11.2, P13.1(b), P13.2(b) |
| 🟨 **DevOps / Telephony** | P0.1, P0.2, P7.1, P7.2(a), P7.4, P11.1, P14.1, P14.2, P14.3 |

---

*End of execution plan. Architecture and rationale: `00_PROJECT_CONTEXT.md`.*
