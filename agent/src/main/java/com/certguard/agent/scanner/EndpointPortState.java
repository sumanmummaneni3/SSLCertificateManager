package com.certguard.agent.scanner;

/**
 * State of a discovered port (RFC 0011 §3.2).
 * Mirrors the server-side enum of the same name; kept separate to avoid
 * the agent depending on server classes.
 */
public enum EndpointPortState {
    OPEN_TLS,
    OPEN_NO_TLS,
    CLOSED_OR_FILTERED
}
