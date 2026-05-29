package com.certguard.renewal.ca;

import com.certguard.renewal.entity.CaProviderConfig;
import com.certguard.renewal.repository.CaProviderConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the correct CaProvider using a four-tier priority chain:
 *   1. Per-request override (explicitly chosen by the user in the UI)
 *   2. Per-target preferred provider (targets.preferred_ca_provider in core)
 *   3. Per-org default (ca_provider_configs where org_id = <orgId>)
 *   4. Platform default (ca_provider_configs where is_platform_default = true)
 *   5. Fallback: NoopCaProvider
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaProviderRegistry {

    private final List<CaProvider> providers;
    private final CaProviderConfigRepository configRepository;
    private final NoopCaProvider noopCaProvider;

    public Map<String, CaProvider> providerMap() {
        return providers.stream()
                .collect(Collectors.toMap(CaProvider::type, Function.identity()));
    }

    /**
     * @param requestedProvider   per-request override from the UI (may be null/blank)
     * @param targetPreferredProvider  per-target preferred provider (may be null/blank)
     * @param orgId               org to look up org-level default
     */
    public CaProvider resolve(String requestedProvider, String targetPreferredProvider, UUID orgId) {
        Map<String, CaProvider> map = providerMap();

        // Tier 1: per-request
        CaProvider p = lookup(map, requestedProvider);
        if (p != null) {
            log.debug("CA provider resolved via per-request override: {}", requestedProvider);
            return p;
        }

        // Tier 2: per-target preferred
        p = lookup(map, targetPreferredProvider);
        if (p != null) {
            log.debug("CA provider resolved via per-target preference: {}", targetPreferredProvider);
            return p;
        }

        // Tier 3: per-org default from DB
        if (orgId != null) {
            Optional<CaProviderConfig> orgDefault = configRepository.findByOrgId(orgId)
                    .stream().findFirst();
            if (orgDefault.isPresent()) {
                p = lookup(map, orgDefault.get().getProviderType());
                if (p != null) {
                    log.debug("CA provider resolved via org default: {}", orgDefault.get().getProviderType());
                    return p;
                }
            }
        }

        // Tier 4: platform default from DB
        Optional<CaProviderConfig> platformDefault = configRepository.findByPlatformDefaultTrue();
        if (platformDefault.isPresent()) {
            p = lookup(map, platformDefault.get().getProviderType());
            if (p != null) {
                log.debug("CA provider resolved via platform default: {}", platformDefault.get().getProviderType());
                return p;
            }
        }

        // Tier 5: built-in NOOP
        log.warn("No CA provider resolved — falling back to NOOP (orgId: {})", orgId);
        return noopCaProvider;
    }

    public List<Map<String, String>> listProviderOptions() {
        return providers.stream()
                .filter(p -> !"NOOP".equals(p.type()))
                .map(p -> Map.of("type", p.type(), "label", friendlyLabel(p.type())))
                .collect(Collectors.toList());
    }

    private CaProvider lookup(Map<String, CaProvider> map, String type) {
        if (type == null || type.isBlank()) return null;
        return map.get(type.toUpperCase());
    }

    private String friendlyLabel(String type) {
        return switch (type.toUpperCase()) {
            case "LETS_ENCRYPT" -> "Let's Encrypt (ACME)";
            case "DIGICERT"     -> "DigiCert";
            case "SECTIGO"      -> "Sectigo";
            case "INTERNAL"     -> "Internal CA";
            default             -> type;
        };
    }
}
