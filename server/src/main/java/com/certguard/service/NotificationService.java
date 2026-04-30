package com.certguard.service;

import com.certguard.entity.Agent;
import com.certguard.entity.OrgNotificationChannel;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.repository.OrgNotificationChannelRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

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
 *   - EMAIL:        LIVE — dispatched via JavaMailSender with Thymeleaf HTML/text bodies
 *   - SMS:          Coming Soon — stored in JSONB, UI shows read-only
 *   - WHATSAPP:     Coming Soon — stored in JSONB, UI shows read-only
 *   - SLACK:        Coming Soon — stored in JSONB, UI shows read-only
 *   - TEAMS:        Coming Soon — stored in JSONB, UI shows read-only
 *   - PSA:          Coming Soon — stored in JSONB, UI shows read-only
 *   - SERVICE_DESK: Coming Soon — stored in JSONB, UI shows read-only
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class NotificationService {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final OrgNotificationChannelRepository orgChannelRepository;

    @Value("${spring.mail.from:noreply@certguard.cloud}")
    private String fromAddress;

    @Value("${app.dev-mode:true}")
    private boolean devMode;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public NotificationService(JavaMailSender mailSender,
                               TemplateEngine templateEngine,
                               OrgNotificationChannelRepository orgChannelRepository) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.orgChannelRepository = orgChannelRepository;
    }

    /**
     * Called by the expiry checker when a target crosses a threshold.
     *
     * @param target     the target whose certificate is expiring
     * @param daysLeft   days until certificate expires (negative = already expired)
     * @param severity   "WARNING" (≤30 days) or "CRITICAL" (≤7 days)
     */
    @Async
    public void dispatchExpiryAlert(Target target, int daysLeft, String severity) {
        Map<String, Object> channels = resolveChannels(target);
        if (channels == null || channels.isEmpty()) return;

        String templateName = "CRITICAL".equals(severity) ? "expiry-critical" : "expiry-warning";
        String subject = buildExpirySubject(target, daysLeft, severity);

        Context ctx = new Context();
        ctx.setVariable("host", target.getHost());
        ctx.setVariable("port", target.getPort());
        ctx.setVariable("daysLeft", daysLeft);
        ctx.setVariable("severity", severity);
        ctx.setVariable("baseUrl", baseUrl);

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
            log.warn("Agent {} ({}) is offline but org has no contact email — skipping alert",
                    agent.getName(), agent.getId());
            return;
        }

        String lastSeen = agent.getLastSeenAt() != null
                ? FMT.format(agent.getLastSeenAt())
                : "never";

        String subject = "[CertGuard] Agent offline: " + agent.getName();

        Context ctx = new Context();
        ctx.setVariable("agentName", agent.getName());
        ctx.setVariable("agentId", agent.getId().toString());
        ctx.setVariable("lastSeen", lastSeen);
        ctx.setVariable("orgName", org != null ? org.getName() : "unknown");
        ctx.setVariable("thresholdMinutes", 10);
        ctx.setVariable("baseUrl", baseUrl);

        if (devMode) {
            log.info("[DEV] Would send offline alert for agent {} to {} — last seen {}",
                    agent.getName(), contactEmail, lastSeen);
            return;
        }

        sendMimeEmail(contactEmail, subject, "agent-offline", ctx,
                "agent " + agent.getName());
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
                               String templateName, Context ctx, String logTarget) {
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
            log.info("[DEV] Email alert template={} for {} → {} subject={}",
                    templateName, logTarget, addresses, subject);
            return;
        }

        for (String address : addresses) {
            sendMimeEmail(address, subject, templateName, ctx, logTarget);
        }
    }

    private void sendMimeEmail(String to, String subject, String templateName,
                               Context ctx, String logTarget) {
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
            log.info("[COMING SOON] {} notification skipped for {} — not yet implemented",
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
}
