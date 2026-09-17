/* GENERATED - DO NOT EDIT. Editor hints only; not compiled. Run scripts/gen-contracts.sh */

/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "ChannelProfile".
 */
type ChannelProfile = "PSTN_NARROWBAND" | "VOIP_WIDEBAND" | "WEBRTC_WIDEBAND";
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "VoiceFamily".
 */
type VoiceFamily = {
  [k: string]: unknown;
} & {
  available: boolean;
  spoofProbability?: UnitInterval;
  modelId?: string;
  confidence?: UnitInterval;
};
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "UnitInterval".
 */
type UnitInterval = number;
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "ChannelFamily".
 */
type ChannelFamily = {
  [k: string]: unknown;
} & {
  available: boolean;
  rirT60Ms?: number;
  rirPlausible?: boolean;
  doubleCompressionScore?: UnitInterval;
  noiseFloorStationarity?: UnitInterval;
  dcOffset?: number;
};
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "ProsodyFamily".
 */
type ProsodyFamily = {
  [k: string]: unknown;
} & {
  available: boolean;
  f0MeanHz?: number;
  f0StdHz?: number;
  jitterLocalPct?: number;
  shimmerLocalPct?: number;
  hnrDb?: number;
  breathEventsPerMin?: number;
  disfluencyRate?: number;
  articulationRateSylSec?: number;
  unnaturalnessScore?: UnitInterval;
};
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "SpeakerFamily".
 */
type SpeakerFamily = {
  [k: string]: unknown;
} & {
  available: boolean;
  embeddingId?: string;
  enrolledProfileId?: string | null;
  cosineSimilarity?: UnitInterval;
  intraCallDrift?: UnitInterval;
};
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "WatermarkFamily".
 */
type WatermarkFamily = {
  [k: string]: unknown;
} & {
  available: boolean;
  detector?: string;
  detected?: boolean;
  provider?: string | null;
  confidence?: UnitInterval;
};
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "LinguisticFamily".
 */
type LinguisticFamily = {
  [k: string]: unknown;
} & {
  available: boolean;
  ageMs?: number;
  language?: string;
  urgency?: UnitInterval;
  secrecy?: UnitInterval;
  authorityInvocation?: UnitInterval;
  emotionalCoercion?: UnitInterval;
  askDetected?: boolean;
  ask?: Ask;
  claimedIdentity?: string | null;
  claimedRole?: string | null;
  redactedSnippet?: string;
};
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "InterventionLevel".
 */
type InterventionLevel =
  "LEVEL_1_SILENT" | "LEVEL_2_SOFT_NUDGE" | "LEVEL_3_STEP_UP_MFA" | "LEVEL_4_AUTO_HOLD" | "LEVEL_5_TERMINATE";
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "RiskState".
 */
type RiskState = "SCORED" | "INSUFFICIENT_EVIDENCE" | "DEGRADED";
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "IdentityVerdict".
 */
type IdentityVerdict = "VERIFIED" | "INCONCLUSIVE" | "IMPERSONATION_HUMAN" | "IMPERSONATION_SYNTHETIC";
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "Trend".
 */
type Trend = "RISING" | "FALLING" | "STABLE";
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "ReasonSeverity".
 */
type ReasonSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

/**
 * Inference Plane → Decision Plane. Emitted every 500 ms. No audio, no verbatim transcript beyond a short redacted snippet. Context §8.1.
 */
interface FeatureFrame {
  schema: "sentinelvoice.FeatureFrame/1";
  sessionId: string;
  seq: number;
  windowStartMs: number;
  windowEndMs: number;
  channelProfile: ChannelProfile;
  speechPresent: boolean;
  cumulativeSpeechMs: number;
  voice: VoiceFamily;
  channel: ChannelFamily;
  prosody: ProsodyFamily;
  speaker: SpeakerFamily;
  watermark: WatermarkFamily;
  linguistic: LinguisticFamily;
  latencyMs: {
    fastPath: number;
    slowPath: number;
  };
}
/**
 * This interface was referenced by `FeatureFrame`'s JSON-Schema
 * via the `definition` "Ask".
 */
interface Ask {
  type: string;
  amount: number;
  currency: string;
  beneficiaryHint: string;
  deadline: string;
}

/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "UnitInterval".
 */
type UnitInterval = number;
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "Trend".
 */
