package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorroborationGateTest {

    private CorroborationGate gate;

    @BeforeEach
    void setUp() {
        SentinelProperties properties = FusionEngineTest.testProperties();
        gate = new CorroborationGate(properties);
    }

    @Test
    void blocksWhenOnlyAcousticFamiliesFire() {
        Map<EvidenceFamily, Double> scores = new EnumMap<>(EvidenceFamily.class);
        scores.put(EvidenceFamily.VOICE, 0.95);
        scores.put(EvidenceFamily.CHANNEL, 0.90);
        scores.put(EvidenceFamily.PROSODY, 0.90);
        // contextual all below threshold
        scores.put(EvidenceFamily.LINGUISTIC, 0.10);
        scores.put(EvidenceFamily.TRANSACTION, 0.10);
        scores.put(EvidenceFamily.RELATIONSHIP, 0.10);

        FusionResult.CorroborationDetail detail = gate.evaluate(scores);

        assertThat(detail.satisfied()).isFalse();
        assertThat(detail.familiesAboveThreshold())
                .contains(EvidenceFamily.VOICE, EvidenceFamily.CHANNEL, EvidenceFamily.PROSODY)
                .doesNotContain(EvidenceFamily.LINGUISTIC, EvidenceFamily.TRANSACTION, EvidenceFamily.RELATIONSHIP);
        assertThat(detail.independentFamiliesRequired()).isEqualTo(2);
    }

    @Test
    void blocksWhenOnlyContextualFamiliesFire() {
        Map<EvidenceFamily, Double> scores = new EnumMap<>(EvidenceFamily.class);
        scores.put(EvidenceFamily.VOICE, 0.10);
        scores.put(EvidenceFamily.CHANNEL, 0.10);
        scores.put(EvidenceFamily.PROSODY, 0.10);
        scores.put(EvidenceFamily.LINGUISTIC, 0.95);
        scores.put(EvidenceFamily.TRANSACTION, 0.90);
        scores.put(EvidenceFamily.RELATIONSHIP, 0.90);

        FusionResult.CorroborationDetail detail = gate.evaluate(scores);

        assertThat(detail.satisfied()).isFalse();
        assertThat(detail.familiesAboveThreshold())
                .contains(EvidenceFamily.LINGUISTIC, EvidenceFamily.TRANSACTION, EvidenceFamily.RELATIONSHIP)
                .doesNotContain(EvidenceFamily.VOICE, EvidenceFamily.CHANNEL, EvidenceFamily.PROSODY);
    }

    @Test
    void satisfiedWhenBothGroupsExceedThresholds() {
        Map<EvidenceFamily, Double> scores = new EnumMap<>(EvidenceFamily.class);
        scores.put(EvidenceFamily.VOICE, 0.70);
        scores.put(EvidenceFamily.LINGUISTIC, 0.80);

        FusionResult.CorroborationDetail detail = gate.evaluate(scores);

        assertThat(detail.satisfied()).isTrue();
        assertThat(detail.familiesAboveThreshold())
                .containsExactlyInAnyOrder(EvidenceFamily.VOICE, EvidenceFamily.LINGUISTIC);
    }
}
