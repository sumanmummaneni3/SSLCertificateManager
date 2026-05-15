package com.certguard.config;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SalesWebhookProperties.class)
public class SalesWebhookConfig {

    // Exposed as a bean so Spring Boot's Jackson auto-configuration registers it
    // on the shared ObjectMapper without replacing the auto-configured instance.
    @Bean
    public JavaTimeModule javaTimeModule() {
        return new JavaTimeModule();
    }
}
