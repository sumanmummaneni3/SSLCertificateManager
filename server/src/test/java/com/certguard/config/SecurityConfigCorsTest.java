package com.certguard.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit-level tests for the CORS guard in {@link SecurityConfig}.
 * Constructs only the SecurityConfig bean (no Spring context needed) and
 * invokes {@code corsConfigurationSource()} directly.
 *
 * Covered cases:
 *  - prod mode + empty origins  → IllegalStateException
 *  - prod mode + wildcard "*"   → IllegalStateException
 *  - dev  mode + empty origins  → allowed (uses allowedOriginPatterns)
 *  - prod mode + explicit origin → no exception
 */
class SecurityConfigCorsTest {

    private SecurityConfig buildConfig(boolean devMode, String rawOrigins) {
        // SecurityConfig requires 7 collaborators; pass nulls — we only invoke
        // corsConfigurationSource() which does not use any of them.
        SecurityConfig cfg = new SecurityConfig(null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(cfg, "devMode", devMode);
        ReflectionTestUtils.setField(cfg, "corsAllowedOriginsRaw", rawOrigins);
        return cfg;
    }

    @Test
    void prodModeEmptyOrigins_throws() {
        SecurityConfig cfg = buildConfig(false, "");
        assertThatIllegalStateException()
                .isThrownBy(cfg::corsConfigurationSource)
                .withMessageContaining("app.cors.allowed-origins")
                .withMessageContaining("app.dev-mode=false");
    }

    @Test
    void prodModeWildcardOrigin_throws() {
        SecurityConfig cfg = buildConfig(false, "*");
        assertThatIllegalStateException()
                .isThrownBy(cfg::corsConfigurationSource)
                .withMessageContaining("app.cors.allowed-origins")
                .withMessageContaining("app.dev-mode=false");
    }

    @Test
    void devModeEmptyOrigins_doesNotThrow() {
        SecurityConfig cfg = buildConfig(true, "");
        assertThatNoException().isThrownBy(cfg::corsConfigurationSource);
    }

    @Test
    void devModeWildcard_doesNotThrow() {
        SecurityConfig cfg = buildConfig(true, "*");
        assertThatNoException().isThrownBy(cfg::corsConfigurationSource);
    }

    @Test
    void prodModeExplicitOrigin_doesNotThrow() {
        SecurityConfig cfg = buildConfig(false, "https://app.example.com");
        assertThatNoException().isThrownBy(cfg::corsConfigurationSource);
    }
}
