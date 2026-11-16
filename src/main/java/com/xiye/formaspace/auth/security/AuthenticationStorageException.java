package com.xiye.formaspace.auth.security;

import org.springframework.security.core.AuthenticationException;

/** Carries no JDBC details; the Bearer failure handler serializes this as 503. */
public class AuthenticationStorageException extends AuthenticationException {
    public AuthenticationStorageException() {
        super("Authentication storage unavailable");
    }
}
