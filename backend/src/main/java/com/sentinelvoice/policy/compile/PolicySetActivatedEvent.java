package com.sentinelvoice.policy.compile;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/** Fired when a policy set becomes ACTIVE so F7 caches can reload. */
public class PolicySetActivatedEvent extends ApplicationEvent {

    private final UUID tenantId;
    private final UUID policySetId;
    private final int version;

    public PolicySetActivatedEvent(Object source, UUID tenantId, UUID policySetId, int version) {
        super(source);
        this.tenantId = tenantId;
        this.policySetId = policySetId;
        this.version = version;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID policySetId() {
        return policySetId;
    }

    public int version() {
        return version;
    }
}
