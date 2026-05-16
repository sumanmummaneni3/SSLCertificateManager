package com.certguard.auth.controller;

import com.certguard.auth.security.UnifiedTokenProvider;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/.well-known")
@RequiredArgsConstructor
public class JwksController {

    private final UnifiedTokenProvider tokenProvider;

    @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        RSAPublicKey pub = tokenProvider.getPublicKey();
        RSAKey rsaKey = new RSAKey.Builder(pub)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(new com.nimbusds.jose.Algorithm("RS256"))
                .keyID("certguard-auth-key-1")
                .build();
        return new JWKSet(rsaKey).toJSONObject();
    }
}
