package com.certguard.security;

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
    private final boolean platformAdmin;
    private final String orgRole;   // ADMIN | ENGINEER | VIEWER | null for platform admins
    private final Map<String, Object> attributes;
    private final OidcIdToken idToken;
    private final OidcUserInfo userInfo;

    public CertGuardUserPrincipal(UUID userId, UUID orgId, String email,
                                   boolean platformAdmin, String orgRole,
                                   Map<String, Object> attributes,
                                   OidcIdToken idToken, OidcUserInfo userInfo) {
        this.userId = userId;
        this.orgId = orgId;
        this.email = email;
        this.platformAdmin = platformAdmin;
        this.orgRole = orgRole;
        this.attributes = attributes != null ? attributes : Map.of();
        this.idToken = idToken;
        this.userInfo = userInfo;
    }

    public CertGuardUserPrincipal(UUID userId, UUID orgId, String email,
                                   boolean platformAdmin, String orgRole) {
        this(userId, orgId, email, platformAdmin, orgRole, Map.of(), null, null);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (platformAdmin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
        }
        if (orgRole != null && !orgRole.isBlank()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + orgRole));
        }
        return authorities;
    }

    @Override public String getPassword()  { return null; }
    @Override public String getUsername()  { return email; }
    @Override public String getName()      { return email; }
    @Override public Map<String, Object> getClaims()     { return attributes; }
    @Override public OidcUserInfo getUserInfo()           { return userInfo; }
    @Override public OidcIdToken getIdToken()             { return idToken; }
    @Override public Map<String, Object> getAttributes() { return attributes; }
}
