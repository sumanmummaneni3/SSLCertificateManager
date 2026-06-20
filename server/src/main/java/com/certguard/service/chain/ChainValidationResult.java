package com.certguard.service.chain;

import com.certguard.enums.ChainValidationError;

/**
 * Immutable result of {@link ChainValidationService#validate(java.security.cert.X509Certificate[])}.
 *
 * Public/private leniency (INVALID vs advisory) is applied by the caller
 * (ExpiryEvaluationService) based on whether the target is private.
 */
public record ChainValidationResult(
        boolean trusted,
        ChainValidationError error,
        String errorDetail,   // free-form extra context (e.g. for CHAIN_ERROR)
        int chainDepth
) {
    public static ChainValidationResult trusted(int depth) {
        return new ChainValidationResult(true, null, null, depth);
    }

    public static ChainValidationResult failed(ChainValidationError error, int depth) {
        return new ChainValidationResult(false, error, null, depth);
    }

    public static ChainValidationResult failed(ChainValidationError error, String detail, int depth) {
        return new ChainValidationResult(false, error, detail, depth);
    }

    /** True iff the chain is a single self-signed leaf. */
    public boolean isSelfSigned() {
        return error == ChainValidationError.SELF_SIGNED;
    }
}
