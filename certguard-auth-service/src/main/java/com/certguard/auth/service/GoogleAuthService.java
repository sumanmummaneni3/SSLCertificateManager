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
public class GoogleAuthService {

    private static final String PROVIDER = "google";
    private static final String GOOGLE_TOKENINFO_URL =
            "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private final UserRepository userRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${auth.google.client-id}")
    private String clientId;

    @Value("${auth.google.client-secret}")
    private String clientSecret;

    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

    @PostConstruct
    void checkConfig() {
        if (clientId == null || clientId.isBlank()) {
            log.warn("GOOGLE_CLIENT_ID is not set — Google OAuth will not work");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            log.warn("GOOGLE_CLIENT_SECRET is not set — Google authorization code flow will not work");
        }
    }

    /**
     * Validates a Google ID token (issued by GSI / One Tap) and upserts the local user record.
     * Returns the user — caller is responsible for session creation.
     */
    @Transactional
    public User authenticateWithIdToken(String idToken) {
        JsonNode tokenInfo = fetchTokenInfo(idToken);
        validateAudience(tokenInfo);

        String sub   = tokenInfo.path("sub").asText(null);
        String email = tokenInfo.path("email").asText(null);
        String name  = tokenInfo.path("name").asText(email);
        boolean emailVerified = tokenInfo.path("email_verified").asBoolean(false);

        if (sub == null || email == null) {
            throw new AuthException("Google token missing required claims (sub, email)");
        }

        return upsertUser(sub, email, name, emailVerified);
    }

    private JsonNode fetchTokenInfo(String idToken) {
        try {
            String json = webClient.get()
                    .uri(GOOGLE_TOKENINFO_URL + idToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return objectMapper.readTree(json);
        } catch (WebClientResponseException e) {
            log.warn("Google tokeninfo rejected token: {}", e.getResponseBodyAsString());
            throw new AuthException("Invalid Google ID token");
        } catch (Exception e) {
            log.error("Google tokeninfo call failed", e);
            throw new AuthException("Could not verify Google token");
        }
    }

    private void validateAudience(JsonNode tokenInfo) {
        String aud = tokenInfo.path("aud").asText("");
        if (!clientId.equals(aud)) {
            throw new AuthException("Google token audience mismatch");
        }
        String exp = tokenInfo.path("exp").asText("0");
        if (Instant.ofEpochSecond(Long.parseLong(exp)).isBefore(Instant.now())) {
            throw new AuthException("Google token has expired");
        }
    }

    private User upsertUser(String sub, String email, String name, boolean emailVerified) {
        return userRepository.findByProviderIdAndProviderUserId(PROVIDER, sub)
                .map(u -> {
                    u.setEmail(email);
                    u.setName(name);
                    u.setEmailVerified(emailVerified);
                    return userRepository.save(u);
                })
                .orElseGet(() -> {
                    // Also check by email in case the user already registered via another provider
                    return userRepository.findByEmail(email).orElseGet(() ->
                            userRepository.save(User.builder()
                                    .providerId(PROVIDER)
                                    .providerUserId(sub)
                                    .email(email)
                                    .name(name)
                                    .emailVerified(emailVerified)
                                    .build()));
                });
    }

    /**
     * Exchanges a Google authorization code for tokens, then validates the returned ID token.
     * Used by the callback endpoint after Google redirects back to the service.
     */
    @Transactional
    public User authenticateWithCode(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code",          code);
        body.add("client_id",     clientId);
        body.add("client_secret", clientSecret);
        body.add("redirect_uri",  redirectUri);
        body.add("grant_type",    "authorization_code");

        JsonNode tokenResponse;
        try {
            String json = webClient.post()
                    .uri(GOOGLE_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            tokenResponse = objectMapper.readTree(json);
        } catch (WebClientResponseException e) {
            log.warn("Google code exchange failed: {}", e.getResponseBodyAsString());
            throw new AuthException("Google authorization code exchange failed");
        } catch (Exception e) {
            log.error("Google code exchange error", e);
            throw new AuthException("Could not complete Google authentication");
        }

        String idToken = tokenResponse.path("id_token").asText(null);
        if (idToken == null) {
            throw new AuthException("Google did not return an id_token");
        }
        return authenticateWithIdToken(idToken);
    }

    /** Build the Google OAuth2 authorization URL (for server-side redirect flows). */
    public String buildAuthorizationUrl(String redirectUri) {
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&scope=openid%20email%20profile"
                + "&redirect_uri=" + redirectUri
                + "&access_type=offline"
                + "&prompt=consent";
    }
}
