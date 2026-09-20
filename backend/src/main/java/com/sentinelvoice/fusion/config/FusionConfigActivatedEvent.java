package com.sentinelvoice.fusion.config;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/** Fired when a fusion config becomes ACTIVE so the runtime cache can reload. */
public class FusionConfigActivatedEvent extends ApplicationEvent {

    private final UUID tenantId;
    private final UUID configId;
    private final int version;

    public FusionConfigActivatedEvent(Object source, UUID tenantId, UUID configId, int version) {
        super(source);
        this.tenantId = tenantId;
        this.configId = configId;
        this.version = version;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID configId() {
        return configId;
    }

    public int version() {
        return version;
    }
}
