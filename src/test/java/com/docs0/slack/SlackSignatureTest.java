package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlackSignatureTest {

    private static final String SECRET = "8f742231b10e8888abcd99yyyzzz85a5";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String TS = Long.toString(NOW.getEpochSecond());
    private static final byte[] BODY = "{\"type\":\"event_callback\",\"team_id\":\"T1\"}".getBytes(StandardCharsets.UTF_8);

    private static String sign(String ts, byte[] body) {
        return SlackSignature.sign(SECRET, ts, body);
    }

    @Test
    void matchesSlacksDocumentedExample() {
        // The worked example in Slack's "Verifying requests from Slack" guide.
        String secret = "8f742231b10e8888abcd99yyyzzz85a5";
        String body = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V"
                + "&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text="
                + "&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN"
                + "&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";
        assertThat(SlackSignature.sign(secret, "1531420618", body.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503");
    }

    @Test
    void acceptsACorrectlySignedRecentRequest() {
        assertThat(SlackSignature.valid(SECRET, TS, sign(TS, BODY), BODY, NOW)).isTrue();
        assertThat(SlackSignature.valid(SECRET, TS, sign(TS, BODY), BODY, NOW.plusSeconds(299))).isTrue();
        assertThat(SlackSignature.valid(SECRET, TS, sign(TS, BODY), BODY, NOW.minusSeconds(299))).isTrue();
    }

    @Test
    void rejectsTheWrongSecretATamperedBodyAndATamperedTimestamp() {
        assertThat(SlackSignature.valid("another-secret", TS, sign(TS, BODY), BODY, NOW)).isFalse();
        byte[] tampered = "{\"type\":\"event_callback\",\"team_id\":\"T2\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(SlackSignature.valid(SECRET, TS, sign(TS, BODY), tampered, NOW)).isFalse();
        String later = Long.toString(NOW.getEpochSecond() + 1);
        assertThat(SlackSignature.valid(SECRET, later, sign(TS, BODY), BODY, NOW)).isFalse();
    }

    @Test
    void rejectsMissingOrBlankHeadersAndSecrets() {
        String signature = sign(TS, BODY);
        assertThat(SlackSignature.valid(SECRET, null, signature, BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, null, BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, signature, null, NOW)).isFalse();
        assertThat(SlackSignature.valid(null, TS, signature, BODY, NOW)).isFalse();
        // With no signing secret configured (the pilot) nothing can be valid.
        assertThat(SlackSignature.valid("", TS, signature, BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid("  ", TS, signature, BODY, NOW)).isFalse();
    }

    @Test
    void rejectsStaleAndFutureTimestampsEvenWhenSignedCorrectly() {
        String old = Long.toString(NOW.minusSeconds(301).getEpochSecond());
        String future = Long.toString(NOW.plusSeconds(301).getEpochSecond());
        assertThat(SlackSignature.valid(SECRET, old, sign(old, BODY), BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, future, sign(future, BODY), BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, "0", sign("0", BODY), BODY, NOW)).isFalse();
    }

    @Test
    void rejectsMalformedTimestampsAndSignatureFormats() {
        String good = sign(TS, BODY);
        assertThat(SlackSignature.valid(SECRET, "abc", sign("abc", BODY), BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, "", sign("", BODY), BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, good.substring(3), BODY, NOW)).isFalse(); // no v0= prefix
        assertThat(SlackSignature.valid(SECRET, TS, "V0=" + good.substring(3), BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, "v1=" + good.substring(3), BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, good.toUpperCase().replace("V0=", "v0="), BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, good + "0", BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, good.substring(0, good.length() - 1), BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, " " + good, BODY, NOW)).isFalse();
        assertThat(SlackSignature.valid(SECRET, TS, "v0=", BODY, NOW)).isFalse();
    }

    @Test
    void comparesInConstantTime() throws Exception {
        assertThat(SlackSignature.constantTimeEquals("v0=abc", "v0=abc")).isTrue();
        assertThat(SlackSignature.constantTimeEquals("v0=abc", "v0=abd")).isFalse();
        assertThat(SlackSignature.constantTimeEquals("v0=abc", "v0=ab")).isFalse();
        // The check must go through the helper (MessageDigest.isEqual), never String.equals on the signature.
        String source = Files.readString(Path.of("src/main/java/com/docs0/slack/SlackSignature.java"));
        assertThat(source).contains("MessageDigest.isEqual").contains("constantTimeEquals(expected, signature)")
                .doesNotContain("expected.equals(").doesNotContain("signature.equals(");
    }
}
