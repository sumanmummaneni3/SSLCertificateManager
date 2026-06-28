package com.certguard.agent.scanner;

/**
 * Device classification result for a discovered host (RFC 0011 §4.3).
 * Mirrors the server-side enum; kept separate to avoid agent depending on server classes.
 */
public enum DeviceClass {
    ROUTER, SWITCH, SERVER, WORKSTATION, UNKNOWN
}
