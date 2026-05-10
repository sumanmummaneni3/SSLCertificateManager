# CertGuard LLD Addendum — MSP Onboarding, Multi-Org Management & Billing

> **Status:** Decisions locked (Q1–Q7 answered by PO). This section is implementation-ready and supersedes any conflicting parts of `docs/architecture/LLD.md` for the MSP / billing surface.
>
> **Append target:** `docs/architecture/LLD.md` (after the "Platform Admin" section).
> **Source of truth for code citations:** absolute paths under `/home/msuman/git/SSLCertificateManager/`.

---

## 1. Database Schema Changes

Two new Flyway migrations, additive only. Both follow existing conventions (`db/migration/V1__core_schema.sql:5-11` provides `update_updated_at()` trigger; `db/migration/V5__rbac_locations_org_profile.sql:6-9` shows the enum+`ALTER TABLE … ADD COLUMN IF NOT EXISTS` pattern).

### 1.1 `V22__msp_onboarding.sql` — soft delete + onboarding tracking

```sql
-- ============================================================
-- V22 — MSP onboarding: soft-delete orgs + user onboarding state
-- ============================================================

-- 1. Soft-delete on organizations.
--    All read paths must filter `WHERE archived_at IS NULL`.
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS archived_by UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS archive_reason VARCHAR(500);

-- Partial index — most queries hit live rows.
CREATE INDEX IF NOT EXISTS idx_orgs_active
    ON organizations(id) WHERE archived_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_orgs_parent_active
    ON organizations(parent_org_id) WHERE archived_at IS NULL;

-- 2. Onboarding tracking on users.
--    Set immediately for invited users (skip wizard) and after wizard completion.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS onboarding_completed_at TIMESTAMPTZ NULL;

-- Backfill: every user who already has at least one ACCEPTED org_member is "onboarded".
UPDATE users u
   SET onboarding_completed_at = u.created_at
 WHERE onboarding_completed_at IS NULL
   AND EXISTS (
       SELECT 1 FROM org_members m
        WHERE m.user_id = u.id
          AND m.invite_status = 'ACCEPTED'
   );

-- 3. Drop the self-service MSP toggle from UpdateOrgProfileRequest path.
--    The org_type column stays; only the WRITE path changes (see §2.2).
--    No DDL change needed — guard is in code (@PreAuthorize).
```

### 1.2 `V23__billing.sql` — plans + Stripe subscription mapping

```sql
-- ============================================================
-- V23 — Billing: Plans + Stripe subscription mapping
-- ============================================================

CREATE TYPE billing_status AS ENUM (
    'TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'UNPAID', 'INCOMPLETE'
);

-- ── plans ────────────────────────────────────────────────────────────────
-- Catalog table seeded from application bootstrap or admin tooling.
CREATE TABLE plans (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                     VARCHAR(50)  NOT NULL UNIQUE,        -- 'free', 'pro', 'msp-starter', 'msp-growth'
    display_name             VARCHAR(100) NOT NULL,
    -- Stripe
    stripe_price_id          VARCHAR(255) NOT NULL UNIQUE,
    stripe_product_id        VARCHAR(255) NOT NULL,
    -- Quotas governed by this plan
    max_certificate_quota    INTEGER NOT NULL,                    -- caps cert scans (matches subscriptions.max_certificate_quota)
    max_child_orgs           INTEGER NOT NULL DEFAULT 0,          -- 0 for non-MSP plans
    max_agents               INTEGER NOT NULL DEFAULT 5,
    -- Plan eligibility
    is_msp_plan              BOOLEAN NOT NULL DEFAULT FALSE,
    monthly_price_cents      INTEGER NOT NULL,
    currency                 CHAR(3)  NOT NULL DEFAULT 'USD',
    active                   BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TRIGGER trg_plans_updated_at
    BEFORE UPDATE ON plans
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ── org_billing ──────────────────────────────────────────────────────────
-- One row per BILLABLE org (MSP or SINGLE not under an MSP).
-- Child orgs of an MSP DO NOT get a row — billing rolls up to the parent MSP
-- (Decision #3). The old `subscriptions` row stays as an internal quota mirror.
CREATE TABLE org_billing (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                      UUID NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    plan_id                     UUID REFERENCES plans(id) ON DELETE RESTRICT,
    stripe_customer_id          VARCHAR(255) UNIQUE,
    stripe_subscription_id      VARCHAR(255) UNIQUE,
    stripe_payment_method_id    VARCHAR(255),
    status                      billing_status NOT NULL DEFAULT 'TRIALING',
    current_period_start        TIMESTAMPTZ,
    current_period_end          TIMESTAMPTZ,
    trial_ends_at               TIMESTAMPTZ,
    cancel_at_period_end        BOOLEAN NOT NULL DEFAULT FALSE,
    last_webhook_event_id       VARCHAR(255),     -- idempotency: skip dup Stripe deliveries
    last_webhook_received_at    TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_org_billing_status ON org_billing(status);
CREATE TRIGGER trg_org_billing_updated_at
    BEFORE UPDATE ON org_billing
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ── stripe_webhook_events (audit + idempotency) ──────────────────────────
CREATE TABLE stripe_webhook_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stripe_event_id     VARCHAR(255) NOT NULL UNIQUE,
    event_type          VARCHAR(100) NOT NULL,
    payload             JSONB NOT NULL,
    org_id              UUID REFERENCES organizations(id) ON DELETE SET NULL,
    processed           BOOLEAN NOT NULL DEFAULT FALSE,
    processing_error    TEXT,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_stripe_events_received ON stripe_webhook_events(received_at);
CREATE INDEX idx_stripe_events_unprocessed
    ON stripe_webhook_events(received_at) WHERE processed = FALSE;
```

