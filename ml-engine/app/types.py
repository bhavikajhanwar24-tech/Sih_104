"""Pydantic models mirroring the frozen FeatureFrame contract (docs/contracts)."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Optional

import numpy as np
from pydantic import BaseModel, ConfigDict, Field


class ChannelProfile(str, Enum):
    PSTN_NARROWBAND = "PSTN_NARROWBAND"
    VOIP_WIDEBAND = "VOIP_WIDEBAND"
    WEBRTC_WIDEBAND = "WEBRTC_WIDEBAND"


class VoiceFamily(BaseModel):
    model_config = ConfigDict(extra="forbid")

    available: bool
    spoofProbability: Optional[float] = Field(default=None, ge=0, le=1)
    modelId: Optional[str] = None
    confidence: Optional[float] = Field(default=None, ge=0, le=1)


class ChannelFamily(BaseModel):
    model_config = ConfigDict(extra="forbid")

    available: bool
    rirT60Ms: Optional[float] = None
    rirPlausible: Optional[bool] = None
    doubleCompressionScore: Optional[float] = Field(default=None, ge=0, le=1)
    noiseFloorStationarity: Optional[float] = Field(default=None, ge=0, le=1)
    dcOffset: Optional[float] = None


class ProsodyFamily(BaseModel):
    model_config = ConfigDict(extra="forbid")

    available: bool
    f0MeanHz: Optional[float] = None
    f0StdHz: Optional[float] = None
    jitterLocalPct: Optional[float] = None
    shimmerLocalPct: Optional[float] = None
    hnrDb: Optional[float] = None
    breathEventsPerMin: Optional[float] = None
    disfluencyRate: Optional[float] = None
    articulationRateSylSec: Optional[float] = None
    unnaturalnessScore: Optional[float] = Field(default=None, ge=0, le=1)


class SpeakerFamily(BaseModel):
    model_config = ConfigDict(extra="forbid")

    available: bool
    embeddingId: Optional[str] = None
    enrolledProfileId: Optional[str] = None
    cosineSimilarity: Optional[float] = Field(default=None, ge=0, le=1)
    intraCallDrift: Optional[float] = Field(default=None, ge=0, le=1)


class WatermarkFamily(BaseModel):
    model_config = ConfigDict(extra="forbid")

    available: bool
    detector: Optional[str] = None
    detected: Optional[bool] = None
    provider: Optional[str] = None
    confidence: Optional[float] = Field(default=None, ge=0, le=1)


class Ask(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: str
    amount: float
    currency: str
    beneficiaryHint: str
    deadline: str


class LinguisticFamily(BaseModel):
    model_config = ConfigDict(extra="forbid")

    available: bool
    ageMs: Optional[int] = Field(default=None, ge=0)
    language: Optional[str] = None
    urgency: Optional[float] = Field(default=None, ge=0, le=1)
    secrecy: Optional[float] = Field(default=None, ge=0, le=1)
    authorityInvocation: Optional[float] = Field(default=None, ge=0, le=1)
    emotionalCoercion: Optional[float] = Field(default=None, ge=0, le=1)
    askDetected: Optional[bool] = None
    ask: Optional[Ask] = None
    claimedIdentity: Optional[str] = None
    claimedRole: Optional[str] = None
    redactedSnippet: Optional[str] = None


class LatencyMs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    fastPath: float = Field(ge=0)
    slowPath: float = Field(ge=0)


class FeatureFrame(BaseModel):
    """Frozen contract sentinelvoice.FeatureFrame/1. No audio."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    schema_name: str = Field(default="sentinelvoice.FeatureFrame/1", alias="schema")
    sessionId: str = Field(min_length=1)
    seq: int = Field(ge=0)
    windowStartMs: int = Field(ge=0)
    windowEndMs: int = Field(ge=0)
    channelProfile: ChannelProfile
    speechPresent: bool
    cumulativeSpeechMs: int = Field(ge=0)
    voice: VoiceFamily
    channel: ChannelFamily
    prosody: ProsodyFamily
    speaker: SpeakerFamily
    watermark: WatermarkFamily
    linguistic: LinguisticFamily
    latencyMs: LatencyMs


class IngestHello(BaseModel):
    """First WS text frame from a media source. Context §7.1."""

    model_config = ConfigDict(extra="forbid")

    sessionId: str
    sampleRate: int = 16000
    encoding: str = "pcm_s16le"
    channel: Optional[ChannelProfile] = None
    channels: int = 1
    source: Optional[str] = None


@dataclass
class AudioFrame:
    session_id: str
    seq: int
    pcm: np.ndarray
    profile: ChannelProfile
    received_at_ms: int
