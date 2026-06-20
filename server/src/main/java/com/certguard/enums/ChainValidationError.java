package com.certguard.enums;

/**
 * Structured chain-validation failure codes (RFC 0009 §5.1).
 *
 * Stored as text in {@code certificate_records.chain_validation_error}.
 * Null when the chain is trusted. The {@code CHAIN_ERROR} catch-all uses
 * a formatted string {@code CHAIN_ERROR:<reason>} in the DB but maps to
 * this enum value in Java (the {@code :reason} suffix is stripped by the
 * mapping layer).
 */
public enum ChainValidationError {
    /** Root cert not present in the configured trust store. */
    UNTRUSTED_ANCHOR,
    /** Chain is incomplete and cannot be completed via AIA. */
    INCOMPLETE_CHAIN,
    /** Leaf cert is self-signed (subject == issuer, no separate CA). */
    SELF_SIGNED,
    /** An intermediate or root within the path has expired. */
    EXPIRED_CHAIN_ELEMENT,
    /** basicConstraints pathLenConstraint violated. */
    PATH_LEN_VIOLATION,
    /** Issuer nameConstraints excludes the leaf SAN. */
    NAME_CONSTRAINT_VIOLATION,
    /** Leaf not a CA but used as issuer, or CA bit missing on intermediate. */
    BASIC_CONSTRAINT_VIOLATION,
    /** A signature in the chain does not verify (tampered/mismatched). */
    SIGNATURE_INVALID,
    /** Disabled weak algorithm (e.g. MD5/SHA-1) in JDK certpath policy. */
    WEAK_ALGORITHM,
    /** Catch-all for unexpected CertPathValidatorException subtypes. */
    CHAIN_ERROR
}
