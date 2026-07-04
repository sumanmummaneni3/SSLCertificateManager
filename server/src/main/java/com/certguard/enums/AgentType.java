package com.certguard.enums;

/**
 * Distinguishes customer-deployed agents from platform-operated scanner agents.
 *
 * CUSTOMER        — deployed on-prem by a CertGuard customer to scan private targets.
 *                   Claims AGENT_PINNED jobs that belong to its assigned targets.
 * PLATFORM_SCANNER — operated by CertGuard to scan public targets on behalf of all orgs.
 *                   Claims PUBLIC_POOL jobs; belongs to the reserved platform org.
 *
 * RFC 0013 §1.
 */
public enum AgentType {
    CUSTOMER,
    PLATFORM_SCANNER
}
