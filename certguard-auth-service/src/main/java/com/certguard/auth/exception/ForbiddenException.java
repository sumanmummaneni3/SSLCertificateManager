package com.certguard.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * RFC 0015 Phase 2 — raised by switch-org for non-member, revoked-membership,
 * revoked-session, and platform-admin-attempting-switch-org cases.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
