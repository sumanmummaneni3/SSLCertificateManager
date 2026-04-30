package com.certguard.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DevModeWarning {

    private final boolean devMode;

    public DevModeWarning(@Value("${app.dev-mode:false}") boolean devMode) {
        this.devMode = devMode;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfDevMode() {
        if (devMode) {
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            log.warn("!!! WARNING: app.dev-mode is ENABLED. Dev-only endpoints  !!!");
            log.warn("!!! (/api/v1/auth/dev-token) are ACTIVE. Do NOT run this  !!!");
            log.warn("!!! configuration in a production environment.             !!!");
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        }
    }
}
