package com.certguard.service;

import com.certguard.dto.sales.CreateSalesKeyRequest;
import com.certguard.dto.sales.SalesKeyResponse;
import com.certguard.entity.SalesApiKey;
import com.certguard.entity.User;
import com.certguard.enums.SalesKeyStatus;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.SalesApiKeyRepository;
import com.certguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalesApiKeyService {

    private final SalesApiKeyRepository salesApiKeyRepository;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * Generates a new sales API key, hashes it, persists the entity, and returns
     * the response with the plain key populated (only on creation).
     *
     * @param req             key creation request (label + optional expiry)
     * @param createdByUserId UUID of the PLATFORM_ADMIN performing the action
     */
    @Transactional
    public SalesKeyResponse createKey(CreateSalesKeyRequest req, UUID createdByUserId) {
        // Generate a long, high-entropy plain key: "SLS-" + 64 hex chars (~70 total)
        String plainKey = "SLS-"
                + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");

        String keyHash = passwordEncoder.encode(plainKey);

        User createdBy = createdByUserId != null
                ? userRepository.findById(createdByUserId).orElse(null)
                : null;

        SalesApiKey entity = SalesApiKey.builder()
                .label(req.getLabel())
                .keyHash(keyHash)
                .status(SalesKeyStatus.ACTIVE)
                .createdBy(createdBy)
                .expiresAt(req.getExpiresAt())
                .build();

        entity = salesApiKeyRepository.save(entity);

        log.info("Sales API key created: label='{}', id={}, by={}",
                entity.getLabel(), entity.getId(), createdByUserId);

        return toResponse(entity, plainKey);
    }

    /**
     * Returns all sales API keys ordered by creation date descending.
     * The {@code plainKey} field is always null in this response.
     */
    @Transactional(readOnly = true)
    public List<SalesKeyResponse> listKeys() {
        return salesApiKeyRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(k -> toResponse(k, null))
                .collect(Collectors.toList());
    }

    /**
     * Revokes the specified key by setting its status to REVOKED.
     *
     * @throws ResourceNotFoundException if the key does not exist
     */
    @Transactional
    public void revokeKey(UUID keyId) {
        SalesApiKey key = salesApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ResourceNotFoundException("Sales API key not found: " + keyId));
        key.setStatus(SalesKeyStatus.REVOKED);
        salesApiKeyRepository.save(key);
        log.warn("Sales API key revoked: label='{}', id={}", key.getLabel(), keyId);
    }

    // ── DTO mapper ─────────────────────────────────────────────────────────

    private SalesKeyResponse toResponse(SalesApiKey key, String plainKey) {
        return SalesKeyResponse.builder()
                .id(key.getId())
                .label(key.getLabel())
                .status(key.getStatus())
                .createdAt(key.getCreatedAt())
                .lastUsedAt(key.getLastUsedAt())
                .expiresAt(key.getExpiresAt())
                .plainKey(plainKey)
                .build();
    }
}
