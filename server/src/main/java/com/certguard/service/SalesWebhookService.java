package com.certguard.service;

import com.certguard.config.SalesWebhookProperties;
import com.certguard.dto.webhook.SalesWebhookPayload;
import com.certguard.entity.Organization;
import com.certguard.entity.Subscription;
import com.certguard.enums.SalesWebhookEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalesWebhookService {

    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private final SalesWebhookProperties props;

    @Async
    public void fire(SalesWebhookEventType type,
                     Organization org,
                     Subscription sub,
                     String actorEmail,
                     Map<String, Object> context) {
        if (props.url() == null || props.url().isBlank()) {
            log.debug("Webhook not configured (app.sales.webhook.url is blank) — skipping event {}", type);
            return;
        }

        SalesWebhookPayload payload = buildPayload(type, org, sub, actorEmail, context);

        RestClient client = RestClient.builder()
                .baseUrl(props.url())
                .build();

        for (int attempt = 0; attempt <= props.maxRetries(); attempt++) {
            try {
                String body = objectMapper.writeValueAsString(payload);
                String signature = (props.secret() != null && !props.secret().isBlank())
                        ? computeSignature(body)
                        : null;

                var spec = client.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

                if (signature != null) {
                    spec = spec.header("X-CertGuard-Signature", "sha256=" + signature);
                }

                spec.retrieve()
                        .toBodilessEntity();

                log.info("Webhook delivered — event={}, org={}, attempt={}", type, org.getId(), attempt + 1);
                return;

            } catch (Exception ex) {
                if (attempt < props.maxRetries()) {
                    long delayMs = (long) Math.pow(2, attempt) * 1000;
                    log.warn("Webhook attempt {}/{} failed for {}: {}. Retrying in {}ms",
                            attempt + 1, props.maxRetries() + 1, type, ex.getMessage(), delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    log.error("Webhook delivery failed after {} attempts for event {}, orgId {}",
                            props.maxRetries() + 1, type, org.getId());
                }
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private SalesWebhookPayload buildPayload(SalesWebhookEventType type,
                                              Organization org,
                                              Subscription sub,
                                              String actorEmail,
                                              Map<String, Object> context) {
        return SalesWebhookPayload.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(type.name())
                .timestamp(Instant.now())
                .org(SalesWebhookPayload.OrgRef.builder()
                        .id(org.getId().toString())
                        .name(org.getName())
                        .orgType(org.getOrgType() != null ? org.getOrgType().name() : null)
                        .contactEmail(org.getContactEmail())
                        .createdAt(org.getCreatedAt())
                        .build())
                .subscription(SalesWebhookPayload.SubscriptionRef.builder()
                        .status(sub != null && sub.getStatus() != null ? sub.getStatus().name() : null)
                        .maxCertificateQuota(sub != null ? sub.getMaxCertificateQuota() : 0)
                        .build())
                .actor(SalesWebhookPayload.ActorRef.builder()
                        .email(actorEmail)
                        .build())
                .context(context)
                .build();
    }

    /**
     * Computes HMAC-SHA256 over the serialised payload body using the configured secret.
     * Returns a lowercase hex string.
     */
    private String computeSignature(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception ex) {
            log.warn("Failed to compute webhook HMAC signature: {}", ex.getMessage());
            return "";
        }
    }
}
