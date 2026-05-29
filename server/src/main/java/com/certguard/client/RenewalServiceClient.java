package com.certguard.client;

import com.certguard.dto.internal.InternalCreateRenewalRequest;
import com.certguard.dto.internal.InternalDeliveryEventRequest;
import com.certguard.dto.response.RenewalResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for certguard-renewal-service internal API.
 * All requests carry "Authorization: Bearer <internal-service-token>".
 */
@Slf4j
@Component
public class RenewalServiceClient {

    private final RestClient restClient;

    public RenewalServiceClient(
            @Value("${app.renewal-service.url:http://renewal-service:8444}") String baseUrl,
            @Value("${app.internal.service-token:}") String serviceToken) {

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + serviceToken)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ── Renewal lifecycle ────────────────────────────────────────────────────

    public RenewalResponse createRenewal(InternalCreateRenewalRequest req) {
        return restClient.post()
                .uri("/internal/v1/renewals")
                .body(req)
                .retrieve()
                .body(RenewalResponse.class);
    }

    public RenewalResponse getRenewal(UUID orgId, UUID renewalId) {
        return restClient.get()
                .uri("/internal/v1/renewals/{id}?orgId={orgId}", renewalId, orgId)
                .retrieve()
                .body(RenewalResponse.class);
    }

    public List<RenewalResponse> listRenewals(UUID orgId, UUID certId) {
        return restClient.get()
                .uri("/internal/v1/renewals?orgId={orgId}&certId={certId}", orgId, certId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public RenewalResponse cancelRenewal(UUID orgId, UUID renewalId) {
        return restClient.post()
                .uri("/internal/v1/renewals/{id}/cancel?orgId={orgId}", renewalId, orgId)
                .retrieve()
                .body(RenewalResponse.class);
    }

    // ── CSR submission (from agent) ──────────────────────────────────────────

    public void submitCsr(UUID renewalId, String csrPem) {
        restClient.post()
                .uri("/internal/v1/renewals/{id}/csr", renewalId)
                .body(Map.of("csrPem", csrPem))
                .retrieve()
                .toBodilessEntity();
    }

    // ── Package streaming (for agent download) ───────────────────────────────

    public InputStream streamPackage(UUID renewalId) {
        return restClient.get()
                .uri("/internal/v1/renewals/{id}/package", renewalId)
                .retrieve()
                .body(InputStream.class);
    }

    // ── Provider list (for UI) ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> listProviders() {
        return restClient.get()
                .uri("/internal/v1/providers")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    // ── Delivery callbacks (from AgentJobService) ────────────────────────────

    public void notifyDeliveryCompleted(UUID renewalId) {
        try {
            restClient.post()
                    .uri("/internal/v1/renewals/{id}/delivery-completed", renewalId)
                    .body(new InternalDeliveryEventRequest(null))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to notify renewal service of delivery completion for renewalId: {}",
                    renewalId, e);
        }
    }

    public void notifyDeliveryFailed(UUID renewalId, String errorDetail) {
        try {
            restClient.post()
                    .uri("/internal/v1/renewals/{id}/delivery-failed", renewalId)
                    .body(new InternalDeliveryEventRequest(errorDetail))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to notify renewal service of delivery failure for renewalId: {}",
                    renewalId, e);
        }
    }
}