### 1.3 Notes on existing tables

- `subscriptions` table (V1) is **kept as-is**. It continues to hold `max_certificate_quota` (the effective per-org quota cap) and `status` (legacy `SubscriptionStatus`). `BillingService` writes into `subscriptions.max_certificate_quota` for the MSP org based on its plan, and propagates aggregate quota to child orgs (see §2.7).
- `UpdateOrgProfileRequest.isMsp` (`server/src/main/java/com/certguard/dto/request/UpdateOrgProfileRequest.java:18`) field is **kept on the DTO** but ignored by `OrgService.updateProfile()`. A new field-level guard rejects the request if a non-PLATFORM_ADMIN sets it (see §2.2). This avoids a UI break while moving promotion to admin only.
- No DB triggers for archive cascade — handled in application layer (transactional, atomic, with audit) so we don't lose visibility into what got archived.

### 1.4 Index recommendations

- `idx_orgs_active` and `idx_orgs_parent_active` (partial, `WHERE archived_at IS NULL`) cover the dominant read pattern.
- `org_billing.status` index supports the upcoming "delinquent orgs" admin view.
- `targets` already has `idx_targets_org_id` (V1:75) — sufficient for the MSP aggregated-targets `IN (?, ?, ...)` query at the expected fan-out (≤ 100 child orgs typical).

---

## 2. Backend — New / Modified Classes

### 2.1 `AdminController.promoteToMsp` (Decision #4)

**File:** `server/src/main/java/com/certguard/controller/AdminController.java`

Append a method (existing class is already `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` at class level — `AdminController.java:31`):

```java
@PatchMapping("/orgs/{orgId}/promote-msp")
public ResponseEntity<OrgResponse> promoteToMsp(
        @PathVariable UUID orgId,
        @RequestParam(required = false) String reason) {
    return ResponseEntity.ok(adminService.promoteToMsp(orgId, reason));
}

@PatchMapping("/orgs/{orgId}/demote-msp")
public ResponseEntity<OrgResponse> demoteFromMsp(@PathVariable UUID orgId) {
    return ResponseEntity.ok(adminService.demoteFromMsp(orgId));
}
```

**File:** `server/src/main/java/com/certguard/service/AdminService.java`

```java
@Transactional
public OrgResponse promoteToMsp(UUID orgId, String reason) {
    Organization org = orgRepository.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    if (org.getOrgType() == OrgType.MSP) return orgService.toResponse(org,
        subscriptionRepository.findByOrganizationId(orgId).orElse(null));
    org.setOrgType(OrgType.MSP);
    orgRepository.save(org);
    log.info("Org {} promoted to MSP by platform admin (reason='{}')", orgId, reason);
    return orgService.toResponse(org,
        subscriptionRepository.findByOrganizationId(orgId).orElse(null));
}

@Transactional
public OrgResponse demoteFromMsp(UUID orgId) {
    Organization org = orgRepository.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    long childCount = orgRepository.countByParentOrgIdAndArchivedAtIsNull(orgId);
    if (childCount > 0) {
        throw new IllegalStateException(
            "Cannot demote MSP with " + childCount + " active child orgs. " +
            "Archive children first.");
    }
    org.setOrgType(OrgType.SINGLE);
    orgRepository.save(org);
    return orgService.toResponse(org,
        subscriptionRepository.findByOrganizationId(orgId).orElse(null));
}
```

### 2.2 Guard self-service MSP toggle (Decision #4)

**File:** `server/src/main/java/com/certguard/service/OrgService.java:96`

Replace:

```java
if (req.getIsMsp() != null) org.setOrgType(req.getIsMsp() ? OrgType.MSP : OrgType.SINGLE);
```

with:

```java
if (req.getIsMsp() != null) {
    boolean isPlatformAdmin = SecurityContextHolder.getContext().getAuthentication()
        .getAuthorities().stream()
        .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    if (!isPlatformAdmin) {
        throw new AccessDeniedException(
            "MSP promotion is sales-assisted. Contact support.");
    }
    org.setOrgType(req.getIsMsp() ? OrgType.MSP : OrgType.SINGLE);
}
```

### 2.3 New `OnboardingController` + `OnboardingService` (Decisions #6 & wizard)

**File (new):** `server/src/main/java/com/certguard/controller/OnboardingController.java`

```java
@RestController
@RequestMapping(value = "/api/v1/onboarding", produces = "application/json")
@RequiredArgsConstructor
public class OnboardingController {
    private final OnboardingService onboardingService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<OrgResponse> completeOnboarding(
            @Valid @RequestBody CompleteOnboardingRequest req,
            @AuthenticationPrincipal CertGuardUserPrincipal principal) {
        return ResponseEntity.ok(onboardingService.complete(
            principal.getUserId(), TenantContext.getOrgId(), req));
    }
}
```

**File (new):** `server/src/main/java/com/certguard/dto/request/CompleteOnboardingRequest.java`

```java
@Data
public class CompleteOnboardingRequest {
    @NotBlank @Size(max = 255) private String orgName;
    @NotNull  private OrgType   orgType;          // SINGLE | MSP (MSP intent recorded; promotion requires platform-admin)
    @Email @Size(max = 255) private String contactEmail;
    @Size(max = 100) private String country;
}
```

