package com.certguard.auth.service;

import com.certguard.auth.entity.User;
import com.certguard.auth.exception.AuthException;
import com.certguard.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class MicrosoftAuthService {

    private static final String PROVIDER = "microsoft";
    private static final String GRAPH_ME_URL = "https://graph.microsoft.com/v1.0/me";

    private final UserRepository userRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${auth.microsoft.client-id}")
    private String clientId;

    @Value("${auth.microsoft.client-secret}")
    private String clientSecret;

    @Value("${auth.microsoft.tenant-id:common}")
    private String tenantId;

    @PostConstruct
    void checkConfig() {
        if (clientId == null || clientId.isBlank()) {
            log.warn("MICROSOFT_CLIENT_ID is not set — Microsoft OAuth will not work");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            log.warn("MICROSOFT_CLIENT_SECRET is not set — Microsoft OAuth will not work");
        }
    }

    /**
     * Exchanges an authorization {@code code} for tokens, calls Microsoft Graph /me,
     * and upserts the local user record.
     */
    @Transactional
    public User authenticateWithCode(String code, String redirectUri) {
        JsonNode tokenResponse = exchangeCode(code, redirectUri);
        String accessToken  = tokenResponse.path("access_token").asText(null);
        String refreshToken = tokenResponse.path("refresh_token").asText(null);
        long   expiresIn    = tokenResponse.path("expires_in").asLong(3600);

        if (accessToken == null) {
            throw new AuthException("Microsoft token exchange did not return an access token");
        }

        JsonNode me = fetchMeProfile(accessToken);
        String oid   = me.path("id").asText(null);
        String email = me.path("mail").asText(me.path("userPrincipalName").asText(null));
        String name  = me.path("displayName").asText(email);

        if (oid == null || email == null) {
            throw new AuthException("Microsoft Graph /me missing required fields");
        }

        Instant tokenExpiry = Instant.now().plusSeconds(expiresIn);
        return upsertUser(oid, email, name, accessToken, refreshToken, tokenExpiry);
    }

    private JsonNode exchangeCode(String code, String redirectUri) {
        String tokenUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id",     clientId);
        body.add("client_secret", clientSecret);
        body.add("code",          code);
        body.add("redirect_uri",  redirectUri);
        body.add("grant_type",    "authorization_code");
        body.add("scope",         "openid email profile User.Read offline_access");

        try {
            String json = webClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return objectMapper.readTree(json);
        } catch (WebClientResponseException e) {
            log.warn("Microsoft token exchange failed: {}", e.getResponseBodyAsString());
            throw new AuthException("Microsoft authorization code exchange failed");
        } catch (Exception e) {
            log.error("Microsoft token exchange error", e);
            throw new AuthException("Could not complete Microsoft authentication");
        }
    }

    private JsonNode fetchMeProfile(String accessToken) {
        try {
            String json = webClient.get()
                    .uri(GRAPH_ME_URL)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return objectMapper.readTree(json);
        } catch (WebClientResponseException e) {
            log.warn("Microsoft Graph /me failed: {}", e.getResponseBodyAsString());
            throw new AuthException("Could not retrieve Microsoft user profile");
        } catch (Exception e) {
            log.error("Microsoft Graph error", e);
            throw new AuthException("Could not retrieve Microsoft user profile");
        }
    }

    private User upsertUser(String oid, String email, String name,
                             String accessToken, String refreshToken, Instant tokenExpiry) {
        return userRepository.findByProviderIdAndProviderUserId(PROVIDER, oid)
                .map(u -> {
                    u.setEmail(email);
                    u.setName(name);
                    u.setAccessToken(accessToken);
                    u.setRefreshToken(refreshToken);
                    u.setTokenExpiresAt(tokenExpiry);
                    u.setEmailVerified(true);
                    return userRepository.save(u);
                })
                .orElseGet(() -> userRepository.save(User.builder()
                        .providerId(PROVIDER)
                        .providerUserId(oid)
                        .email(email)
                        .name(name)
                        .accessToken(accessToken)
                        .refreshToken(refreshToken)
                        .tokenExpiresAt(tokenExpiry)
                        .emailVerified(true)
                        .build()));
    }

    public String buildAuthorizationUrl(String redirectUri) {
        return "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/authorize"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&scope=openid%20email%20profile%20User.Read%20offline_access"
                + "&redirect_uri=" + redirectUri
                + "&response_mode=query";
    }
}
