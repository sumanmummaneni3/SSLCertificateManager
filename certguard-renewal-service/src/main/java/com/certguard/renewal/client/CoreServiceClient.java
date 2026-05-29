package com.certguard.renewal.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for certguard core server internal API.
 * Used to enqueue agent jobs and trigger notification emails.
 */
@Slf4j
@Component
public class CoreServiceClient {

    private final RestClient restClient;

    public CoreServiceClient(
            @Value("${app.core-service.url:https://app:8443}") String baseUrl,
            @Value("${app.internal.service-token:}") String serviceToken) {

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + serviceToken)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public UUID enqueueCsrJob(UUID renewalId, UUID agentId, UUID orgId, UUID targetId,
                               String commonName, List<String> sans) {
        Map<String, Object> body = Map.of(
                "agentId", agentId,
                "orgId", orgId,
                "targetId", targetId,
                "renewalId", renewalId,
                "jobType", "CERT_RENEW_CSR",
                "payload", Map.of("commonName", commonName, "sans", sans),
                "dedupKey", "CERT_RENEW_CSR:" + renewalId
        );

        var response = restClient.post()
                .uri("/internal/v1/agent-jobs")
                .body(body)
                .retrieve()
                .body(Map.class);

        return UUID.fromString((String) response.get("jobId"));
    }

    public UUID enqueueCertDelivery(UUID renewalId, UUID agentId, UUID orgId, UUID targetId,
                                     String targetInstallPath, UUID packageId,
                                     String checksumSha256, String fileName) {
        Map<String, Object> body = Map.of(
                "agentId", agentId,
                "orgId", orgId,
                "targetId", targetId,
                "renewalId", renewalId,
                "jobType", "CERT_DELIVERY",
                "payload", Map.of(
                        "packageId", packageId.toString(),
                        "targetLocation", targetInstallPath != null ? targetInstallPath : "",
                        "checksumSha256", checksumSha256,
                        "fileName", fileName
                ),
                "dedupKey", "CERT_DELIVERY:" + targetId + ":" + packageId
        );

        var response = restClient.post()
                .uri("/internal/v1/agent-jobs")
                .body(body)
                .retrieve()
                .body(Map.class);

        return UUID.fromString((String) response.get("jobId"));
    }

    public void triggerNotification(String eventType, UUID renewalId, UUID orgId,
                                     UUID requestedBy, String targetInstallPath,
                                     String fileName, String checksumSha256, String errorDetail) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("eventType", eventType);
        body.put("renewalId", renewalId.toString());
        body.put("orgId", orgId.toString());
        if (requestedBy != null) body.put("requestedBy", requestedBy.toString());
        if (targetInstallPath != null) body.put("targetInstallPath", targetInstallPath);
        if (fileName != null) body.put("fileName", fileName);
        if (checksumSha256 != null) body.put("checksumSha256", checksumSha256);
        if (errorDetail != null) body.put("errorDetail", errorDetail);

        try {
            restClient.post()
                    .uri("/internal/v1/notifications/renewal")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to trigger {} notification for renewalId: {}", eventType, renewalId, e);
        }
    }
}
