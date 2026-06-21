package com.certguard.event;

import com.certguard.enums.RevocationStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * v1 subscriber for {@link CertRevocationTransitionEvent} (RFC 0009 §4.4 / BE-10).
 *
 * <p>Emits a structured WARN log and increments Micrometer counters.
 * Future: add an {@code @TransactionalEventListener(phase = AFTER_COMMIT)} here (or
 * alongside) to persist to {@code certificate_revocation_events} without modifying producers.
 */
@Component
public class RevocationTransitionEventListener {

    private static final Logger log = LoggerFactory.getLogger(RevocationTransitionEventListener.class);

    private final MeterRegistry meterRegistry;

    public RevocationTransitionEventListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @EventListener
    public void onRevocationTransition(CertRevocationTransitionEvent event) {
        // Structured WARN log on every transition.
        if (event.newRevocationStatus() == RevocationStatus.REVOKED) {
            log.warn("REVOCATION TRANSITION: certId={} orgId={} {} → {} reason={} source={} deepCheck={}",
                    event.certId(), event.orgId(),
                    event.previousRevocationStatus(), event.newRevocationStatus(),
                    event.reason(), event.source(), event.deepCheck());
        } else {
            log.info("Revocation transition: certId={} orgId={} {} → {} reason={} source={}",
                    event.certId(), event.orgId(),
                    event.previousRevocationStatus(), event.newRevocationStatus(),
                    event.reason(), event.source());
        }

        // Micrometer counter.
        try {
            String reason = event.reason() != null ? event.reason() : "NONE";
            meterRegistry.counter("certguard.revocation.transition.total",
                    "previous", String.valueOf(event.previousRevocationStatus()),
                    "current",  String.valueOf(event.newRevocationStatus()),
                    "reason",   reason).increment();
        } catch (Exception ignored) { /* metrics never crash the event path */ }
    }
}
