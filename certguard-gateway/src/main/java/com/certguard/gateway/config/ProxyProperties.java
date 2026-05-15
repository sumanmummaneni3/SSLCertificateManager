package com.certguard.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "gateway.proxy")
public class ProxyProperties {

    private List<Route> routes = new ArrayList<>();
    private int connectTimeoutMs = 5000;
    private int responseTimeoutMs = 30000;

    @Data
    public static class Route {
        private String pathPrefix;
        private String upstream;
    }
}