**File (new):** `server/src/main/java/com/certguard/service/OnboardingService.java`

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OnboardingService {
    private final OrganizationRepository orgRepository;
    private final UserRepository userRepository;
    private final OrgService orgService;

    @Transactional
    public OrgResponse complete(UUID userId, UUID orgId, CompleteOnboardingRequest req) {
        Organization org = orgRepository.findById(orgId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        org.setName(req.getOrgName().trim());
        if (req.getContactEmail() != null) org.setContactEmail(req.getContactEmail());
        if (req.getCountry()      != null) org.setCountry(req.getCountry());

        // Decision #4: self-service MSP promotion is disabled.
        // If user picked MSP, record the intent but keep as SINGLE.
        // PLATFORM_ADMIN runs PATCH /admin/orgs/{id}/promote-msp after sales call.
        org.setOrgType(OrgType.SINGLE);
        orgRepository.save(org);

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(Instant.now());
            userRepository.save(user);
        }
        return orgService.getOrg(orgId);
    }
}
```

**Entity change:** `User` (`server/src/main/java/com/certguard/entity/User.java`) — add field:

```java
@Column(name = "onboarding_completed_at")
private Instant onboardingCompletedAt;
```

### 2.4 `InvitationService.acceptInvite` — set `onboardingCompletedAt` (Decision #6)

**File:** `server/src/main/java/com/certguard/service/InvitationService.java:117-135`

When creating a new `User` via invitation:

```java
return userRepository.save(User.builder()
        .organization(placeholder)
        .email(inv.getEmail())
        .name(inv.getEmail().split("@")[0])
        .role(UserRole.MEMBER)
        .onboardingCompletedAt(Instant.now())   // invited users skip the wizard
        .build());
```

For the existing-user branch, after the acceptance completes:

```java
if (user.getOnboardingCompletedAt() == null) {
    user.setOnboardingCompletedAt(Instant.now());
    userRepository.save(user);
}
```

### 2.5 `MeController` — expose onboarding + MSP permission flags

**File:** `server/src/main/java/com/certguard/controller/MeController.java:77-86`

```java
User u = userRepository.findById(principal.getUserId()).orElse(null);
boolean onboardingCompleted = u != null && u.getOnboardingCompletedAt() != null;

user.put("onboardingCompleted", onboardingCompleted);
user.put("onboardingCompletedAt", u != null ? u.getOnboardingCompletedAt() : null);

boolean isMspMember = currentOrg != null && OrgType.MSP.equals(currentOrg.get("orgType"));
permissions.put("isMspMember", isMspMember);
permissions.put("canAccessBilling",
    isAdmin && (isMspMember || isPlatformAdmin || true));
```

### 2.6 MSP-scoped data access — `TenantContext` extension (Decision #2)

MSP users do **not** switch orgs. Backend aggregates across child orgs server-side using a per-request `accessibleOrgIds` snapshot.

**File:** `server/src/main/java/com/certguard/security/TenantContext.java` — add:

```java
private static final ThreadLocal<List<UUID>> ACCESSIBLE_ORG_IDS = new ThreadLocal<>();

public static void setAccessibleOrgIds(List<UUID> ids) { ACCESSIBLE_ORG_IDS.set(ids); }
public static List<UUID> getAccessibleOrgIds() {
    List<UUID> ids = ACCESSIBLE_ORG_IDS.get();
    return ids != null ? ids : (getOrgId() != null ? List.of(getOrgId()) : List.of());
}
// add ACCESSIBLE_ORG_IDS.remove() to clear()
```

**File:** `server/src/main/java/com/certguard/security/JwtAuthenticationFilter.java` — after `TenantContext.setOrgId(effectiveOrgId)` (~line 132):

```java
List<UUID> accessibleOrgIds = new ArrayList<>();
accessibleOrgIds.add(effectiveOrgId);
organizationRepository.findById(effectiveOrgId).ifPresent(org -> {
    if (org.getOrgType() == OrgType.MSP) {
        accessibleOrgIds.addAll(
            organizationRepository.findActiveChildIds(effectiveOrgId));
    }
});
TenantContext.setAccessibleOrgIds(accessibleOrgIds);
```

**File:** `server/src/main/java/com/certguard/repository/OrganizationRepository.java` — add:

```java
@Query("SELECT o.id FROM Organization o " +
       "WHERE o.parentOrg.id = :parentId AND o.archivedAt IS NULL")
List<UUID> findActiveChildIds(@Param("parentId") UUID parentId);

