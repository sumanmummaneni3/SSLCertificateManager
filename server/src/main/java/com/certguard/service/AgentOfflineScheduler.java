package com.certguard.service;

import com.certguard.entity.Agent;
import com.certguard.enums.AgentStatus;
import com.certguard.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduled job that detects agents that have gone silent (no heartbeat)
 * and delegates alerting to NotificationService.
 *
 * An agent is considered offline when:
 *   lastSeenAt < now - app.agent.offline-threshold-minutes (default 10 min)
 *
 * Runs every 5 minutes. Configurable via:
 *   app.agent.offline-threshold-minutes (default 10)
 *   app.agent.offline-check-interval-ms (default 300000 = 5 min)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOfflineScheduler {

    private final AgentRepository agentRepository;
    private final NotificationService notificationService;

    @Value("${app.agent.offline-threshold-minutes:10}")
    private int offlineThresholdMinutes;

    @Scheduled(fixedDelayString = "${app.agent.offline-check-interval-ms:300000}")
    @SchedulerLock(name = "AgentOfflineScheduler_checkOfflineAgents",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT4M")
    @Transactional
    public void checkOfflineAgents() {
        Instant now = Instant.now();
        Instant threshold = now.minus(offlineThresholdMinutes, ChronoUnit.MINUTES);
        Instant alertDeduplicationWindow = now.minus(24, ChronoUnit.HOURS);

        List<Agent> offlineAgents = agentRepository.findOfflineAgents(AgentStatus.ACTIVE, threshold);

        if (offlineAgents.isEmpty()) return;

        log.warn("{} agent(s) appear offline (no heartbeat in {} min)",
                offlineAgents.size(), offlineThresholdMinutes);

        for (Agent agent : offlineAgents) {
            // Skip agents that already received an alert within the last 24 hours.
            if (agent.getLastOfflineAlertSentAt() != null
                    && agent.getLastOfflineAlertSentAt().isAfter(alertDeduplicationWindow)) {
                log.debug("Suppressing duplicate offline alert for agent {} (last sent: {})",
                        agent.getId(), agent.getLastOfflineAlertSentAt());
                continue;
            }

            notificationService.dispatchAgentOfflineAlert(agent, agent.getOrganization());
            agent.setLastOfflineAlertSentAt(now);
            agentRepository.save(agent);
        }
    }
}
