package com.certguard.agent.config;

/**
 * Agent operating mode (RFC 0011 §6.1).
 *
 * AUTHENTICATED — registered with an org, uses mTLS + HMAC.  Full feature set.
 * ANONYMOUS     — no registration, single-use session, uses X-Anon-Scan-Token.
 *                 Performs NIC discovery + CIDR sweep then exits.
 */
public enum AgentMode {
    AUTHENTICATED,
    ANONYMOUS
}
