package com.sentinelvoice.policy.engine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime rule engine — evaluates ACCEPTED/EDITED rules from the ACTIVE set only (F7).
 */
@Service
public class RuleEngine {

    private final ActivePolicyCache cache;
    private final PolicyEngineProperties props;
    private final Timer evaluationTimer;
    private final LongAdder evalCount = new LongAdder();
    private final LongAdder evalMicrosTotal = new LongAdder();
    private final AtomicLong lastEvalMicros = new AtomicLong();

    public RuleEngine(ActivePolicyCache cache, PolicyEngineProperties props, MeterRegistry registry) {
        this.cache = cache;
        this.props = props;
        this.evaluationTimer = Timer.builder("sentinelvoice.policy.engine.evaluation")
                .description("Policy rule evaluation latency")
                .register(registry);
    }

    public RuleEvaluation evaluate(UUID tenantId, FactSet facts) {
        return evaluate(tenantId, facts, false, null);
    }

    public RuleEvaluation evaluate(UUID tenantId, FactSet facts, boolean simulation) {
        return evaluate(tenantId, facts, simulation, null);
    }

    /**
     * @param policySetOverride when non-null (simulate), load that set's ACCEPTED/EDITED rules
     *                          instead of ACTIVE. Pass null for live / active simulation.
     */
    public RuleEvaluation evaluate(
            UUID tenantId,
            FactSet facts,
            boolean simulation,
            CompiledPolicy policyOverride
    ) {
        long start = System.nanoTime();
        try {
            Optional<CompiledPolicy> policyOpt = policyOverride != null
                    ? Optional.of(policyOverride)
                    : cache.get(tenantId);
            if (policyOpt.isEmpty()) {
                long micros = elapsedMicros(start);
                record(micros);
                return RuleEvaluation.noPolicy(micros, simulation);
            }
            CompiledPolicy policy = policyOpt.get();
            FactSet safeFacts = facts == null ? FactSet.empty() : facts;
            String actionType = stringFact(safeFacts, "ask.type");
            List<CompiledPolicy.CompiledRule> candidates = policy.rulesForAction(actionType);

            List<RuleEvaluation.FiredRule> fired = new ArrayList<>();
            List<RuleEvaluation.UndeterminedRule> undetermined = new ArrayList<>();
            Set<String> allUnknown = new LinkedHashSet<>();
            int minLevel = 0;
            double scoreAcc = 0.0;
            double scoreMax = 0.0;

            for (CompiledPolicy.CompiledRule rule : candidates) {
                ConditionEvaluator.EvalResult result =
                        ConditionEvaluator.evaluate(rule.condition(), safeFacts);
                if (result.value() == TriBool.TRUE) {
                    fired.add(new RuleEvaluation.FiredRule(
                            rule.ruleId(),
                            rule.title(),
                            rule.severity(),
                            rule.reasonCode(),
                            rule.sourceRef(),
                            rule.minLevel(),
                            rule.scoreBoost()
                    ));
                    minLevel = Math.max(minLevel, rule.minLevel());
                    scoreAcc += rule.scoreBoost();
                    scoreMax = Math.max(scoreMax, rule.scoreBoost());
                } else if (result.value() == TriBool.UNKNOWN) {
                    List<String> missing = result.unknownFacts().stream().sorted().toList();
                    allUnknown.addAll(missing);
                    undetermined.add(new RuleEvaluation.UndeterminedRule(
                            rule.ruleId(),
                            rule.title(),
                            missing
                    ));
                }
            }

            double policyScore = props.useSumCap()
                    ? Math.min(props.scoreCap(), scoreAcc)
                    : Math.min(1.0, scoreMax);

            long micros = elapsedMicros(start);
            record(micros);
            return new RuleEvaluation(
                    RuleEvaluation.STATE_OK,
                    List.copyOf(fired),
                    List.copyOf(undetermined),
                    minLevel,
                    policyScore,
                    List.copyOf(allUnknown),
                    micros,
                    policy.version(),
                    policy.contentSha(),
                    simulation
            );
        } finally {
            evaluationTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    public Optional<CompiledPolicy> activePolicy(UUID tenantId) {
        return cache.get(tenantId);
    }

    public void invalidate(UUID tenantId) {
        cache.invalidate(tenantId);
    }

    public long avgEvalMicros() {
        long n = evalCount.sum();
        if (n == 0) {
            return 0L;
        }
        return evalMicrosTotal.sum() / n;
    }

    public long lastEvalMicros() {
        return lastEvalMicros.get();
    }

    private void record(long micros) {
        evalCount.increment();
        evalMicrosTotal.add(micros);
        lastEvalMicros.set(micros);
    }

    private static long elapsedMicros(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000L);
    }

    private static String stringFact(FactSet facts, String path) {
        Object v = facts.raw(path);
        return v == null ? null : String.valueOf(v);
    }
}
