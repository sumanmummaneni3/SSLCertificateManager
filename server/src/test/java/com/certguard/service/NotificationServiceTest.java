package com.certguard.service;

import com.certguard.entity.Agent;
import com.certguard.entity.CertificateRecord;
import com.certguard.entity.OrgNotificationChannel;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.repository.OrgNotificationChannelRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock JavaMailSender mailSender;
    @Mock TemplateEngine templateEngine;
    @Mock OrgNotificationChannelRepository orgChannelRepository;
    @Mock OrganizationRepository orgRepository;
    @Mock UserRepository userRepository;

    NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                mailSender, templateEngine, orgChannelRepository, orgRepository, userRepository);
        ReflectionTestUtils.setField(notificationService, "fromAddress", "noreply@certguard.cloud");
        ReflectionTestUtils.setField(notificationService, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(notificationService, "uiBaseUrl", "http://localhost:5173");
        ReflectionTestUtils.setField(notificationService, "devMode", false);
    }

    private Organization buildOrg() {
        Organization org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        return org;
    }

    private Target targetWithEmailChannel(Organization org, String... addresses) {
        Map<String, Object> emailCfg = new HashMap<>();
        emailCfg.put("enabled", true);
        emailCfg.put("addresses", List.of(addresses));

        Map<String, Object> channels = new HashMap<>();
        channels.put("email", emailCfg);

        return Target.builder()
                .organization(org)
                .host("example.com")
                .port(443)
                .notificationChannels(channels)
                .build();
    }

    private CertificateRecord certForTarget(Target target) {
        CertificateRecord cert = CertificateRecord.builder()
                .target(target)
                .orgId(target.getOrganization().getId())
                .commonName("example.com")
                .issuer("Test CA")
                .serialNumber("123")
                .expiryDate(Instant.now().plus(20, ChronoUnit.DAYS))
                .notBefore(Instant.now().minus(30, ChronoUnit.DAYS))
                .build();
        ReflectionTestUtils.setField(cert, "id", UUID.randomUUID());
        return cert;
    }

    @Nested
    class DispatchExpiryAlert {

        @Test
        void devModeSkipsSend() {
            ReflectionTestUtils.setField(notificationService, "devMode", true);
            Organization org = buildOrg();
            Target target = targetWithEmailChannel(org, "ops@example.com");
            CertificateRecord cert = certForTarget(target);

            notificationService.dispatchExpiryAlert(cert, 10, "WARNING");

            verifyNoInteractions(mailSender);
        }

        @Test
        void emptyChannelsSkips() {
            Organization org = buildOrg();
            when(orgChannelRepository.findByOrganizationIdAndEnabledTrue(any())).thenReturn(List.of());

            Target target = Target.builder()
                    .organization(org).host("x.com").port(443)
                    .notificationChannels(Map.of())
                    .build();
            CertificateRecord cert = certForTarget(target);

            notificationService.dispatchExpiryAlert(cert, 5, "CRITICAL");

            verifyNoInteractions(mailSender);
        }

        @Test
        void emptyRecipientsSkipsSend() {
            Organization org = buildOrg();

            Map<String, Object> emailCfg = Map.of("enabled", true, "addresses", List.of());
            Target target = Target.builder()
                    .organization(org).host("x.com").port(443)
                    .notificationChannels(Map.of("email", emailCfg))
                    .build();
            CertificateRecord cert = certForTarget(target);

            notificationService.dispatchExpiryAlert(cert, 5, "CRITICAL");

            verifyNoInteractions(mailSender);
        }

        @Test
        void smtpExceptionIsSwallowed() {
            Organization org = buildOrg();
            Target target = targetWithEmailChannel(org, "ops@example.com");
            CertificateRecord cert = certForTarget(target);
            when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);
            doThrow(new RuntimeException("SMTP down")).when(mailSender).send(any(MimeMessage.class));

            notificationService.dispatchExpiryAlert(cert, 3, "CRITICAL");
            // no exception propagates
        }

        @Test
        void warningUsesWarningTemplate() {
            Organization org = buildOrg();
            Target target = targetWithEmailChannel(org, "ops@example.com");
            CertificateRecord cert = certForTarget(target);
            when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchExpiryAlert(cert, 20, "WARNING");

            ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
            verify(templateEngine, atLeastOnce()).process(templateCaptor.capture(), any(IContext.class));
            assertThat(templateCaptor.getAllValues()).anyMatch(n -> n.contains("expiry-warning"));
        }

        @Test
        void criticalUsesCriticalTemplate() {
            Organization org = buildOrg();
            Target target = targetWithEmailChannel(org, "ops@example.com");
            CertificateRecord cert = certForTarget(target);
            when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchExpiryAlert(cert, 2, "CRITICAL");

            ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
            verify(templateEngine, atLeastOnce()).process(templateCaptor.capture(), any(IContext.class));
            assertThat(templateCaptor.getAllValues()).anyMatch(n -> n.contains("expiry-critical"));
        }

        @Test
        void comingSoonChannelIsOnlyLogged() {
            Organization org = buildOrg();

            Map<String, Object> slackCfg = Map.of("enabled", true);
            Map<String, Object> emailCfg = new HashMap<>();
            emailCfg.put("enabled", false);
            emailCfg.put("addresses", List.of("ops@example.com"));
            Target target = Target.builder()
                    .organization(org).host("x.com").port(443)
                    .notificationChannels(Map.of("slack", slackCfg, "email", emailCfg))
                    .build();
            CertificateRecord cert = certForTarget(target);

            notificationService.dispatchExpiryAlert(cert, 5, "WARNING");

            verifyNoInteractions(mailSender);
        }

        @Test
        void orgFallbackChannelUsedWhenTargetEmpty() {
            Organization org = buildOrg();
            UUID orgId = org.getId();

            Target target = Target.builder()
                    .organization(org).host("x.com").port(443)
                    .notificationChannels(Map.of())
                    .build();
            CertificateRecord cert = certForTarget(target);

            OrgNotificationChannel orgChannel = OrgNotificationChannel.builder()
                    .organization(org)
                    .channelType("email")
                    .config(Map.of("addresses", List.of("org-admin@example.com")))
                    .enabled(true)
                    .build();

            when(orgChannelRepository.findByOrganizationIdAndEnabledTrue(orgId))
                    .thenReturn(List.of(orgChannel));
            when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchExpiryAlert(cert, 5, "WARNING");

            verify(mailSender).send(any(MimeMessage.class));
        }
    }

    @Nested
    class DispatchRevocationAlert {

        private com.certguard.dto.internal.RevocationAlertContext ctxWith(
                String reason, boolean onHold, boolean highSeverity, Map<String, Object> channels) {
            return new com.certguard.dto.internal.RevocationAlertContext(
                    UUID.randomUUID(),
                    "example.com", 443,
                    UUID.randomUUID(),
                    reason,
                    "OCSP",
                    java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS),
                    onHold,
                    highSeverity ? "CRITICAL" : "HIGH",
                    channels);
        }

        private Map<String, Object> emailChannels(String... addresses) {
            Map<String, Object> emailCfg = new HashMap<>();
            emailCfg.put("enabled", true);
            emailCfg.put("addresses", List.of(addresses));
            Map<String, Object> channels = new HashMap<>();
            channels.put("email", emailCfg);
            return channels;
        }

        @Test
        void devModeSkipsSend() {
            ReflectionTestUtils.setField(notificationService, "devMode", true);
            var ctx = ctxWith("KEY_COMPROMISE", false, true, emailChannels("ops@example.com"));

            notificationService.dispatchRevocationAlert(ctx);

            verifyNoInteractions(mailSender);
        }

        @Test
        void emptyChannelsSkipsSend() {
            var ctx = ctxWith("UNSPECIFIED", false, false, Map.of());

            notificationService.dispatchRevocationAlert(ctx);

            verifyNoInteractions(mailSender);
        }

        @Test
        void revocationAlertUsesRevocationTemplate() {
            var ctx = ctxWith("KEY_COMPROMISE", false, true, emailChannels("ops@example.com"));
            when(templateEngine.process(anyString(), any(org.thymeleaf.context.IContext.class)))
                    .thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchRevocationAlert(ctx);

            ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
            verify(templateEngine, atLeastOnce()).process(templateCaptor.capture(),
                    any(org.thymeleaf.context.IContext.class));
            assertThat(templateCaptor.getAllValues())
                    .anyMatch(name -> name.contains("revocation-alert"));
        }

        @Test
        void onHoldReasonDisplayedAsSuspended() {
            // reason display = "Suspended (on hold)" when onHold=true
            var ctx = ctxWith("CERTIFICATE_HOLD", true, false, emailChannels("ops@example.com"));
            when(templateEngine.process(anyString(), any(org.thymeleaf.context.IContext.class)))
                    .thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchRevocationAlert(ctx);

            // Subject must contain "Suspended (on hold)"
            ArgumentCaptor<org.thymeleaf.context.IContext> ctxCaptor =
                    ArgumentCaptor.forClass(org.thymeleaf.context.IContext.class);
            verify(templateEngine, atLeastOnce()).process(anyString(), ctxCaptor.capture());
            org.thymeleaf.context.IContext captured = ctxCaptor.getAllValues().get(0);
            assertThat(captured.getVariable("onHold")).isEqualTo(true);
            assertThat(captured.getVariable("reason")).isEqualTo("Suspended (on hold)");
        }

        @Test
        void highSeverityFlagSetForKeyCompromise() {
            var ctx = ctxWith("KEY_COMPROMISE", false, true, emailChannels("sec@example.com"));
            when(templateEngine.process(anyString(), any(org.thymeleaf.context.IContext.class)))
                    .thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchRevocationAlert(ctx);

            ArgumentCaptor<org.thymeleaf.context.IContext> ctxCaptor =
                    ArgumentCaptor.forClass(org.thymeleaf.context.IContext.class);
            verify(templateEngine, atLeastOnce()).process(anyString(), ctxCaptor.capture());
            org.thymeleaf.context.IContext captured = ctxCaptor.getAllValues().get(0);
            assertThat(captured.getVariable("highSeverity")).isEqualTo(true);
        }

        @Test
        void smtpExceptionIsSwallowed() {
            var ctx = ctxWith("SUPERSEDED", false, false, emailChannels("ops@example.com"));
            when(templateEngine.process(anyString(), any(org.thymeleaf.context.IContext.class)))
                    .thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);
            doThrow(new RuntimeException("SMTP down")).when(mailSender).send(any(MimeMessage.class));

            notificationService.dispatchRevocationAlert(ctx);
            // no exception propagates
        }
    }

    @Nested
    class DispatchAgentOfflineAlert {

        @Test
        void devModeSkipsSend() {
            ReflectionTestUtils.setField(notificationService, "devMode", true);
            Organization org = Organization.builder().name("Org").contactEmail("ops@example.com").build();
            Agent agent = Agent.builder().name("agent-1").organization(org).build();
            ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());
            agent.setLastSeenAt(Instant.now().minusSeconds(600));

            notificationService.dispatchAgentOfflineAlert(agent, org);

            verifyNoInteractions(mailSender);
        }

        @Test
        void missingContactEmailSkips() {
            Organization org = Organization.builder().name("Org").build();
            Agent agent = Agent.builder().name("agent-1").organization(org).build();
            ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());

            notificationService.dispatchAgentOfflineAlert(agent, org);

            verifyNoInteractions(mailSender);
        }

        @Test
        void sendsEmailToContactAddress() {
            Organization org = Organization.builder().name("Org").contactEmail("ops@example.com").build();
            Agent agent = Agent.builder().name("agent-1").organization(org).build();
            ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());
            agent.setLastSeenAt(Instant.now().minusSeconds(600));

            when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchAgentOfflineAlert(agent, org);

            verify(mailSender).send(any(MimeMessage.class));
        }
    }
}
