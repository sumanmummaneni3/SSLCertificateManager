package com.certguard.service;

import com.certguard.dto.internal.ExpiryAlertContext;
import com.certguard.dto.internal.InternalRenewalNotificationRequest;
import com.certguard.entity.Agent;
import com.certguard.entity.CertificateRecord;
import com.certguard.entity.OrgNotificationChannel;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.repository.OrgNotificationChannelRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatches certificate expiry and agent-offline notifications to enabled channels.
 *
 * Per design review comments:
 *   - EMAIL:        LIVE -- dispatched via JavaMailSender with Thymeleaf HTML/text bodies
 *   - SMS:          Coming Soon -- stored in JSONB, UI shows read-only
 *   - WHATSAPP:     Coming Soon -- stored in JSONB, UI shows read-only
 *   - SLACK:        Coming Soon -- stored in JSONB, UI shows read-only
 *   - TEAMS:        Coming Soon -- stored in JSONB, UI shows read-only
 *   - PSA:          Coming Soon -- stored in JSONB, UI shows read-only
 *   - SERVICE_DESK: Coming Soon -- stored in JSONB, UI shows read-only
 */
@Service
@Transactional(readOnly = true)
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

    private final JavaMailSender mailSender;
    private final org.thymeleaf.TemplateEngine templateEngine;
    private final OrgNotificationChannelRepository orgChannelRepository;

    @Value("${app.mail.from:noreply@certguard.cloud}")
    private String fromAddress;

    @Value("${app.dev-mode:true}")
    private boolean devMode;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.ui-base-url:${app.base-url:http://localhost:5173}}")
    private String uiBaseUrl;

    private final OrganizationRepository orgRepository;
    private final UserRepository userRepository;

    public NotificationService(JavaMailSender mailSender,
                               org.thymeleaf.TemplateEngine templateEngine,
                               OrgNotificationChannelRepository orgChannelRepository,
                               OrganizationRepository orgRepository,
                               UserRepository userRepository) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.orgChannelRepository = orgChannelRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
    }

    /**
     * Primary dispatch path (P1-A fix): accepts a pre-built {@link ExpiryAlertContext}
     * that was constructed while the Hibernate session was still open (before the
     * AFTER_COMMIT boundary). Performs zero JPA entity access — safe to call from any
     * async thread or post-commit hook.
     *
     * @param ctx immutable snapshot built in-transaction by ExpiryEvaluationService
     */
    @Async
    public void dispatchExpiryAlert(ExpiryAlertContext ctx) {
        Map<String, Object> channels = ctx.channels();
        if (channels == null || channels.isEmpty()) {
            log.warn("No notification channels configured for target {}:{} (org {}), skipping alert",
                    ctx.host(), ctx.port(), ctx.orgId());
            return;
        }

        String logTarget    = ctx.host() + ":" + ctx.port();
        String templateName = "CRITICAL".equals(ctx.severity()) ? "expiry-critical" : "expiry-warning";
        String subject      = buildExpirySubjectFromCtx(ctx);
        String deepLinkUrl  = uiBaseUrl + "/certificates/" + ctx.certId();

        org.thymeleaf.context.Context thCtx = new org.thymeleaf.context.Context();
        thCtx.setVariable("host",            ctx.host());
        thCtx.setVariable("port",            ctx.port());
        thCtx.setVariable("daysLeft",        ctx.daysLeft());
        thCtx.setVariable("severity",        ctx.severity());
        thCtx.setVariable("baseUrl",         baseUrl);
        thCtx.setVariable("agentDiscovered", ctx.agentDiscovered());
        thCtx.setVariable("deepLinkUrl",     deepLinkUrl);

        dispatchEmail(channels, subject, templateName, thCtx, logTarget);

        logComingSoon("sms",          channels, logTarget);
        logComingSoon("whatsapp",     channels, logTarget);
        logComingSoon("slack",        channels, logTarget);
        logComingSoon("teams",        channels, logTarget);
        logComingSoon("psa",          channels, logTarget);
        logComingSoon("service_desk", channels, logTarget);
    }

    /**
     * Legacy entity-based overload — retained for backward compatibility with tests
     * that construct a full in-memory entity graph (session always open, no lazy risk).
     *
     * @deprecated Prefer {@link #dispatchExpiryAlert(ExpiryAlertContext)} for any
     *             call that crosses a transaction boundary (AFTER_COMMIT / async thread).
     *             This overload dereferences {@code cert.getTarget().getOrganization()} and
     *             {@code target.getAgent()}, which throws LazyInitializationException if the
     *             session is closed (P1-A).
     */
    @Async
    @Deprecated(since = "RFC-0008-P1A", forRemoval = false)
    public void dispatchExpiryAlert(CertificateRecord cert, int daysLeft, String severity) {
        Target target = cert.getTarget();
        Map<String, Object> channels = resolveChannels(target);
        if (channels == null || channels.isEmpty()) {
            log.warn("No notification channels configured for target {} (org {}), skipping alert",
                    target.getId(), target.getOrganization().getId());
            return;
        }

        String templateName = "CRITICAL".equals(severity) ? "expiry-critical" : "expiry-warning";
        String subject = buildExpirySubject(target, daysLeft, severity);

        boolean agentDiscovered = target.getAgent() != null;
        String deepLinkUrl = uiBaseUrl + "/certificates/" + cert.getId();

        org.thymeleaf.context.Context ctx = new org.thymeleaf.context.Context();
        ctx.setVariable("host", target.getHost());
        ctx.setVariable("port", target.getPort());
        ctx.setVariable("daysLeft", daysLeft);
        ctx.setVariable("severity", severity);
        ctx.setVariable("baseUrl", baseUrl);
        ctx.setVariable("agentDiscovered", agentDiscovered);
        ctx.setVariable("deepLinkUrl", deepLinkUrl);

        dispatchEmail(channels, subject, templateName, ctx, target.getHost() + ":" + target.getPort());

        logComingSoon("sms",          channels, target.getHost() + ":" + target.getPort());
        logComingSoon("whatsapp",     channels, target.getHost() + ":" + target.getPort());
        logComingSoon("slack",        channels, target.getHost() + ":" + target.getPort());
        logComingSoon("teams",        channels, target.getHost() + ":" + target.getPort());
        logComingSoon("psa",          channels, target.getHost() + ":" + target.getPort());
        logComingSoon("service_desk", channels, target.getHost() + ":" + target.getPort());
    }

    /**
     * Called when an agent is detected as offline.
     */
    @Async
    public void dispatchAgentOfflineAlert(Agent agent, Organization org) {
        String contactEmail = (org != null) ? org.getContactEmail() : null;
        if (contactEmail == null || contactEmail.isBlank()) {
            log.warn("Agent {} ({}) is offline but org has no contact email -- skipping alert",
                    agent.getName(), agent.getId());
            return;
        }

        String lastSeen = agent.getLastSeenAt() != null
                ? FMT.format(agent.getLastSeenAt())
                : "never";

        String subject = "[CertGuard] Agent offline: " + agent.getName();

        org.thymeleaf.context.Context ctx = new org.thymeleaf.context.Context();
        ctx.setVariable("agentName", agent.getName());
        ctx.setVariable("agentId", agent.getId().toString());
        ctx.setVariable("lastSeen", lastSeen);
        ctx.setVariable("orgName", org != null ? org.getName() : "unknown");
        ctx.setVariable("thresholdMinutes", 10);
        ctx.setVariable("baseUrl", baseUrl);

        if (devMode) {
            log.info("[DEV] Would send offline alert for agent {} to {} -- last seen {}",
                    agent.getName(), contactEmail, lastSeen);
            return;
        }

        sendMimeEmail(contactEmail, subject, "agent-offline", ctx,
                "agent " + agent.getName());
    }

    /**
     * Dispatched by InternalRenewalNotificationController when the renewal service signals
     * a package is ready for download.
     */
    @Async
    public void dispatchRenewalReady(InternalRenewalNotificationRequest req) {
        String contactEmail = resolveRenewalContactEmail(req);
        if (contactEmail == null) return;

        String downloadLink = uiBaseUrl + "/renewals/" + req.renewalId() + "/package";
        String subject = "[CertGuard] Certificate Renewed — Download Ready";

        org.thymeleaf.context.Context ctx = new org.thymeleaf.context.Context();
        ctx.setVariable("renewalId", req.renewalId().toString());
        ctx.setVariable("downloadLink", downloadLink);
        ctx.setVariable("fileName", req.fileName());
        ctx.setVariable("checksum", req.checksumSha256());
        ctx.setVariable("baseUrl", baseUrl);

        if (devMode) {
            log.info("[DEV] Would send renewal-ready email to {} for renewalId {}",
                    contactEmail, req.renewalId());
            return;
        }
        sendMimeEmail(contactEmail, subject, "renewal-ready", ctx, "renewal " + req.renewalId());
    }

    /**
     * Dispatched when the agent has successfully installed the renewed certificate.
     */
    @Async
    public void dispatchRenewalInstalled(InternalRenewalNotificationRequest req) {
        String contactEmail = resolveRenewalContactEmail(req);
        if (contactEmail == null) return;

        String subject = "[CertGuard] Certificate Successfully Installed";

        org.thymeleaf.context.Context ctx = new org.thymeleaf.context.Context();
        ctx.setVariable("renewalId", req.renewalId().toString());
        ctx.setVariable("targetInstallPath", req.targetInstallPath());
        ctx.setVariable("baseUrl", baseUrl);

        if (devMode) {
            log.info("[DEV] Would send renewal-installed email to {} for renewalId {}",
                    contactEmail, req.renewalId());
            return;
        }
        sendMimeEmail(contactEmail, subject, "renewal-installed", ctx, "renewal " + req.renewalId());
    }

    /**
     * Dispatched when certificate installation fails. Surfaces the full errorDetail (req #8).
     */
    @Async
    public void dispatchRenewalFailed(InternalRenewalNotificationRequest req) {
        String contactEmail = resolveRenewalContactEmail(req);
        if (contactEmail == null) return;

        String subject = "[CertGuard] Certificate Installation Failed";

        org.thymeleaf.context.Context ctx = new org.thymeleaf.context.Context();
        ctx.setVariable("renewalId", req.renewalId().toString());
        ctx.setVariable("errorDetail", req.errorDetail() != null ? req.errorDetail() : "Unknown error");
        ctx.setVariable("targetInstallPath", req.targetInstallPath());
        ctx.setVariable("baseUrl", baseUrl);

        if (devMode) {
            log.info("[DEV] Would send renewal-failed email to {} for renewalId {} — error: {}",
                    contactEmail, req.renewalId(), req.errorDetail());
            return;
        }
        sendMimeEmail(contactEmail, subject, "renewal-failed", ctx, "renewal " + req.renewalId());
    }

    /**
     * Resolves effective notification channels for a target.
     * Falls back to org-level channel config when target JSONB is empty.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveChannels(Target target) {
        Map<String, Object> targetChannels = target.getNotificationChannels();
        if (targetChannels != null && !targetChannels.isEmpty()) {
            return targetChannels;
        }

        UUID orgId = target.getOrganization().getId();
        List<OrgNotificationChannel> orgChannels =
                orgChannelRepository.findByOrganizationIdAndEnabledTrue(orgId);

        if (orgChannels.isEmpty()) return Map.of();

        Map<String, Object> resolved = new HashMap<>();
        for (OrgNotificationChannel ch : orgChannels) {
            Map<String, Object> config = ch.getConfig() != null ? new HashMap<>(ch.getConfig()) : new HashMap<>();
            config.put("enabled", true);
            resolved.put(ch.getChannelType().toLowerCase(), config);
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private void dispatchEmail(Map<String, Object> channels, String subject,
                               String templateName, org.thymeleaf.context.Context ctx, String logTarget) {
        Object emailCfg = channels.get("email");
        if (!(emailCfg instanceof Map)) return;
        Map<String, Object> cfg = (Map<String, Object>) emailCfg;

        Boolean enabled = (Boolean) cfg.get("enabled");
        if (!Boolean.TRUE.equals(enabled)) return;

        Object addressesObj = cfg.get("addresses");
        List<String> addresses = new ArrayList<>();
        if (addressesObj instanceof List) {
            addresses = (List<String>) addressesObj;
        }
        if (addresses.isEmpty()) {
            log.warn("Email channel enabled for {} but no addresses configured", logTarget);
            return;
        }

        if (devMode) {
            log.info("[DEV] Email alert template={} for {} -> {} subject={}",
                    templateName, logTarget, addresses, subject);
            return;
        }

        for (String address : addresses) {
            sendMimeEmail(address, subject, templateName, ctx, logTarget);
        }
    }

    private void sendMimeEmail(String to, String subject, String templateName,
                               org.thymeleaf.context.Context ctx, String logTarget) {
        try {
            String htmlBody = templateEngine.process("email/" + templateName, ctx);
            String textBody = templateEngine.process("email/" + templateName + ".txt", ctx);

            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);
            mailSender.send(msg);
            log.info("Email sent to {} template={} for {}", to, templateName, logTarget);
        } catch (Exception e) {
            log.error("Failed to send email to {} template={} for {}: {}",
                    to, templateName, logTarget, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void logComingSoon(String channel, Map<String, Object> channels, String logTarget) {
        Object cfg = channels.get(channel);
        if (!(cfg instanceof Map)) return;
        Boolean enabled = (Boolean) ((Map<String, Object>) cfg).get("enabled");
        if (Boolean.TRUE.equals(enabled)) {
            log.info("[COMING SOON] {} notification skipped for {} -- not yet implemented",
                    channel, logTarget);
        }
    }

    private String buildExpirySubject(Target target, int daysLeft, String severity) {
        if (daysLeft < 0) {
            return "[CertGuard] EXPIRED: " + target.getHost() + ":" + target.getPort();
        }
        return "[CertGuard] " + severity + ": " + target.getHost() + ":" + target.getPort()
                + " expires in " + daysLeft + " day(s)";
    }

    private String buildExpirySubjectFromCtx(ExpiryAlertContext ctx) {
        if (ctx.daysLeft() < 0) {
            return "[CertGuard] EXPIRED: " + ctx.host() + ":" + ctx.port();
        }
        return "[CertGuard] " + ctx.severity() + ": " + ctx.host() + ":" + ctx.port()
                + " expires in " + ctx.daysLeft() + " day(s)";
    }

    private String resolveRenewalContactEmail(InternalRenewalNotificationRequest req) {
        String email = orgRepository.findById(req.orgId())
                .map(org -> org.getContactEmail())
                .filter(e -> e != null && !e.isBlank())
                .orElse(null);

        if (email == null && req.requestedBy() != null) {
            email = userRepository.findById(req.requestedBy())
                    .map(u -> u.getEmail())
                    .filter(e -> e != null && !e.isBlank())
                    .orElse(null);
        }

        if (email == null) {
            log.warn("No contact email found for renewal {} (org {})", req.renewalId(), req.orgId());
        }
        return email;
    }
}
