package com.certguard.enums;

/**
 * Lifecycle status of a {@link com.certguard.entity.NotificationOutbox} row (RFC 0008 / R19 fix).
 *
 * <p>Stored as plain {@code TEXT} (see V43) rather than a Postgres ENUM type — small,
 * closed set that only the application interprets.
 */
public enum OutboxStatus {
    /** Not yet sent; eligible for drain once {@code next_attempt_at} is due. */
    PENDING,
    /** Delivered successfully (or logged in dev-mode). Terminal. */
    SENT,
    /** Attempt cap exhausted without a successful send. Terminal — queryable for alerting/ops. */
    FAILED
}