long countByParentOrgIdAndArchivedAtIsNull(UUID parentOrgId);
```

### 2.7 `MspDashboardService` + `MspDashboardController` — aggregated views (Decisions #1, #2)

**File (new):** `server/src/main/java/com/certguard/service/MspDashboardService.java`

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MspDashboardService {

    private final OrganizationRepository orgRepository;
    private final TargetRepository targetRepository;
    private final CertificateRecordRepository certRepository;
    private final AgentRepository agentRepository;

    public MspDashboardResponse getDashboard(UUID mspOrgId) {
        assertMsp(mspOrgId);
        List<UUID> orgIds = collectScope(mspOrgId);

        long totalTargets = targetRepository.countByOrgIdIn(orgIds);
        long valid        = certRepository.countByOrgIdInAndStatus(orgIds, CertStatus.VALID);
        long expiring     = certRepository.countByOrgIdInAndStatus(orgIds, CertStatus.EXPIRING);
        long expired      = certRepository.countByOrgIdInAndStatus(orgIds, CertStatus.EXPIRED);
        long unreachable  = certRepository.countByOrgIdInAndStatus(orgIds, CertStatus.UNREACHABLE);
        long agentCount   = agentRepository.countByOrgIdIn(orgIds);
        long childOrgCount = orgIds.size() - 1;  // exclude MSP itself

        List<MspChildOrgStat> perOrg = targetRepository.countTargetsAndCertsPerOrg(orgIds);

        return MspDashboardResponse.builder()
            .mspOrgId(mspOrgId)
            .childOrgCount(childOrgCount)
            .totalTargets(totalTargets)
            .totalAgents(agentCount)
            .valid(valid).expiring(expiring).expired(expired).unreachable(unreachable)
            .perOrg(perOrg)
            .build();
    }

    public Page<MspTargetRow> listTargetsAcrossChildren(UUID mspOrgId, Pageable pageable) {
        assertMsp(mspOrgId);
        List<UUID> orgIds = collectScope(mspOrgId);
        return targetRepository.findAllByOrgIdInWithOrg(orgIds, pageable)
                .map(MspTargetRow::fromEntity);
    }

    private void assertMsp(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
            .orElseThrow(() -> new ResourceNotFoundException("Org not found"));
        if (org.getOrgType() != OrgType.MSP) {
            throw new AccessDeniedException("Endpoint is MSP-only");
        }
    }

    private List<UUID> collectScope(UUID mspOrgId) {
        List<UUID> scoped = TenantContext.getAccessibleOrgIds();
        if (scoped.contains(mspOrgId)) return scoped;
        List<UUID> ids = new ArrayList<>(orgRepository.findActiveChildIds(mspOrgId));
        ids.add(mspOrgId);
        return ids;
    }
}
```

**File (new):** `server/src/main/java/com/certguard/controller/MspDashboardController.java`

```java
@RestController
@RequestMapping(value = "/api/v1/msp", produces = "application/json")
@RequiredArgsConstructor
public class MspDashboardController {
    private final MspDashboardService mspDashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<MspDashboardResponse> dashboard() {
        return ResponseEntity.ok(mspDashboardService.getDashboard(TenantContext.getOrgId()));
    }

    @GetMapping("/targets")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<Page<MspTargetRow>> targets(Pageable pageable) {
        return ResponseEntity.ok(
            mspDashboardService.listTargetsAcrossChildren(TenantContext.getOrgId(), pageable));
    }
}
```

**Repository additions:**

`TargetRepository.java`:
```java
long countByOrgIdIn(@Param("orgIds") Collection<UUID> orgIds);

@Query("SELECT t FROM Target t JOIN FETCH t.organization o " +
       "WHERE o.id IN :orgIds AND o.archivedAt IS NULL")
Page<Target> findAllByOrgIdInWithOrg(@Param("orgIds") Collection<UUID> orgIds, Pageable pageable);

@Query("SELECT new com.certguard.dto.response.MspChildOrgStat(" +
       "o.id, o.name, COUNT(t.id)) " +
       "FROM Organization o LEFT JOIN Target t ON t.organization = o " +
       "WHERE o.id IN :orgIds GROUP BY o.id, o.name")
List<MspChildOrgStat> countTargetsAndCertsPerOrg(@Param("orgIds") Collection<UUID> orgIds);
```

`CertificateRecordRepository.java`:
```java
long countByOrgIdInAndStatus(Collection<UUID> orgIds, CertStatus status);
```

`AgentRepository.java`:
```java
long countByOrgIdIn(Collection<UUID> orgIds);
```

