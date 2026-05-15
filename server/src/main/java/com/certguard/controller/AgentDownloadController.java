package com.certguard.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the agent JAR and version info — no auth required.
 *
 * When {@code app.agent.artifact-url-template} is set, download requests are
 * redirected (HTTP 302) to the resolved GHCR artifact URL. This allows PR 3 to
 * remove the classpath JAR without breaking existing clients: they follow the
 * redirect transparently.
 *
 * When the template is blank the controller falls back to streaming the JAR
 * directly from the classpath (pre-PR-3 behaviour, preserved for local dev and
 * integration tests).
 */
@Slf4j
@RestController
public class AgentDownloadController {

    @Value("${app.agent.artifact-url-template:}")
    private String agentArtifactUrlTemplate;

    @Value("${app.release-tag:latest}")
    private String appReleaseTag;

    @GetMapping("/agent/download")
    public ResponseEntity<byte[]> downloadAgent() throws IOException {
        if (agentArtifactUrlTemplate != null && !agentArtifactUrlTemplate.isBlank()) {
            String redirectUrl = String.format(agentArtifactUrlTemplate, appReleaseTag);
            log.info("Redirecting agent download to GHCR: {}", redirectUrl);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(redirectUrl))
                    .build();
        }

        // Classpath fallback — used during transition and in local/test environments
        ClassPathResource resource = new ClassPathResource("agent/certguard-agent.jar");
        if (!resource.exists()) {
            log.warn("Agent JAR not found at classpath:agent/certguard-agent.jar");
            return ResponseEntity.notFound().build();
        }
        byte[] content = resource.getInputStream().readAllBytes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"certguard-agent.jar\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.length)
                .body(content);
    }

    @GetMapping("/agent/version")
    public ResponseEntity<?> agentVersion() {
        if (agentArtifactUrlTemplate != null && !agentArtifactUrlTemplate.isBlank()) {
            String artifactUrl = String.format(agentArtifactUrlTemplate, appReleaseTag);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("version",          "1.0.0");
            body.put("available",        true);
            body.put("minServerVersion", "1.0.0");
            body.put("artifactUrl",      artifactUrl);
            return ResponseEntity.ok(body);
        }

        ClassPathResource resource = new ClassPathResource("agent/certguard-agent.jar");
        return ResponseEntity.ok(java.util.Map.of(
                "version",          "1.0.0",
                "available",        resource.exists(),
                "minServerVersion", "1.0.0"
        ));
    }
}
