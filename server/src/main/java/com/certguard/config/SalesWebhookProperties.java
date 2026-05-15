package com.certguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.sales.webhook")
public record SalesWebhookProperties(
        String url,
        String secret,
        @DefaultValue("5000") int timeoutMs,
        @DefaultValue("3") int maxRetries
) {}
