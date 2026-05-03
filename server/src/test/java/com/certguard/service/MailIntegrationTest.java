package com.certguard.service;

import com.certguard.entity.Agent;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.repository.OrgNotificationChannelRepository;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.thymeleaf.TemplateEngine;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test that uses a real Thymeleaf engine + real JavaMailSender
 * against GreenMail to assert rendered email properties.
 *
 * Boots only the mail + Thymeleaf autoconfiguration — no JPA, no security.
 */
@SpringBootTest(
    classes = {NotificationService.class},
    properties = {
        "app.dev-mode=false",
        "app.base-url=http://localhost",
        "spring.mail.host=localhost",
        "spring.mail.port=3025",
        "spring.mail.username=test@localhost",
        "spring.mail.password=password",
        "spring.mail.properties.mail.smtp.auth=true",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "app.mail.from=noreply@certguard.cloud"
    }
)
@ImportAutoConfiguration({MailSenderAutoConfiguration.class, ThymeleafAutoConfiguration.class})
class MailIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(com.icegreen.greenmail.configuration.GreenMailConfiguration
                    .aConfig().withUser("test@localhost", "password"));

    @MockitoBean
    OrgNotificationChannelRepository orgChannelRepository;

    @Autowired
    JavaMailSender mailSender;

    @Autowired
    TemplateEngine templateEngine;

    NotificationService notificationService;

    Organization org;
    Target target;

    @BeforeEach
    void setUp() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();

        when(orgChannelRepository.findByOrganizationIdAndEnabledTrue(any())).thenReturn(List.of());

        notificationService = new NotificationService(mailSender, templateEngine, orgChannelRepository);
        ReflectionTestUtils.setField(notificationService, "fromAddress", "noreply@certguard.cloud");
        ReflectionTestUtils.setField(notificationService, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(notificationService, "devMode", false);

        org = Organization.builder().name("TestOrg").contactEmail("ops@example.com").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());

        Map<String, Object> emailCfg = new HashMap<>();
        emailCfg.put("enabled", true);
        emailCfg.put("addresses", List.of("ops@example.com"));
        Map<String, Object> channels = new HashMap<>();
        channels.put("email", emailCfg);

        target = Target.builder()
                .organization(org)
                .host("example.com")
                .port(443)
                .notificationChannels(channels)
                .build();
    }

    @Test
    void expiryWarning_sendsEmailWithCorrectSubjectAndRecipient() throws Exception {
        notificationService.dispatchExpiryAlert(target, 20, "WARNING");

        greenMail.waitForIncomingEmail(5000, 1);
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);

        MimeMessage msg = messages[0];
        assertThat(msg.getSubject()).contains("WARNING");
        assertThat(msg.getSubject()).contains("example.com:443");
        assertThat(msg.getAllRecipients()[0].toString()).isEqualTo("ops@example.com");
        assertThat(msg.getFrom()[0].toString()).isEqualTo("noreply@certguard.cloud");
    }

    @Test
    void expiryCritical_sendsEmailWithCriticalSubject() throws Exception {
        notificationService.dispatchExpiryAlert(target, 3, "CRITICAL");

        greenMail.waitForIncomingEmail(5000, 1);
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).contains("CRITICAL");
    }

    @Test
    void expiredCert_subjectContainsExpired() throws Exception {
        notificationService.dispatchExpiryAlert(target, -3, "CRITICAL");

        greenMail.waitForIncomingEmail(5000, 1);
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).contains("EXPIRED");
    }

    @Test
    void agentOfflineAlert_sendsEmailToContactAddress() throws Exception {
        Agent agent = Agent.builder()
                .name("prod-agent-1")
                .organization(org)
                .agentKeyHash("abc")
                .build();
        ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());
        agent.setLastSeenAt(Instant.now().minusSeconds(600));

        notificationService.dispatchAgentOfflineAlert(agent, org);

        greenMail.waitForIncomingEmail(5000, 1);
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).contains("Agent offline");
        assertThat(messages[0].getAllRecipients()[0].toString()).isEqualTo("ops@example.com");
    }
}
