package com.certguard.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Serves the agent JAR and version info — no auth required.
 * Place certguard-agent.jar in src/main/resources/agent/
 */
@Slf4j
@RestController
public class AgentDownloadController {

    @GetMapping("/agent/download")
    public ResponseEntity<byte[]> downloadAgent() throws IOException {
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
        ClassPathResource resource = new ClassPathResource("agent/certguard-agent.jar");
        return ResponseEntity.ok(java.util.Map.of(
                "version",          "1.0.0",
                "available",        resource.exists(),
                "minServerVersion", "1.0.0"
        ));
    }
}
