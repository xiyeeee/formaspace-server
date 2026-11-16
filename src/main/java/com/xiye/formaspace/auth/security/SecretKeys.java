package com.xiye.formaspace.auth.security;

import java.util.Base64;

public final class SecretKeys {
    private SecretKeys() {}

    public static byte[] decode(String value, String variable) {
        try {
            byte[] key = Base64.getDecoder().decode(value == null ? "" : value);
            if (key.length >= 32) {
                return key;
            }
        } catch (IllegalArgumentException ignored) {
            // Never include the supplied value or decoder exception in startup logs.
        }
        throw new IllegalStateException(variable + " 必须为至少 32 随机字节的 Base64 编码");
    }
}
