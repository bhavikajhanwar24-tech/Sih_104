package com.sentinelvoice.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Frozen contract {@code sentinelvoice.FeatureFrame/1}. Inference → Decision. No audio.
 * Nested records mirror {@code docs/contracts/FeatureFrame.schema.json} field-for-field.
 */
public record FeatureFrame(
        @JsonProperty("schema") String schema,
        String sessionId,
        int seq,
        long windowStartMs,
        long windowEndMs,
        ChannelProfile channelProfile,
        boolean speechPresent,
        long cumulativeSpeechMs,
        VoiceFamily voice,
        ChannelFamily channel,
        ProsodyFamily prosody,
        SpeakerFamily speaker,
        WatermarkFamily watermark,
        LinguisticFamily linguistic,
        LatencyMs latencyMs
) {
    public record VoiceFamily(
            boolean available,
            Double spoofProbability,
            String modelId,
            Double confidence
    ) {
    }

    public record ChannelFamily(
            boolean available,
            Double rirT60Ms,
            Boolean rirPlausible,
            Double doubleCompressionScore,
            Double noiseFloorStationarity,
            Double dcOffset
    ) {
    }

    public record ProsodyFamily(
            boolean available,
            Double f0MeanHz,
            Double f0StdHz,
            Double jitterLocalPct,
            Double shimmerLocalPct,
            Double hnrDb,
            Double breathEventsPerMin,
            Double disfluencyRate,
            Double articulationRateSylSec,
            Double unnaturalnessScore
    ) {
    }

    public record SpeakerFamily(
            boolean available,
            String embeddingId,
            String enrolledProfileId,
            Double cosineSimilarity,
            Double intraCallDrift
    ) {
    }

    public record WatermarkFamily(
            Boolean available,
            String detector,
            Boolean detected,
            String provider,
            Double confidence
    ) {
    }

    public record LatencyMs(
            double fastPath,
            double slowPath
    ) {
    }
}
