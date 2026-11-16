package com.xiye.formaspace.common.validation;

import java.util.Locale;

public final class AccountIdentifiers {
    private AccountIdentifiers() {}

    public static String normalize(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
