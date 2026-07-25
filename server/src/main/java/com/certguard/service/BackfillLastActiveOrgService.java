package com.certguard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * RFC 0015 Phase 3 — a one-time, narrowly-targeted data backfill for users who got
 * stuck <em>before</em> Phase 1 shipped: their home-org ({@code users.org_id})
 * membership is missing or revoked, and they have not logged in since to trigger
 * {@link ActiveOrgResolver}'s self-heal write-back.
 *
 * <p>This is NOT a new ongoing mechanism and NOT a scheduled job — it is invoked
 * at most once, at startup, by {@link LastActiveOrgBackfillRunner}, gated off by
 * default via {@code app.backfill.last-active-org.mode}.
 *
 * <p>Eligibility (a user qualifies iff ALL of):
 * <ol>
 *   <li>(A) no valid (ACCEPTED, non-revoked) membership in their home org;</li>
 *   <li>(B) at least one valid (ACCEPTED, non-revoked) membership somewhere (guaranteed
 *       structurally: {@code candidates} is built by joining against a valid membership);</li>
 *   <li>(C) {@code last_active_org_id} is NULL, or points at an org where the user has no
 *       valid membership — this is what makes the operation idempotent: a user already
 *       correct (including one fixed by a prior run of this same backfill, or self-healed by
 *       {@link ActiveOrgResolver} on a recent login) is never touched again;</li>
 *   <li>(D) not a platform admin.</li>
 * </ol>
 *
 * <p>The "new org" chosen is the org of the user's most-recent (by {@code created_at DESC})
 * valid membership — the exact same definition of "valid" and "most-recent" encoded by
 * {@link ActiveOrgResolver} step 3 ({@code OrgMemberRepository#findFirstByUserIdAndInviteStatusAndRevokedAtIsNullOrderByCreatedAtDesc}),
 * so a backfilled value can never diverge from what the resolver would have picked had the
 * user logged in instead.
 *
 * <p>{@code CANDIDATES_CTE} is shared, byte-for-byte, between the dry-run SELECT and the
 * apply UPDATE so the two modes can never observe different eligibility logic.
 */
@Service
@Transactional(readOnly = true)
public class BackfillLastActiveOrgService {

    private static final Logger log = LoggerFactory.getLogger(BackfillLastActiveOrgService.class);

    /**
     * Eligibility CTE. {@code sub} picks each user's single most-recent valid membership
     * (DISTINCT ON ... ORDER BY user_id, created_at DESC, id DESC — the {@code id DESC}
     * tiebreak only matters for exact-timestamp ties, which should be vanishingly rare;
     * not worth over-engineering further). The outer WHERE encodes conditions (A), (C), (D);
     * (B) is structural — a user only appears in {@code candidates} at all if the join to
     * {@code sub} (a valid membership) succeeds.
     */
    private static final String CANDIDATES_CTE = """
            WITH candidates AS (
                SELECT u.id AS user_id,
                       u.last_active_org_id AS old_org_id,
                       sub.org_id AS new_org_id
                FROM users u
                JOIN (
                    SELECT DISTINCT ON (om.user_id) om.user_id, om.org_id
                    FROM org_members om
                    WHERE om.invite_status = 'ACCEPTED' AND om.revoked_at IS NULL
                    ORDER BY om.user_id, om.created_at DESC, om.id DESC
                ) sub ON sub.user_id = u.id
                WHERE u.role <> 'PLATFORM_ADMIN'
                  AND NOT EXISTS (
                      SELECT 1 FROM org_members hm
                      WHERE hm.org_id = u.org_id AND hm.user_id = u.id
                        AND hm.invite_status = 'ACCEPTED' AND hm.revoked_at IS NULL
                  )
                  AND (
                      u.last_active_org_id IS NULL
                      OR NOT EXISTS (
                          SELECT 1 FROM org_members lm
                          WHERE lm.org_id = u.last_active_org_id AND lm.user_id = u.id
                            AND lm.invite_status = 'ACCEPTED' AND lm.revoked_at IS NULL
                      )
                  )
            )
            """;

    private static final String SELECT_CANDIDATES_SQL =
            CANDIDATES_CTE + "SELECT user_id, old_org_id, new_org_id FROM candidates";

    private static final String UPDATE_CANDIDATES_SQL =
            CANDIDATES_CTE + """
            UPDATE users u
            SET last_active_org_id = c.new_org_id
            FROM candidates c
            WHERE u.id = c.user_id
            RETURNING u.id AS user_id, c.old_org_id AS old_org_id, c.new_org_id AS new_org_id
            """;

    private static final RowMapper<BackfillRow> ROW_MAPPER = (rs, rowNum) -> new BackfillRow(
            (UUID) rs.getObject("user_id"),
            (UUID) rs.getObject("old_org_id"),
            (UUID) rs.getObject("new_org_id"));

    private final JdbcTemplate jdbcTemplate;

    public BackfillLastActiveOrgService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One row of backfill output: who moved, from what, to what. */
    public record BackfillRow(UUID userId, UUID oldLastActiveOrgId, UUID newOrgId) {}

    /**
     * Identifies exactly the users the {@code apply} path would affect, without writing
     * anything. Logs every row via {@link #logRows}.
     */
    public List<BackfillRow> dryRun() {
        List<BackfillRow> rows = jdbcTemplate.query(SELECT_CANDIDATES_SQL, ROW_MAPPER);
        logRows("DRY-RUN", rows);
        return rows;
    }

    /**
     * Performs the single set-based UPDATE and returns/logs exactly the rows that were
     * changed (via {@code RETURNING}, so the log can never drift from what was actually
     * written). Idempotent: a second call with no intervening logins/switches affects 0 rows,
     * because condition (C) is re-evaluated against the now-corrected {@code last_active_org_id}.
     */
    @Transactional
    public List<BackfillRow> apply() {
        List<BackfillRow> rows = jdbcTemplate.query(UPDATE_CANDIDATES_SQL, ROW_MAPPER);
        logRows("APPLY", rows);
        return rows;
    }

    private void logRows(String mode, List<BackfillRow> rows) {
        for (BackfillRow row : rows) {
            log.info("RFC 0015 Phase 3 backfill [{}]: userId={} oldLastActiveOrgId={} newOrgId={}",
                    mode, row.userId(), row.oldLastActiveOrgId(), row.newOrgId());
        }
        log.info("RFC 0015 Phase 3 backfill [{}]: {} user(s) {}", mode, rows.size(),
                "APPLY".equals(mode) ? "affected" : "would be affected");
    }
}
