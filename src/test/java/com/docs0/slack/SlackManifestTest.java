package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The two Slack app manifests and the scope list the install button uses must not drift apart. */
class SlackManifestTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String file) throws Exception {
        return new Yaml().load(Files.readString(Path.of("slack", file)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> botScopes(Map<String, Object> manifest) {
        return (List<String>) map(map(manifest, "oauth_config"), "scopes").get("bot");
    }

    @SuppressWarnings("unchecked")
    private static List<String> botEvents(Map<String, Object> manifest) {
        return (List<String>) map(map(manifest, "settings"), "event_subscriptions").get("bot_events");
    }

    @Test
    void bothManifestsRequestTheSameBotScopesAndEvents() throws Exception {
        Map<String, Object> pilot = load("manifest.yml");
        Map<String, Object> distributed = load("manifest-distributed.yml");

        assertThat(botScopes(distributed)).containsExactlyInAnyOrderElementsOf(botScopes(pilot));
        assertThat(botEvents(distributed)).containsExactlyInAnyOrderElementsOf(botEvents(pilot));
        assertThat(map(distributed, "features")).isEqualTo(map(pilot, "features"));
    }

    @Test
    void theScopeConstantMatchesBothManifests() throws Exception {
        for (String file : List.of("manifest.yml", "manifest-distributed.yml")) {
            Map<String, Object> manifest = load(file);
            assertThat(botScopes(manifest)).as(file).containsExactlyInAnyOrderElementsOf(SlackScopes.BOT);
            assertThat(botEvents(manifest)).as(file).containsExactlyInAnyOrderElementsOf(SlackScopes.BOT_EVENTS);
        }
    }

    @Test
    void theDistributedManifestUsesHttpInsteadOfSocketMode() throws Exception {
        Map<String, Object> settings = map(load("manifest-distributed.yml"), "settings");

        assertThat(settings).containsEntry("socket_mode_enabled", false);
        assertThat(map(settings, "event_subscriptions")).containsEntry("request_url", "https://YOUR-DOMAIN/slack/events");
        assertThat(map(settings, "interactivity")).containsEntry("is_enabled", true)
                .containsEntry("request_url", "https://YOUR-DOMAIN/slack/interactions");
        assertThat(map(load("manifest-distributed.yml"), "oauth_config").get("redirect_urls")).asList()
                .contains("https://YOUR-DOMAIN/slack/oauth/callback", "https://YOUR-DOMAIN/admin/login/callback");
        assertThat(map(load("manifest.yml"), "settings")).containsEntry("socket_mode_enabled", true);
    }

    @Test
    void theInstallLinkAsksForExactlyTheManifestScopes() {
        String url = new HttpSlackOAuth("cid", "secret", "https://docs0.example/slack/oauth/callback", "unused")
                .authorizeUrl("s");
        String scope = java.net.URLDecoder.decode(url.replaceAll(".*[?&]scope=([^&]*).*", "$1"),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(scope.split(",")).containsExactlyInAnyOrderElementsOf(SlackScopes.BOT);
    }
}
