package com.sentinelvoice.response;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class ResponsePlanActivatedEvent extends ApplicationEvent {

    private final UUID tenantId;
    private final UUID planId;
    private final int version;

    public ResponsePlanActivatedEvent(Object source, UUID tenantId, UUID planId, int version) {
        super(source);
        this.tenantId = tenantId;
        this.planId = planId;
        this.version = version;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID planId() {
        return planId;
    }

    public int version() {
        return version;
    }
}
