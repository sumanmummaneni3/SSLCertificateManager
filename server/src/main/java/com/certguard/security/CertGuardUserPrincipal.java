package com.certguard.security;

import com.certguard.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.*;

@Getter
public class CertGuardUserPrincipal implements OidcUser, UserDetails {

    private final UUID userId;
    private final UUID orgId;
    private final String email;
    private final String role;
    private final Map<String, Object> attributes;
    private final OidcIdToken idToken;
    private final OidcUserInfo userInfo;

    public CertGuardUserPrincipal(UUID userId, UUID orgId, String email, String role,
                                   Map<String, Object> attributes, OidcIdToken idToken, OidcUserInfo userInfo) {
        this.userId = userId;
        this.orgId = orgId;
        this.email = email;
        this.role = role;
        this.attributes = attributes != null ? attributes : Map.of();
        this.idToken = idToken;
        this.userInfo = userInfo;
    }

    // Constructor for JWT filter (no OIDC context)
    public CertGuardUserPrincipal(UUID userId, UUID orgId, String email, String role) {
        this(userId, orgId, email, role, Map.of(), null, null);
    }

    public static CertGuardUserPrincipal create(User user, Map<String, Object> attributes,
                                                 OidcIdToken idToken, OidcUserInfo userInfo) {
        return new CertGuardUserPrincipal(
                user.getId(), user.getOrganization().getId(),
                user.getEmail(), user.getRole().name(),
                attributes, idToken, userInfo);
    }

    public static CertGuardUserPrincipal create(User user) {
        return new CertGuardUserPrincipal(
                user.getId(), user.getOrganization().getId(),
                user.getEmail(), user.getRole().name());
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getPassword()  { return null; }
    @Override public String getUsername()  { return email; }
    @Override public String getName()      { return email; }
    @Override public Map<String, Object> getClaims() { return attributes; }
    @Override public OidcUserInfo getUserInfo() { return userInfo; }
    @Override public OidcIdToken getIdToken()   { return idToken; }
    @Override public Map<String, Object> getAttributes() { return attributes; }
}
