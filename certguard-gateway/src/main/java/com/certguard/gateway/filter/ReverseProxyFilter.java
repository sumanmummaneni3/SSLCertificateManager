package com.certguard.gateway.filter;

import com.certguard.gateway.config.ProxyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient; // constructed directly; no Boot auto-config in Boot 4.0
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Last filter in the chain: matches the request path to a configured upstream
 * and proxies the full request/response via WebClient.
 *
 * <p>Runs at LOWEST_PRECEDENCE so that JWT validation and Spring Security
 * filters always execute first.
 */
@Slf4j
@Component
public class ReverseProxyFilter implements WebFilter, Ordered {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host"
    );

    private final ProxyProperties props;
    private final WebClient webClient;

    public ReverseProxyFilter(ProxyProperties props) {
        this.props = props;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String upstream = resolveUpstream(path);

        if (upstream == null) {
            return notFound(exchange);
        }

        URI upstreamUri = buildUri(upstream, exchange.getRequest().getURI());
        log.debug("Proxying {} {} → {}", exchange.getRequest().getMethod(), path, upstreamUri);

        HttpHeaders forwardHeaders = filterRequestHeaders(exchange.getRequest().getHeaders(), upstream);

        return webClient.method(exchange.getRequest().getMethod())
                .uri(upstreamUri)
                .headers(h -> h.addAll(forwardHeaders))
                .body(BodyInserters.fromDataBuffers(exchange.getRequest().getBody()))
                .exchangeToMono(upstream_resp -> {
                    exchange.getResponse().setStatusCode(upstream_resp.statusCode());
                    upstream_resp.headers().asHttpHeaders().forEach((name, values) -> {
                        if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                            exchange.getResponse().getHeaders().addAll(name, values);
                        }
                    });
                    return exchange.getResponse()
                            .writeWith(upstream_resp.bodyToFlux(DataBuffer.class));
                })
                .timeout(Duration.ofMillis(props.getResponseTimeoutMs()))
                .onErrorResume(ex -> {
                    log.warn("Upstream {} unreachable: {}", upstreamUri, ex.getMessage());
                    return serviceUnavailable(exchange);
                });
    }

    private String resolveUpstream(String path) {
        for (ProxyProperties.Route route : props.getRoutes()) {
            if (path.startsWith(route.getPathPrefix())) {
                return route.getUpstream();
            }
        }
        return null;
    }

    private URI buildUri(String upstream, URI original) {
        String query = original.getRawQuery();
        String base = upstream + original.getRawPath();
        return URI.create(query != null ? base + "?" + query : base);
    }

    private HttpHeaders filterRequestHeaders(HttpHeaders original, String upstreamBase) {
        HttpHeaders filtered = new HttpHeaders();
        original.forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                filtered.addAll(name, values);
            }
        });
        // Rewrite Host to match the upstream
        URI upstreamUri = URI.create(upstreamBase);
        filtered.set(HttpHeaders.HOST, upstreamUri.getHost()
                + (upstreamUri.getPort() != -1 ? ":" + upstreamUri.getPort() : ""));
        return filtered;
    }

    private Mono<Void> notFound(ServerWebExchange exchange) {
        return writeError(exchange, HttpStatus.NOT_FOUND, "No route matched the request path");
    }

    private Mono<Void> serviceUnavailable(ServerWebExchange exchange) {
        return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable");
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String detail) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = String.format(
                "{\"status\":%d,\"title\":\"%s\",\"detail\":\"%s\"}",
                status.value(), status.getReasonPhrase(), detail);
        DataBuffer buf = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buf));
    }
}