type Trend = "RISING" | "FALLING" | "STABLE";
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "RiskState".
 */
type RiskState = "SCORED" | "INSUFFICIENT_EVIDENCE" | "DEGRADED";
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "InterventionLevel".
 */
type InterventionLevel =
  "LEVEL_1_SILENT" | "LEVEL_2_SOFT_NUDGE" | "LEVEL_3_STEP_UP_MFA" | "LEVEL_4_AUTO_HOLD" | "LEVEL_5_TERMINATE";
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "IdentityVerdict".
 */
type IdentityVerdict = "VERIFIED" | "INCONCLUSIVE" | "IMPERSONATION_HUMAN" | "IMPERSONATION_SYNTHETIC";
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "ReasonSeverity".
 */
type ReasonSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "ChannelProfile".
 */
type ChannelProfile = "PSTN_NARROWBAND" | "VOIP_WIDEBAND" | "WEBRTC_WIDEBAND";

/**
 * Decision Plane → React via STOMP /topic/telemetry/{sessionId}. Context §8.2.
 */
interface TelemetryFrame {
  schema: "sentinelvoice.TelemetryFrame/1";
  sessionId: string;
  seq: number;
  tsEpochMs: number;
  callElapsedMs: number;
  risk: Risk;
  families: Families;
  corroboration: Corroboration;
  intervention: Intervention;
  identity: Identity;
  topReasons: Reason[];
  transcriptDelta: TranscriptDelta;
  auditHash: string;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "Risk".
 */
interface Risk {
  instantaneous: UnitInterval;
  smoothed: UnitInterval;
  trend: Trend;
  state: RiskState;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "Families".
 */
interface Families {
  voice: FamilyScore;
  channel: FamilyScore;
  prosody: FamilyScore;
  linguistic: FamilyScore;
  transaction: FamilyScore;
  relationship: FamilyScore;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "FamilyScore".
 */
interface FamilyScore {
  score: UnitInterval;
  weight: UnitInterval;
  contribution: UnitInterval;
  available: boolean;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "Corroboration".
 */
interface Corroboration {
  familiesAboveThreshold: string[];
  independentFamiliesRequired: number;
  satisfied: boolean;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "Intervention".
 */
interface Intervention {
  level: InterventionLevel;
  previousLevel: InterventionLevel;
  changedAtMs: number;
  dwellRemainingMs: number;
  manualOverride: null | {
    analystId: string;
    reason: string;
    targetLevel: InterventionLevel;
  };
  actionsFired: string[];
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "Identity".
 */
interface Identity {
  cli: string;
  cliTrunk: string;
  directoryMatch: {} | null;
  claimedIdentity: string | null;
  claimedRole: string | null;
  directoryRecordForClaim: null | DirectoryRecord;
  cliVsClaimMismatch: boolean;
  voicePassport: VoicePassport;
  presenceConflict: null | PresenceConflict;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "DirectoryRecord".
 */
interface DirectoryRecord {
  employeeId: string;
  role: string;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "VoicePassport".
 */
interface VoicePassport {
  enrolled: boolean;
  cosine: UnitInterval;
  verdict: IdentityVerdict;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "PresenceConflict".
 */
interface PresenceConflict {
  expected: string;
  observed: string;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "Reason".
 */
interface Reason {
  code: string;
  severity: ReasonSeverity;
  text: string;
}
/**
 * This interface was referenced by `TelemetryFrame`'s JSON-Schema
 * via the `definition` "TranscriptDelta".
 */
interface TranscriptDelta {
  tsMs: number;
  text: string;
  flags: string[];
}

/**
 * This interface was referenced by `AuditBlock`'s JSON-Schema
 * via the `definition` "UnitInterval".
 */
type UnitInterval = number;
/**
 * This interface was referenced by `AuditBlock`'s JSON-Schema
 * via the `definition` "InterventionLevel".
 */
type InterventionLevel =
  "LEVEL_1_SILENT" | "LEVEL_2_SOFT_NUDGE" | "LEVEL_3_STEP_UP_MFA" | "LEVEL_4_AUTO_HOLD" | "LEVEL_5_TERMINATE";
/**
 * This interface was referenced by `AuditBlock`'s JSON-Schema
 * via the `definition` "ChannelProfile".
 */
type ChannelProfile = "PSTN_NARROWBAND" | "VOIP_WIDEBAND" | "WEBRTC_WIDEBAND";
/**
 * This interface was referenced by `AuditBlock`'s JSON-Schema
 * via the `definition` "RiskState".
 */
type RiskState = "SCORED" | "INSUFFICIENT_EVIDENCE" | "DEGRADED";
/**
 * This interface was referenced by `AuditBlock`'s JSON-Schema
 * via the `definition` "IdentityVerdict".
 */
type IdentityVerdict = "VERIFIED" | "INCONCLUSIVE" | "IMPERSONATION_HUMAN" | "IMPERSONATION_SYNTHETIC";
/**
 * This interface was referenced by `AuditBlock`'s JSON-Schema
 * via the `definition` "Trend".
 */
type Trend = "RISING" | "FALLING" | "STABLE";
/**
 * This interface was referenced by `AuditBlock`'s JSON-Schema
 * via the `definition` "ReasonSeverity".
 */
type ReasonSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

/**
 * One block in the SHA-256 hash-chained audit ledger. Context §8.3 / §9.7.
 */
interface AuditBlock {
  schema: "sentinelvoice.AuditBlock/1";
  sessionId: string;
  blockIndex: number;
  tsEpochMs: number;
  eventType:
    | "SESSION_OPENED"
    | "FEATURE_FRAME_SCORED"
    | "RISK_LEVEL_CHANGED"
    | "INTERVENTION_ACTION_FIRED"
    | "ANALYST_OVERRIDE"
    | "CHALLENGE_ISSUED"
    | "CHALLENGE_RESULT"
    | "PASSPORT_ENROLLED"
    | "PASSPORT_ERASED"
    | "TRANSCRIPT_BREAK_GLASS_ACCESS"
    | "DOSSIER_GENERATED"
    | "SESSION_CLOSED";
  payload: {};
  smoothedRisk: UnitInterval;
  level: InterventionLevel;
  previousHash: string;
  currentHash: string;
}

/**
 * This interface was referenced by `SessionStartRequest`'s JSON-Schema
 * via the `definition` "ChannelProfile".
 */
type ChannelProfile = "PSTN_NARROWBAND" | "VOIP_WIDEBAND" | "WEBRTC_WIDEBAND";
/**
 * This interface was referenced by `SessionStartRequest`'s JSON-Schema
 * via the `definition` "InterventionLevel".
 */
type InterventionLevel =
  "LEVEL_1_SILENT" | "LEVEL_2_SOFT_NUDGE" | "LEVEL_3_STEP_UP_MFA" | "LEVEL_4_AUTO_HOLD" | "LEVEL_5_TERMINATE";
/**
 * This interface was referenced by `SessionStartRequest`'s JSON-Schema
 * via the `definition` "RiskState".
 */
type RiskState = "SCORED" | "INSUFFICIENT_EVIDENCE" | "DEGRADED";
/**
 * This interface was referenced by `SessionStartRequest`'s JSON-Schema
 * via the `definition` "IdentityVerdict".
 */
type IdentityVerdict = "VERIFIED" | "INCONCLUSIVE" | "IMPERSONATION_HUMAN" | "IMPERSONATION_SYNTHETIC";
/**
 * This interface was referenced by `SessionStartRequest`'s JSON-Schema
 * via the `definition` "Trend".
 */
type Trend = "RISING" | "FALLING" | "STABLE";
/**
 * This interface was referenced by `SessionStartRequest`'s JSON-Schema
 * via the `definition` "ReasonSeverity".
 */
type ReasonSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
/**
 * This interface was referenced by `SessionStartRequest`'s JSON-Schema
 * via the `definition` "UnitInterval".
 */
type UnitInterval = number;

/**
 * Start a Decision Plane call session. Context §8.3.
 */
interface SessionStartRequest {
  schema: "sentinelvoice.SessionStartRequest/1";
  /**
   * Optional; generated by the Decision Plane if absent.
   */
  sessionId?: string;
  callerId: string;
  calleeId: string;
  channelProfile: ChannelProfile;
  scenarioId?: string;
}

/**
 * This interface was referenced by `ChallengeIssued`'s JSON-Schema
 * via the `definition` "ChannelProfile".
 */
type ChannelProfile = "PSTN_NARROWBAND" | "VOIP_WIDEBAND" | "WEBRTC_WIDEBAND";
/**
 * This interface was referenced by `ChallengeIssued`'s JSON-Schema
 * via the `definition` "InterventionLevel".
 */
type InterventionLevel =
  "LEVEL_1_SILENT" | "LEVEL_2_SOFT_NUDGE" | "LEVEL_3_STEP_UP_MFA" | "LEVEL_4_AUTO_HOLD" | "LEVEL_5_TERMINATE";
/**
 * This interface was referenced by `ChallengeIssued`'s JSON-Schema
 * via the `definition` "RiskState".
 */
type RiskState = "SCORED" | "INSUFFICIENT_EVIDENCE" | "DEGRADED";
/**
 * This interface was referenced by `ChallengeIssued`'s JSON-Schema
 * via the `definition` "IdentityVerdict".
 */
type IdentityVerdict = "VERIFIED" | "INCONCLUSIVE" | "IMPERSONATION_HUMAN" | "IMPERSONATION_SYNTHETIC";
/**
 * This interface was referenced by `ChallengeIssued`'s JSON-Schema
 * via the `definition` "Trend".
 */
type Trend = "RISING" | "FALLING" | "STABLE";
/**
 * This interface was referenced by `ChallengeIssued`'s JSON-Schema
 * via the `definition` "ReasonSeverity".
 */
type ReasonSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
/**
 * This interface was referenced by `ChallengeIssued`'s JSON-Schema
 * via the `definition` "UnitInterval".
 */
type UnitInterval = number;

/**
 * Server-authoritative liveness challenge presented to the caller. Context §8.3.
 */
interface ChallengeIssued {
  schema: "sentinelvoice.ChallengeIssued/1";
  sessionId: string;
  nonce: string;
  /**
   * Phonetically diverse challenge phrase, e.g. Amber Falcon 72.
   */
  phrase: string;
  expiresAtEpochMs: number;
}

/**
 * This interface was referenced by `ChallengeResult`'s JSON-Schema
 * via the `definition` "UnitInterval".
 */
type UnitInterval = number;
/**
 * This interface was referenced by `ChallengeResult`'s JSON-Schema
 * via the `definition` "ChannelProfile".
 */
type ChannelProfile = "PSTN_NARROWBAND" | "VOIP_WIDEBAND" | "WEBRTC_WIDEBAND";
/**
 * This interface was referenced by `ChallengeResult`'s JSON-Schema
 * via the `definition` "InterventionLevel".
 */
type InterventionLevel =
  "LEVEL_1_SILENT" | "LEVEL_2_SOFT_NUDGE" | "LEVEL_3_STEP_UP_MFA" | "LEVEL_4_AUTO_HOLD" | "LEVEL_5_TERMINATE";
/**
 * This interface was referenced by `ChallengeResult`'s JSON-Schema
 * via the `definition` "RiskState".
 */
type RiskState = "SCORED" | "INSUFFICIENT_EVIDENCE" | "DEGRADED";
/**
 * This interface was referenced by `ChallengeResult`'s JSON-Schema
 * via the `definition` "IdentityVerdict".
 */
type IdentityVerdict = "VERIFIED" | "INCONCLUSIVE" | "IMPERSONATION_HUMAN" | "IMPERSONATION_SYNTHETIC";
/**
 * This interface was referenced by `ChallengeResult`'s JSON-Schema
 * via the `definition` "Trend".
 */
type Trend = "RISING" | "FALLING" | "STABLE";
/**
 * This interface was referenced by `ChallengeResult`'s JSON-Schema
 * via the `definition` "ReasonSeverity".
 */
type ReasonSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

/**
 * Server-measured challenge evaluation. Context §8.3.
 */
interface ChallengeResult {
  schema: "sentinelvoice.ChallengeResult/1";
  sessionId: string;
  nonce: string;
  verdict: "PASS" | "FAIL_LATENCY" | "FAIL_CONTENT" | "FAIL_ACOUSTIC" | "TIMEOUT";
  latencyMs: number;
  contentOverlap: UnitInterval;
  acousticCosine: UnitInterval;
}

/**
 * This interface was referenced by `InterventionOverride`'s JSON-Schema
 * via the `definition` "InterventionLevel".
 */
type InterventionLevel =
  "LEVEL_1_SILENT" | "LEVEL_2_SOFT_NUDGE" | "LEVEL_3_STEP_UP_MFA" | "LEVEL_4_AUTO_HOLD" | "LEVEL_5_TERMINATE";
/**
 * This interface was referenced by `InterventionOverride`'s JSON-Schema
 * via the `definition` "ChannelProfile".
 */
type ChannelProfile = "PSTN_NARROWBAND" | "VOIP_WIDEBAND" | "WEBRTC_WIDEBAND";
/**
 * This interface was referenced by `InterventionOverride`'s JSON-Schema
 * via the `definition` "RiskState".
 */
type RiskState = "SCORED" | "INSUFFICIENT_EVIDENCE" | "DEGRADED";
/**
 * This interface was referenced by `InterventionOverride`'s JSON-Schema
 * via the `definition` "IdentityVerdict".
 */
type IdentityVerdict = "VERIFIED" | "INCONCLUSIVE" | "IMPERSONATION_HUMAN" | "IMPERSONATION_SYNTHETIC";
/**
 * This interface was referenced by `InterventionOverride`'s JSON-Schema
 * via the `definition` "Trend".
 */
type Trend = "RISING" | "FALLING" | "STABLE";
/**
 * This interface was referenced by `InterventionOverride`'s JSON-Schema
 * via the `definition` "ReasonSeverity".
 */
type ReasonSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
/**
 * This interface was referenced by `InterventionOverride`'s JSON-Schema
 * via the `definition` "UnitInterval".
 */
type UnitInterval = number;

/**
 * Analyst up/down-grade of intervention level. Audited event — never anonymous. Context §8.3.
 */
interface InterventionOverride {
  schema: "sentinelvoice.InterventionOverride/1";
  sessionId: string;
  /**
   * Authenticated analyst identity. Overrides must never be anonymous.
   */
  analystId: string;
  /**
   * Mandatory free-text justification (min 10 characters).
   */
  reason: string;
  targetLevel: InterventionLevel;
  previousLevel: InterventionLevel;
  tsEpochMs: number;
}

/**
 * This interface was referenced by `CrossChannelEvent`'s JSON-Schema
 * via the `definition` "ReasonSeverity".
 */
type ReasonSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
/**
 * This interface was referenced by `CrossChannelEvent`'s JSON-Schema
 * via the `definition` "ChannelProfile".
 */
type ChannelProfile = "PSTN_NARROWBAND" | "VOIP_WIDEBAND" | "WEBRTC_WIDEBAND";
/**
 * This interface was referenced by `CrossChannelEvent`'s JSON-Schema
 * via the `definition` "InterventionLevel".
 */
type InterventionLevel =
  "LEVEL_1_SILENT" | "LEVEL_2_SOFT_NUDGE" | "LEVEL_3_STEP_UP_MFA" | "LEVEL_4_AUTO_HOLD" | "LEVEL_5_TERMINATE";
/**
 * This interface was referenced by `CrossChannelEvent`'s JSON-Schema
 * via the `definition` "RiskState".
 */
type RiskState = "SCORED" | "INSUFFICIENT_EVIDENCE" | "DEGRADED";
/**
 * This interface was referenced by `CrossChannelEvent`'s JSON-Schema
 * via the `definition` "IdentityVerdict".
 */
type IdentityVerdict = "VERIFIED" | "INCONCLUSIVE" | "IMPERSONATION_HUMAN" | "IMPERSONATION_SYNTHETIC";
/**
 * This interface was referenced by `CrossChannelEvent`'s JSON-Schema
 * via the `definition` "Trend".
 */
type Trend = "RISING" | "FALLING" | "STABLE";
/**
 * This interface was referenced by `CrossChannelEvent`'s JSON-Schema
 * via the `definition` "UnitInterval".
 */
type UnitInterval = number;

/**
 * Email/SMS/auth/web precursor event correlated to a call target. Context §8.3.
 */
interface CrossChannelEvent {
  schema: "sentinelvoice.CrossChannelEvent/1";
  channel: "EMAIL" | "SMS" | "AUTH" | "WEB";
  targetEmployeeId: string;
  occurredAtEpochMs: number;
  severity: ReasonSeverity;
  /**
   * Sender, sending domain, URL, or number.
   */
  indicator: string;
  campaignId?: string;
  description: string;
}

