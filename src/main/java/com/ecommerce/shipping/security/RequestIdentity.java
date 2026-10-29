package com.ecommerce.shipping.security;

import com.ecommerce.shipping.exception.AccessDeniedException;
import com.ecommerce.shipping.exception.AuthenticationRequiredException;

/**
 * The gateway verifies the JWT and sets X-Username / X-User-Role; this service treats them as authoritative.
 * Roles are compared exactly (case-sensitive, untrimmed): only the literal ADMIN is an admin.
 */
public final class RequestIdentity {

    public static final String ADMIN = "ADMIN";

    private RequestIdentity() { }

    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    /** 401 without a non-blank username. */
    public static void requireAuthenticated(String username) {
        if (username == null || username.isBlank()) {
            throw new AuthenticationRequiredException();
        }
    }

    /** 401 without identity, 403 for anyone but an admin. */
    public static void requireAdmin(String username, String role) {
        requireAuthenticated(username);
        if (!isAdmin(role)) {
            throw new AccessDeniedException();
        }
    }

    /** 401 without identity, 403 unless the caller is the owner or an admin. */
    public static void requireOwnerOrAdmin(String username, String role, String ownerId) {
        requireAuthenticated(username);
        if (!isAdmin(role) && !username.equals(ownerId)) {
            throw new AccessDeniedException();
        }
    }
}
