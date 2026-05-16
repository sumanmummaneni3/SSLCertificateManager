package com.certguard.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.jwt")
public record GatewayJwtProperties(String jwksUri, String issuer, String audience) {}
