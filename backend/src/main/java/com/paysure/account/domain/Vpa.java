package com.paysure.account.domain;

import com.paysure.common.error.BadRequestException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Value object for a UPI-style virtual payment address, e.g. {@code ananya@paysure}.
 * Always lower-case; the handle part is 3-30 chars of {@code [a-z0-9._-]} starting with a letter or digit.
 */
public record Vpa(String value) {

    private static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,29}$");
    private static final Pattern FULL = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,29}@[a-z]{3,20}$");
    /** Handles that could be confused with system identities. */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "system", "treasury", "paysure", "support", "root", "npci", "bank", "upi");

    public Vpa {
        if (value == null || !FULL.matcher(value).matches()) {
            throw new BadRequestException("INVALID_VPA", "Invalid VPA format.");
        }
    }

    /** Builds a VPA from a user-chosen handle, applying normalisation and reserved-name rules. */
    public static Vpa fromHandle(String rawHandle, String suffix) {
        String handle = rawHandle == null ? "" : rawHandle.trim().toLowerCase(Locale.ROOT);
        if (!HANDLE.matcher(handle).matches()) {
            throw new BadRequestException("INVALID_VPA_HANDLE",
                    "Handle must be 3-30 characters: letters, digits, '.', '_' or '-', starting with a letter or digit.");
        }
        if (RESERVED.contains(handle)) {
            throw new BadRequestException("VPA_HANDLE_RESERVED", "This handle is reserved.");
        }
        return new Vpa(handle + "@" + suffix);
    }

    /** Parses a full VPA supplied by a client (e.g. a payee), normalising case and whitespace. */
    public static Vpa parse(String raw) {
        return new Vpa(raw == null ? null : raw.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
