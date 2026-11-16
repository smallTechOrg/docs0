package com.docs0.source.gmail;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** One chosen mailbox and the extra people who may also see it (a shared inbox such as support@). Lowercase. */
public record Mailbox(String email, List<String> viewers) {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s,;<>\"']{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");

    public Mailbox {
        String own = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        email = own;
        viewers = viewers == null ? List.of() : viewers.stream().filter(v -> v != null && !v.isBlank())
                .map(v -> v.strip().toLowerCase(Locale.ROOT)).filter(v -> !v.equals(own)).distinct().toList();
    }

    /** True for text that looks like an address on a domain, e.g. {@code sam@example.com}. */
    public static boolean valid(String email) {
        return email != null && email.strip().length() <= 254 && EMAIL.matcher(email.strip()).matches();
    }
}
