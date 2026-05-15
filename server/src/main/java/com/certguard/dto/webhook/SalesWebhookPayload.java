package com.certguard.dto.webhook;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class SalesWebhookPayload {

    private String eventId;
    private String eventType;
    private Instant timestamp;
    private OrgRef org;
    private SubscriptionRef subscription;
    private ActorRef actor;
    private Map<String, Object> context;

    @Data
    @Builder
    public static class OrgRef {
        private String id;
        private String name;
        private String orgType;
        private String contactEmail;
        private Instant createdAt;
    }

    @Data
    @Builder
    public static class SubscriptionRef {
        private String status;
        private int maxCertificateQuota;
    }

    @Data
    @Builder
    public static class ActorRef {
        private String userId;
        private String email;
    }
}