**New DTOs** (under `dto/response/`):
- `MspDashboardResponse` — KPI fields + `List<MspChildOrgStat> perOrg`
- `MspChildOrgStat(UUID orgId, String orgName, long targetCount)` (record)
- `MspTargetRow` — Target fields + `orgId` + `orgName` (Decision #1: "Org Name" column)

```java
@Data @Builder
public class MspTargetRow {
    private UUID id;
    private UUID orgId;
    private String orgName;    // required by Decision #1
    private String host;
    private int port;
    private boolean isPrivate;
    private boolean enabled;
    private Instant lastScannedAt;
    private CertificateSummary latestCertificate;

    public static MspTargetRow fromEntity(Target t) { ... }
}
```

### 2.8 Soft-delete + cascading archive (Decision #5)

**File:** `server/src/main/java/com/certguard/controller/AdminController.java` — append:

```java
@DeleteMapping("/orgs/{orgId}")
public ResponseEntity<Void> archiveOrg(
        @PathVariable UUID orgId,
        @RequestParam(required = false) String reason,
        @AuthenticationPrincipal CertGuardUserPrincipal principal) {
    adminService.archiveOrg(orgId, principal.getUserId(), reason);
    return ResponseEntity.noContent().build();
}

@PostMapping("/orgs/{orgId}/restore")
public ResponseEntity<OrgResponse> restoreOrg(@PathVariable UUID orgId) {
    return ResponseEntity.ok(adminService.restoreOrg(orgId));
}
```

**File:** `server/src/main/java/com/certguard/service/AdminService.java` — append:

```java
@Transactional
public void archiveOrg(UUID orgId, UUID actingUserId, String reason) {
    Organization org = orgRepository.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Org not found: " + orgId));
    if (org.getArchivedAt() != null) return; // idempotent

    Instant now = Instant.now();
    User actor = userRepository.getReferenceById(actingUserId);
    archiveCascade(org, actor, reason, now);
}

private void archiveCascade(Organization org, User actor, String reason, Instant now) {
    org.setArchivedAt(now);
    org.setArchivedBy(actor);
    org.setArchiveReason(reason);
    orgRepository.save(org);

    targetRepository.disableAllForOrg(org.getId());
    agentRepository.revokeAllForOrg(org.getId());

    if (org.getOrgType() == OrgType.MSP) {
        for (Organization child : orgRepository.findAllByParentOrgIdAndArchivedAtIsNull(org.getId())) {
            archiveCascade(child, actor, "parent-msp-archived: " + reason, now);
        }
    }
}

@Transactional
public OrgResponse restoreOrg(UUID orgId) {
    Organization org = orgRepository.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Org not found"));
    org.setArchivedAt(null);
    org.setArchivedBy(null);
    org.setArchiveReason(null);
    orgRepository.save(org);
    targetRepository.enableAllForOrg(orgId);
    return orgService.getOrg(orgId);
}
```

**Repository write helpers:**

`OrganizationRepository.java`:
```java
List<Organization> findAllByParentOrgIdAndArchivedAtIsNull(UUID parentOrgId);
```

`TargetRepository.java`:
```java
@Modifying
@Query("UPDATE Target t SET t.enabled = false WHERE t.organization.id = :orgId")
void disableAllForOrg(@Param("orgId") UUID orgId);

@Modifying
@Query("UPDATE Target t SET t.enabled = true WHERE t.organization.id = :orgId")
void enableAllForOrg(@Param("orgId") UUID orgId);
```

`AgentRepository.java`:
```java
@Modifying
@Query("UPDATE Agent a SET a.status = com.certguard.enums.AgentStatus.REVOKED " +
       "WHERE a.organization.id = :orgId")
void revokeAllForOrg(@Param("orgId") UUID orgId);
```

**All `find*` paths that return orgs to the API surface must add `AND archivedAt IS NULL`.** Key places:
- `OrganizationRepository.findAllByParentOrgId` → rename to `findActiveByParentOrgId` or update `MspClientService.listClients`
- `OrganizationRepository.findAllWithParent` → add a sibling `findActiveWithParent` for non-admin reads
- `AdminService.listAllOrgs` → add `?includeArchived=true` query param for admin visibility

### 2.9 Authorization guard — `MspAccessGuard` (Decision #1)

**File (new):** `server/src/main/java/com/certguard/security/MspAccessGuard.java`

```java
@Component("mspAccessGuard")
public class MspAccessGuard {

    private final OrganizationRepository orgRepository;

    public MspAccessGuard(OrganizationRepository orgRepository) {
        this.orgRepository = orgRepository;
    }

    public boolean canAccessOrg(UUID targetOrgId) {
        UUID home = TenantContext.getOrgId();
        if (home == null) return false;
        if (home.equals(targetOrgId)) return true;
        return orgRepository.findById(targetOrgId)
            .filter(o -> o.getArchivedAt() == null)
            .map(o -> o.getParentOrg() != null && home.equals(o.getParentOrg().getId()))
            .orElse(false);
    }
}
```

Usage on `MspClientController`:

```java
@GetMapping("/clients/{clientOrgId}")
@PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN') " +
              "and @mspAccessGuard.canAccessOrg(#clientOrgId)")
public ResponseEntity<OrgResponse> getClient(@PathVariable UUID clientOrgId) { ... }
```

### 2.10 Billing — entities, service, webhook (Decision #3)

**Entity (new):** `Plan.java`

```java
@Entity @Table(name = "plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Plan extends BaseEntity {
    @Column(unique = true, nullable = false, length = 50) private String code;
    @Column(name = "display_name", nullable = false, length = 100) private String displayName;
    @Column(name = "stripe_price_id", unique = true, nullable = false) private String stripePriceId;
    @Column(name = "stripe_product_id", nullable = false) private String stripeProductId;
    @Column(name = "max_certificate_quota", nullable = false) private Integer maxCertificateQuota;
    @Column(name = "max_child_orgs", nullable = false) @Builder.Default private Integer maxChildOrgs = 0;
    @Column(name = "max_agents", nullable = false) @Builder.Default private Integer maxAgents = 5;
    @Column(name = "is_msp_plan", nullable = false) @Builder.Default private Boolean isMspPlan = false;
    @Column(name = "monthly_price_cents", nullable = false) private Integer monthlyPriceCents;
    @Column(length = 3, nullable = false) @Builder.Default private String currency = "USD";
    @Column(nullable = false) @Builder.Default private Boolean active = true;
}
```

**Entity (new):** `OrgBilling.java`

```java
@Entity @Table(name = "org_billing")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrgBilling extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false, unique = true)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    @Column(name = "stripe_customer_id", unique = true) private String stripeCustomerId;
    @Column(name = "stripe_subscription_id", unique = true) private String stripeSubscriptionId;
    @Column(name = "stripe_payment_method_id") private String stripePaymentMethodId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "billing_status")
    @Builder.Default
    private BillingStatus status = BillingStatus.TRIALING;

    @Column(name = "current_period_start") private Instant currentPeriodStart;
    @Column(name = "current_period_end")   private Instant currentPeriodEnd;
    @Column(name = "trial_ends_at")        private Instant trialEndsAt;
    @Column(name = "cancel_at_period_end", nullable = false) @Builder.Default
    private Boolean cancelAtPeriodEnd = false;
    @Column(name = "last_webhook_event_id")     private String lastWebhookEventId;
    @Column(name = "last_webhook_received_at")  private Instant lastWebhookReceivedAt;
}
```

**Quota propagation (Decision #3 — MSP pays for all children):**

Update `TargetService.enforceTargetQuota` to roll up to the billing owner:

```java
private void enforceTargetQuota(UUID orgId) {
    UUID quotaOrgId = orgRepository.findBillingOwner(orgId); // self if SINGLE, parent MSP if child
    Subscription sub = subscriptionRepository.findByOrganizationId(quotaOrgId)
        .orElseThrow(...);
    List<UUID> scope = orgRepository.findActiveChildIds(quotaOrgId);
    scope.add(quotaOrgId);
    long totalForBillingScope = targetRepository.countByOrgIdIn(scope);
    if (totalForBillingScope >= sub.getMaxCertificateQuota())
        throw new QuotaExceededException(...);
}
```

`OrganizationRepository.java` — add:
```java
@Query("SELECT COALESCE(o.parentOrg.id, o.id) FROM Organization o WHERE o.id = :orgId")
UUID findBillingOwner(@Param("orgId") UUID orgId);
```

**Controller (new):** `BillingController.java`

```java
@RestController
@RequestMapping(value = "/api/v1/billing", produces = "application/json")
@RequiredArgsConstructor
public class BillingController {
    private final BillingService billingService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<OrgBillingResponse> get() {
        return ResponseEntity.ok(billingService.getBilling(TenantContext.getOrgId()));
    }

    @PostMapping("/checkout")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> startCheckout(@RequestParam String planCode) {
        return ResponseEntity.ok(Map.of("url",
            billingService.createCheckoutSession(TenantContext.getOrgId(), planCode).url()));
    }

    @PostMapping("/portal")
    @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> portal(@RequestParam String returnUrl) {
        return ResponseEntity.ok(Map.of("url",
            billingService.createBillingPortalSession(TenantContext.getOrgId(), returnUrl).url()));
    }

    @GetMapping("/plans")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PlanResponse>> plans() {
        return ResponseEntity.ok(billingService.listAvailablePlans(TenantContext.getOrgId()));
    }
}
```

**Webhook controller (new):** `StripeWebhookController.java`

```java
@RestController
@RequestMapping("/api/v1/webhooks/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final BillingService billingService;
    @Value("${app.billing.stripe.webhook-secret}") private String webhookSecret;

    // Public endpoint — JWT-bypassed; secured by Stripe signature verification.
    // SecurityConfig must permitAll() for /api/v1/webhooks/stripe.
    @PostMapping
    public ResponseEntity<String> handle(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        try {
            Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
            billingService.handleWebhookEvent(event);
            return ResponseEntity.ok("ok");
        } catch (SignatureVerificationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad-signature");
        }
    }
}
```

`SecurityConfig.java` — add to `permitAll()` block:
```java
.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/stripe").permitAll()
```

`application.yml` — add:
```yaml
app:
  billing:
    stripe:
      api-key:        ${STRIPE_API_KEY:}
      webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
      success-url:    ${STRIPE_SUCCESS_URL:${APP_BASE_URL}/settings/billing?status=ok}
      cancel-url:     ${STRIPE_CANCEL_URL:${APP_BASE_URL}/settings/billing?status=cancelled}
```

**PLATFORM_ADMIN quota bypass (Decision #7):** `AdminService.updateQuota` — add audit row:

```java
auditRepository.save(PlatformAdminAudit.of(
    TenantContext.getUserId(), actorEmail,
    orgId, org.getName(),
    "PUT", "/api/v1/admin/orgs/" + orgId + "/quota",
    "Quota override (escape hatch); plan-based quota would be " + planQuotaForOrg(orgId),
    200));
```

---

## 3. Frontend Changes

The UI is a single monolith at `ui/src/certguard-ui.jsx`. New views land as new functions in the same file.

### 3.1 Onboarding wizard — 2-card step + invited skip (Decisions #1, #6)

**File:** `ui/src/certguard-ui.jsx:1116-1172` (`function OrgSetup`)

- Insert a new step before the org-name step: two cards "Standard Organization" vs "MSP (Managed Service Provider)". MSP selection shows a banner: *"MSP accounts require sales activation. Our team will reach out within one business day."*
- Replace the existing `api.updateOrgName(name, token)` call with:
  ```js
  await api.completeOnboarding({ orgName: name.trim(), orgType }, token);
  ```
- Change the App phase-machine onboarding gate (`certguard-ui.jsx:3168`) from the `orgName !== "Dev Organization"` heuristic to:
  ```js
  const onboardingDone = me?.user?.onboardingCompleted === true;
  if (!onboardingDone) { setPhase("org-setup"); return; }
  ```

### 3.2 New `MspDashboardView` (Decision #1 — no org switching, aggregate view)

Renders:
1. **KPI tiles**: Child Orgs, Total Targets, Expiring (≤30d), Expired, Unreachable, Active Agents — fed from `GET /api/v1/msp/dashboard`
2. **Per-child table**: org name, target count, expiring count, "View targets" link (filtered drill-down, no impersonation)
3. **Cross-org expiry chart**: 30/60/90-day buckets

Route id: `msp-dashboard` (default landing for MSP users).

### 3.3 Implement `MspOrgsView` (stub at `certguard-ui.jsx:3047`)

| Action | Endpoint | Role |
| --- | --- | --- |
| List child orgs | `GET /api/v1/msp/clients` | Admin/Engineer/Viewer |
| View child org | `GET /api/v1/msp/clients/{id}` | Admin/Engineer/Viewer |
| Add client org | `POST /api/v1/msp/clients` | Admin only |
| Edit client org | `PUT /api/v1/msp/clients/{id}` | Admin only |
| Archive child | "Request Archive" button → support email | Admin only (PLATFORM_ADMIN executes) |

### 3.4 New `MspTargetsView` — Org Name column (Decision #1)

Calls `GET /api/v1/msp/targets`. Reuses `TargetsView` table layout + adds leading "Org" column from `row.orgName`. Read-only in aggregate view (mutations via child-org context).

### 3.5 Sidebar MSP navigation group

Replace `MSP_GROUP` at `certguard-ui.jsx:1759-1764`:

```js
const MSP_GROUP = {
  label: "MSP",
  items: [
    { id: "msp-dashboard", icon: "◇", label: "MSP Dashboard" },
    { id: "msp-orgs",      icon: "⬡", label: "Client Orgs"   },
    { id: "msp-targets",   icon: "⊕", label: "All Targets"   },
    { id: "billing",       icon: "$", label: "Billing"       },
  ],
};
```

Already toggled by `org?.orgType === "MSP"` at line 1791.

### 3.6 Settings → Billing tab

New tab in `SettingsView`:
- Reads `GET /api/v1/billing` — current plan, payment method, trial end
- Invoice list
- "Manage Billing" → `POST /api/v1/billing/portal` → redirect to Stripe portal
- "Upgrade" → `POST /api/v1/billing/checkout?planCode=...` → redirect to Stripe Checkout

Visible only when `me.permissions.canAccessBilling === true`.

---

## 4. Security & Authorization Model

### 4.1 MSP role matrix

| Role (MSP org) | MSP org itself | Child org read | Child org write | Add child org | Archive child | Manage billing |
| --- | --- | --- | --- | --- | --- | --- |
| ADMIN      | full | yes | yes (server aggregates) | yes | request only | yes |
| ENGINEER   | manage targets/agents | yes | yes | no | no | no |
| VIEWER     | read | yes | no | no | no | no |
| PLATFORM_ADMIN | overrides all | yes | yes (audited) | n/a | yes | n/a |

Child-org mutations (create target inside a child) require a future MSP-mutation RFC — out of scope for v1. MSP ADMIN can invite staff as `OrgMember` of child orgs as a workaround.

### 4.2 JWT + TenantContext

JWT format unchanged (single `orgId` claim). `TenantContext.accessibleOrgIds` is computed per-request from DB (one indexed query on `idx_orgs_parent_active`). Not stored in token — child org membership can change without reissuing tokens.

`X-Acting-As-Org` remains PLATFORM_ADMIN-only. MSP users never get this access.

### 4.3 Archive cascade rules

| Trigger | Effect |
| --- | --- |
| Archive SINGLE org | `archived_at = now()`, all targets disabled, all agents revoked |
| Archive MSP org | Recursively archive every live child org; each cascade writes an audit row |
| Archive child of MSP | Only that child archived; MSP dashboard excludes it on next request |
| Restore | PLATFORM_ADMIN only; restoring MSP does NOT auto-restore children (explicit per-child restore) |

PLATFORM_ADMIN only via `DELETE /api/v1/admin/orgs/{orgId}`. No org-self-deletion.

### 4.4 Stripe webhook security

- `permitAll()` on `/api/v1/webhooks/stripe`
- `Webhook.constructEvent(...)` verifies HMAC signature (rejects stale >5min)
- `stripe_webhook_events.stripe_event_id UNIQUE` — duplicate deliveries are no-ops
- `BillingService.handleWebhookEvent` is `@Transactional`; failed events remain `processed=false` for monitoring

---

## 5. Phased Implementation Plan

### Phase 0 — Schema groundwork (1 sprint, backend only)

| # | Task | File |
| --- | --- | --- |
| 0.1 | Write `V22__msp_onboarding.sql` | `server/src/main/resources/db/migration/V22__msp_onboarding.sql` (new) |
| 0.2 | Write `V23__billing.sql` | `server/src/main/resources/db/migration/V23__billing.sql` (new) |
| 0.3 | Add `archivedAt`, `archivedBy`, `archiveReason` to `Organization` entity | `server/src/main/java/com/certguard/entity/Organization.java` |
| 0.4 | Add `onboardingCompletedAt` to `User` entity | `server/src/main/java/com/certguard/entity/User.java` |
| 0.5 | Create `Plan`, `OrgBilling` entities + `BillingStatus` enum | `server/src/main/java/com/certguard/entity/{Plan,OrgBilling}.java`, `enums/BillingStatus.java` |
| 0.6 | Create `PlanRepository`, `OrgBillingRepository`, `StripeWebhookEventRepository` | `server/src/main/java/com/certguard/repository/` |
| 0.7 | Add `archivedAt IS NULL` filter to org list queries | `server/src/main/java/com/certguard/repository/OrganizationRepository.java` |

### Phase 1 — Onboarding & MSP promotion (1 sprint)

| # | Task | File |
| --- | --- | --- |
| 1.1 | `OnboardingService` + `OnboardingController` + `CompleteOnboardingRequest` | new files |
| 1.2 | Set `onboardingCompletedAt` in `InvitationService.acceptInvite` | `server/src/main/java/com/certguard/service/InvitationService.java:117-150` |
| 1.3 | Expose `onboardingCompleted` in `MeController` | `server/src/main/java/com/certguard/controller/MeController.java:77-86` |
| 1.4 | `PATCH /admin/orgs/{id}/promote-msp` + `demote-msp` | `AdminController.java`, `AdminService.java` |
| 1.5 | Guard `OrgService.updateProfile` against self-service `isMsp` | `server/src/main/java/com/certguard/service/OrgService.java:96` |
| 1.6 | Tests: `OnboardingControllerTest`, `AdminControllerPromoteMspTest` | `server/src/test/java/com/certguard/` |
| 1.7 | UI: 2-card OrgSetup wizard + `onboardingCompleted` gate | `ui/src/certguard-ui.jsx:1116-1172`, `:3156-3192` |
| 1.8 | UI: `api.completeOnboarding` helper | `ui/src/certguard-ui.jsx` (api object) |

### Phase 2 — Soft-delete + cascading archive (0.5 sprint)

| # | Task | File |
| --- | --- | --- |
| 2.1 | `archiveOrg` / `restoreOrg` in `AdminService` + endpoints in `AdminController` | `AdminService.java`, `AdminController.java` |
| 2.2 | `disableAllForOrg` / `enableAllForOrg` on `TargetRepository` | `TargetRepository.java` |
| 2.3 | `revokeAllForOrg` on `AgentRepository` | `AgentRepository.java` |
| 2.4 | `findActiveChildIds`, `findAllByParentOrgIdAndArchivedAtIsNull`, `countByParentOrgIdAndArchivedAtIsNull` | `OrganizationRepository.java` |
| 2.5 | Audit row in `archiveOrg` via `platform_admin_audit` | `AdminService.java` |
| 2.6 | UI: archive confirmation modal in `PlatformOrgsView` | `certguard-ui.jsx:~3262` |
| 2.7 | UI: "Request Archive" button in `MspOrgsView` | `certguard-ui.jsx:3047` |

### Phase 3 — MSP aggregated views (1 sprint)

| # | Task | File |
| --- | --- | --- |
| 3.1 | `TenantContext.accessibleOrgIds` ThreadLocal | `TenantContext.java` |
| 3.2 | Populate `accessibleOrgIds` in `JwtAuthenticationFilter` | `JwtAuthenticationFilter.java:132-141` |
| 3.3 | `MspDashboardService` + `MspDashboardController` + response DTOs | new files |
| 3.4 | Repository additions (`countByOrgIdIn`, `findAllByOrgIdInWithOrg`, etc.) | `TargetRepository.java`, `CertificateRecordRepository.java`, `AgentRepository.java` |
| 3.5 | `MspAccessGuard` SpEL bean | `MspAccessGuard.java` (new) |
| 3.6 | Annotate `MspClientController.{getClient,updateClient}` with `@mspAccessGuard` | `MspClientController.java:32-53` |
| 3.7 | UI: `MspDashboardView` component | `certguard-ui.jsx` |
| 3.8 | UI: `MspTargetsView` with Org Name column | `certguard-ui.jsx` |
| 3.9 | UI: implement `MspOrgsView` (list + Add/Edit modals + Request-Archive) | `certguard-ui.jsx:3047-3064` |
| 3.10 | UI: update `MSP_GROUP` sidebar items | `certguard-ui.jsx:1759-1764` |

### Phase 4 — Billing & Stripe (1.5 sprints)

| # | Task | File |
| --- | --- | --- |
| 4.1 | Add Stripe Java SDK to `pom.xml` | `server/pom.xml` |
| 4.2 | Add `app.billing.stripe.*` config | `server/src/main/resources/application.yml` |
| 4.3 | `BillingService` (checkout, portal, get, applyPlanToOrg, handleWebhookEvent) | `BillingService.java` (new) |
| 4.4 | `BillingController` | `BillingController.java` (new) |
| 4.5 | `StripeWebhookController` + `permitAll()` in `SecurityConfig` | `StripeWebhookController.java` (new), `SecurityConfig.java` |
| 4.6 | Update `TargetService.enforceTargetQuota` to roll up to billing-owner org | `TargetService.java:302-308` |
| 4.7 | `findBillingOwner` on `OrganizationRepository` | `OrganizationRepository.java` |
| 4.8 | Audit `AdminService.updateQuota` writes to `platform_admin_audit` | `AdminService.java:148-151` |
| 4.9 | Seed `plans` rows | `server/src/main/resources/db/migration/V24__seed_plans.sql` (new) |
| 4.10 | Stripe webhook tests | `server/src/test/java/com/certguard/controller/StripeWebhookControllerTest.java` (new) |
| 4.11 | UI: Settings → Billing tab | `certguard-ui.jsx` (extend `SettingsView`) |
| 4.12 | UI: Plan picker / Stripe Checkout redirect | `certguard-ui.jsx` |
| 4.13 | Update `HLD.md` (add Stripe to system context) and `GAPS.md` | `docs/architecture/` |

---

## Intentionally Deferred (out of scope for v1)

1. **MSP child-org mutations from MSP context** — creating targets inside a child org from MSP UI. Workaround: invite MSP staff as OrgMember of child orgs.
2. **Per-child invoice line items** — Decision #3 mandates no per-child billing entity.
3. **Hard delete / GDPR purge** — Decision #5 mandates soft-delete only. Purge flow is a separate compliance RFC.
4. **Plan management UI** — initial plans are seeded via V24 migration; Stripe dashboard is the admin surface for changes.
