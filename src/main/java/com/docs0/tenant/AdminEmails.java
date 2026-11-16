package com.docs0.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The comma-separated {@code tenant.admin_emails}: who may sign in to a company's admin pages by email. */
public final class AdminEmails {

    public static final int MAX = 20;
    private static final int MAX_LENGTH = 254;
    private static final Pattern VALID = Pattern.compile("[^\\s@,;<>\"'()\\\\]+@[^\\s@,;<>\"'()\\\\]+\\.[^\\s@,;<>\"'()\\\\]+");

    private AdminEmails() {}

    /** The stored list as lower-case emails, without blanks or duplicates. */
    public static List<String> parse(String stored) {
        List<String> emails = new ArrayList<>();
        if (stored != null) {
            for (String part : stored.split(",")) {
                String email = part.strip().toLowerCase(Locale.ROOT);
                if (!email.isEmpty() && !emails.contains(email)) {
                    emails.add(email);
                }
            }
        }
        return emails;
    }

    public static String join(List<String> emails) {
        return emails.isEmpty() ? null : String.join(",", emails);
    }

    /** True for something that looks like one email address. */
    public static boolean valid(String email) {
        return email != null && email.length() <= MAX_LENGTH && VALID.matcher(email).matches();
    }

    public static boolean contains(String stored, String email) {
        return email != null && parse(stored).contains(email.strip().toLowerCase(Locale.ROOT));
    }
}
