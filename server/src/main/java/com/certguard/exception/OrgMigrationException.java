package com.certguard.exception;

import java.util.UUID;

/**
 * Typed 409 exception for MSP→MSP org migration failures (RFC 0010 §4).
 *
 * Each subclass carries a problem-type slug that maps to a typed ProblemDetail URI
 * in {@link GlobalExceptionHandler}.
 */
public class OrgMigrationException extends RuntimeException {

    private final String problemType;

    public OrgMigrationException(String problemType, String message) {
        super(message);
        this.problemType = problemType;
    }

    public String getProblemType() { return problemType; }

    // ── Named factory methods (match RFC 0010 §4 error table) ─────────────────

    /** The org is not a client org (no parent, or is itself an MSP). */
    public static OrgMigrationException orgNotTransferable(UUID orgId) {
        return new OrgMigrationException(
                "org-not-transferable",
                "Organisation " + orgId + " is not a transferable client org (must have a parent MSP)");
    }

    /** expectedSourceMspId does not match the current parent — stale-email guard. */
    public static OrgMigrationException sourceMspMismatch(UUID expected, UUID actual) {
        return new OrgMigrationException(
                "source-msp-mismatch",
                "Expected source MSP " + expected + " but org currently belongs to " + actual +
                " — it may have been moved already");
    }

    /** Target MSP is the same as the current parent — no-op. */
    public static OrgMigrationException noOpTransfer(UUID targetMspId) {
        return new OrgMigrationException(
                "no-op-transfer",
                "Org is already under MSP " + targetMspId + " — nothing to transfer");
    }

    /**
     * Undo guard: org has moved again since the FORWARD record was written,
     * so the undo would restore it to a stale parent.
     */
    public static OrgMigrationException undoStale(UUID migrationId) {
        return new OrgMigrationException(
                "undo-stale",
                "Cannot undo migration " + migrationId +
                " — the org has moved again since that transfer; current parent no longer matches");
    }
}
