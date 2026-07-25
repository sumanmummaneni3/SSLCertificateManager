package com.certguard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * RFC 0015 Phase 3 — one-shot, property-gated entry point for
 * {@link BackfillLastActiveOrgService}.
 *
 * <p>This is deliberately NOT a scheduled job: it runs at most once per process start,
 * and only when explicitly enabled via {@code app.backfill.last-active-org.mode}:
 * <ul>
 *   <li>{@code off} (default) — does nothing. Must never run automatically in a normal
 *       deploy.</li>
 *   <li>{@code dry-run} — identifies and logs exactly which users would be affected;
 *       makes zero writes.</li>
 *   <li>{@code apply} — performs the backfill and logs what was actually changed.</li>
 * </ul>
 *
 * <p>Operators should set the property for a single deploy/restart to run the backfill,
 * then unset it (or leave it, since {@code apply} is idempotent — a second run affects 0
 * rows) before the next normal deploy.
 *
 * <p>A failure in {@code dry-run}/{@code apply} is caught and logged at ERROR rather than
 * propagated: a Spring {@link ApplicationRunner} that throws aborts context startup, and a
 * transient DB error during the one deploy where this actually runs must degrade to "not
 * applied, retryable next deploy" — not crash-loop the server.
 */
@Component
public class LastActiveOrgBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LastActiveOrgBackfillRunner.class);

    private final BackfillLastActiveOrgService backfillService;

    @Value("${app.backfill.last-active-org.mode:off}")
    private String mode;

    public LastActiveOrgBackfillRunner(BackfillLastActiveOrgService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String normalizedMode = mode == null ? "off" : mode.trim().toLowerCase(Locale.ROOT);
        switch (normalizedMode) {
            case "off" -> log.debug("RFC 0015 Phase 3 last-active-org backfill: mode=off, skipping");
            case "dry-run" -> {
                log.info("RFC 0015 Phase 3 last-active-org backfill: starting DRY-RUN (no writes)");
                try {
                    List<BackfillLastActiveOrgService.BackfillRow> rows = backfillService.dryRun();
                    log.info("RFC 0015 Phase 3 last-active-org backfill: DRY-RUN complete, {} user(s) would be affected",
                            rows.size());
                } catch (Exception e) {
                    log.error("RFC 0015 Phase 3 last-active-org backfill: mode=dry-run failed — backfill skipped, "
                            + "retry on the next deploy", e);
                }
            }
            case "apply" -> {
                log.info("RFC 0015 Phase 3 last-active-org backfill: starting APPLY");
                try {
                    List<BackfillLastActiveOrgService.BackfillRow> rows = backfillService.apply();
                    log.info("RFC 0015 Phase 3 last-active-org backfill: APPLY complete, {} user(s) affected",
                            rows.size());
                } catch (Exception e) {
                    log.error("RFC 0015 Phase 3 last-active-org backfill: mode=apply failed — backfill skipped, "
                            + "retry on the next deploy (idempotent: unaffected rows remain eligible)", e);
                }
            }
            default -> log.warn(
                    "RFC 0015 Phase 3 last-active-org backfill: unknown mode '{}' (expected off|dry-run|apply), skipping",
                    mode);
        }
    }
}
