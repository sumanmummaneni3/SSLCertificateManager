package com.certguard.service;

import com.certguard.entity.Agent;
import com.certguard.entity.OrgNotificationChannel;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.repository.OrgNotificationChannelRepository;
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

    NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(mailSender, templateEngine, orgChannelRepository);
        ReflectionTestUtils.setField(notificationService, "fromAddress", "noreply@certguard.cloud");
        ReflectionTestUtils.setField(notificationService, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(notificationService, "devMode", false);
    }

    private Target targetWithEmailChannel(String... addresses) {
        Organization org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());

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

    @Nested
    class DispatchExpiryAlert {

        @Test
        void devModeSkipsSend() {
            ReflectionTestUtils.setField(notificationService, "devMode", true);
            Target target = targetWithEmailChannel("ops@example.com");

            notificationService.dispatchExpiryAlert(target, 10, "WARNING");

            verifyNoInteractions(mailSender);
        }

        @Test
        void emptyChannelsSkips() {
            Organization org = Organization.builder().name("Org").build();
            ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
            when(orgChannelRepository.findByOrganizationIdAndEnabledTrue(any())).thenReturn(List.of());

            Target target = Target.builder()
                    .organization(org).host("x.com").port(443)
                    .notificationChannels(Map.of())
                    .build();

            notificationService.dispatchExpiryAlert(target, 5, "CRITICAL");

            verifyNoInteractions(mailSender);
        }

        @Test
        void emptyRecipientsSkipsSend() {
            Organization org = Organization.builder().name("Org").build();
            ReflectionTestUtils.setField(org, "id", UUID.randomUUID());

            Map<String, Object> emailCfg = Map.of("enabled", true, "addresses", List.of());
            Target target = Target.builder()
                    .organization(org).host("x.com").port(443)
                    .notificationChannels(Map.of("email", emailCfg))
                    .build();

            notificationService.dispatchExpiryAlert(target, 5, "CRITICAL");

            verifyNoInteractions(mailSender);
        }

        @Test
        void smtpExceptionIsSwallowed() {
            Target target = targetWithEmailChannel("ops@example.com");
            when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);
            doThrow(new RuntimeException("SMTP down")).when(mailSender).send(any(MimeMessage.class));

            notificationService.dispatchExpiryAlert(target, 3, "CRITICAL");
            // no exception propagates
        }

        @Test
        void warningUsesWarningTemplate() {
            Target target = targetWithEmailChannel("ops@example.com");
            when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchExpiryAlert(target, 20, "WARNING");

            ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
            verify(templateEngine, atLeastOnce()).process(templateCaptor.capture(), any(IContext.class));
            assertThat(templateCaptor.getAllValues()).anyMatch(n -> n.contains("expiry-warning"));
        }

        @Test
        void criticalUsesCriticalTemplate() {
            Target target = targetWithEmailChannel("ops@example.com");
            when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("body");
            MimeMessage mime = mock(MimeMessage.class);
            when(mailSender.createMimeMessage()).thenReturn(mime);

            notificationService.dispatchExpiryAlert(target, 2, "CRITICAL");

            ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
            verify(templateEngine, atLeastOnce()).process(templateCaptor.capture(), any(IContext.class));
            assertThat(templateCaptor.getAllValues()).anyMatch(n -> n.contains("expiry-critical"));
        }

        @Test
        void comingSoonChannelIsOnlyLogged() {
            Organization org = Organization.builder().name("Org").build();
            ReflectionTestUtils.setField(org, "id", UUID.randomUUID());

            Map<String, Object> slackCfg = Map.of("enabled", true);
            Map<String, Object> emailCfg = new HashMap<>();
            emailCfg.put("enabled", false);
            emailCfg.put("addresses", List.of("ops@example.com"));
            Target target = Target.builder()
                    .organization(org).host("x.com").port(443)
                    .notificationChannels(Map.of("slack", slackCfg, "email", emailCfg))
                    .build();

            notificationService.dispatchExpiryAlert(target, 5, "WARNING");

            verifyNoInteractions(mailSender);
        }

        @Test
        void orgFallbackChannelUsedWhenTargetEmpty() {
            Organization org = Organization.builder().name("Org").build();
            UUID orgId = UUID.randomUUID();
            ReflectionTestUtils.setField(org, "id", orgId);

            Target target = Target.builder()
                    .organization(org).host("x.com").port(443)
                    .notificationChannels(Map.of())
                    .build();

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

            notificationService.dispatchExpiryAlert(target, 5, "WARNING");

            verify(mailSender).send(any(MimeMessage.class));
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
