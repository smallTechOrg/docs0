package com.docs0.slack;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Checks that a request really came from Slack: {@code X-Slack-Signature} = "v0=" + HMAC-SHA256 of "v0:ts:body". */
public final class SlackSignature {

    public static final String SIGNATURE_HEADER = "X-Slack-Signature";
    public static final String TIMESTAMP_HEADER = "X-Slack-Request-Timestamp";
    static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final String PREFIX = "v0=";

    private SlackSignature() {}

    /**
     * True only for a correctly signed request made within five minutes of {@code now} (earlier or later). Works on
     * the raw body bytes, so nothing is parsed or re-serialised first. Any missing or malformed input is false.
     */
    public static boolean valid(String signingSecret, String timestamp, String signature, byte[] rawBody, Instant now) {
        if (signingSecret == null || signingSecret.isBlank() || timestamp == null || signature == null
                || rawBody == null || !signature.startsWith(PREFIX)) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.strip());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(MAX_AGE) > 0) {
            return false;
        }
        String expected = PREFIX + HexFormat.of().formatHex(hmac(signingSecret, timestamp.strip(), rawBody));
        return constantTimeEquals(expected, signature);
    }

    /** The signature Slack would send. For tests and tools. */
    public static String sign(String signingSecret, String timestamp, byte[] rawBody) {
        return PREFIX + HexFormat.of().formatHex(hmac(signingSecret, timestamp, rawBody));
    }

    /** Equal strings, without revealing through timing where they differ. */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available");
        }
    }
}
