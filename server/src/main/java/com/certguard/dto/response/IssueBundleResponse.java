package com.certguard.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class IssueBundleResponse {

    private UUID agentId;

    /**
     * Plaintext install key — shown exactly once in the UI.
     * The server stores only the BCrypt hash; this field is NOT persisted.
     */
    private String installKey;

    /** Signed one-time URL for downloading the bundle ZIP. */
    private String bundleDownloadUrl;

    /** When the download token expires (and the bundle becomes unavailable). */
    private Instant expiresAt;
}
