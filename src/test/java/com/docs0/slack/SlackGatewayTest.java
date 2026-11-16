package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.config.Docs0Properties;
import com.docs0.web.StatusContributor;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SlackGatewayTest {

    private static Docs0Properties props(String botToken, String appToken) {
        return new Docs0Properties(
                new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "test-model", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(new BigDecimal("100")),
                new Docs0Properties.Slack(botToken, appToken),
                new Docs0Properties.Drive(15, false), null, "", "", "");
    }

    private static MessageEvent message(String channel, String channelType, String ts, String threadTs) {
        MessageEvent e = new MessageEvent();
        e.setChannel(channel);
        e.setChannelType(channelType);
        e.setUser("U1");
        e.setText("what is our pricing?");
        e.setTs(ts);
        e.setThreadTs(threadTs);
        return e;
    }

    @Test
    void reportsOffAndStaysIdleWithoutTokens() {
        Docs0Properties props = props("", "");
        SlackGateway gateway = new SlackGateway(props, new BoltSlackMessenger(props), null);

        gateway.start();

        assertThat(gateway.status()).isEqualTo(
                new StatusContributor.ComponentStatus("Slack", StatusContributor.State.OFF, "no Slack tokens configured"));
        gateway.stop();
    }

    @Test
    void reportsDownUntilConnectedWhenTokensAreSet() {
        Docs0Properties props = props("xoxb-test", "xapp-test");
        SlackGateway gateway = new SlackGateway(props, new BoltSlackMessenger(props), null);

        // Not started: status() itself never calls Slack.
        assertThat(gateway.status().name()).isEqualTo("Slack");
        assertThat(gateway.status().state()).isEqualTo(StatusContributor.State.DOWN);
        assertThat(gateway.status().detail()).doesNotContain("xoxb").doesNotContain("xapp");
    }

    @Test
    void theSliceWiresUpAsSpringBeans() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(org.springframework.jdbc.core.simple.JdbcClient.class, com.docs0.TestDatabase::jdbc);
            context.registerBean(Docs0Properties.class, () -> props("", ""));
            context.registerBean(SlackFakes.FakeAgent.class);
            context.registerBean(SlackFakes.FakeKnowledge.class);
            context.register(com.docs0.tenant.TenantService.class, com.docs0.budget.BudgetService.class,
                    com.docs0.conversation.ConversationRepository.class,
                    com.docs0.conversation.InteractionRepository.class,
                    BoltSlackMessenger.class, QuestionHandler.class, SlackGateway.class);
            context.refresh();

            assertThat(context.getBean(SlackMessenger.class)).isInstanceOf(BoltSlackMessenger.class);
            assertThat(context.getBean(QuestionHandler.class)).isNotNull();
            assertThat(context.getBean(StatusContributor.class).status().state())
                    .isEqualTo(StatusContributor.State.OFF);
        }
    }

    @Test
    void mapsAChannelMention() {
        AppMentionEvent e = new AppMentionEvent();
        e.setChannel("C1");
        e.setUser("U1");
        e.setText("<@UBOT> what is our pricing?");
        e.setTs("100.2");
        e.setThreadTs("100.1");

        assertThat(SlackGateway.fromMention(e, "Ev1")).isEqualTo(new IncomingEvent(IncomingEvent.Kind.MENTION, "C1",
                "U1", "<@UBOT> what is our pricing?", "100.2", "100.1", "Ev1", null, false));
    }

    @Test
    void ignoresEditedMentionsAndMentionsInDms() {
        AppMentionEvent edited = new AppMentionEvent();
        edited.setChannel("C1");
        edited.setEdited(new AppMentionEvent.Edited());
        AppMentionEvent inDm = new AppMentionEvent();
        inDm.setChannel("D1");

        assertThat(SlackGateway.fromMention(edited, "Ev1")).isNull();
        assertThat(SlackGateway.fromMention(inDm, "Ev2")).isNull();
        assertThat(SlackGateway.fromMention(null, "Ev3")).isNull();
    }

    @Test
    void marksMentionsFromBots() {
        AppMentionEvent e = new AppMentionEvent();
        e.setChannel("C1");
        e.setUser("U1");
        e.setTs("100.2");
        e.setBotId("B1");
        e.setSubtype("bot_message");

        IncomingEvent event = SlackGateway.fromMention(e, "Ev1");

        assertThat(event.fromBot()).isTrue();
        assertThat(event.subtype()).isEqualTo("bot_message");
    }

    @Test
    void mapsMessagesByWhereTheyWereWritten() {
        assertThat(SlackGateway.fromMessage(message("D1", "im", "200.1", null), "Ev1")).isEqualTo(new IncomingEvent(
                IncomingEvent.Kind.DIRECT_MESSAGE, "D1", "U1", "what is our pricing?", "200.1", null, "Ev1", null,
                false));
        assertThat(SlackGateway.fromMessage(message("D1", "im", "200.2", "200.1"), "Ev2").threadTs())
                .isEqualTo("200.1");
        assertThat(SlackGateway.fromMessage(message("C1", "channel", "100.2", "100.1"), "Ev3").kind())
                .isEqualTo(IncomingEvent.Kind.THREAD_REPLY);
        assertThat(SlackGateway.fromMessage(message("G1", "group", "100.2", "100.1"), "Ev4").kind())
                .isEqualTo(IncomingEvent.Kind.THREAD_REPLY);
        assertThat(SlackGateway.fromMessage(message("G2", "mpim", "100.2", "100.1"), "Ev5").kind())
                .isEqualTo(IncomingEvent.Kind.THREAD_REPLY);
    }

    @Test
    void ignoresTopLevelChannelMessagesAndEdits() {
        assertThat(SlackGateway.fromMessage(message("C1", "channel", "100.1", null), "Ev1")).isNull();
        assertThat(SlackGateway.fromMessage(message("C1", "channel", "100.1", "100.1"), "Ev2")).isNull();
        MessageEvent edited = message("D1", "im", "200.1", null);
        edited.setEdited(new MessageEvent.Edited());
        assertThat(SlackGateway.fromMessage(edited, "Ev3")).isNull();
        assertThat(SlackGateway.fromMessage(null, "Ev4")).isNull();
    }

    @Test
    void marksMessagesFromBots() {
        MessageEvent e = message("D1", "im", "200.1", null);
        e.setBotId("B1");

        assertThat(SlackGateway.fromMessage(e, "Ev1").fromBot()).isTrue();
    }

    @Test
    void followUpsAreAskedInTheThreadOfTheClickedMessage() {
        assertThat(SlackGateway.followUpThread("C1", "100.1", "100.5")).isEqualTo("100.1");
        assertThat(SlackGateway.followUpThread("C1", null, "100.5")).isEqualTo("100.5");
        assertThat(SlackGateway.followUpThread("D1", null, "200.5")).isNull();
        assertThat(SlackGateway.followUpThread("D1", "200.1", "200.5")).isEqualTo("200.1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void manifestParsesAndDeclaresWhatDocs0Needs() throws Exception {
        Map<String, Object> manifest = new Yaml().load(Files.readString(Path.of("slack/manifest.yml")));

        Map<String, Object> display = (Map<String, Object>) manifest.get("display_information");
        Map<String, Object> features = (Map<String, Object>) manifest.get("features");
        Map<String, Object> botUser = (Map<String, Object>) features.get("bot_user");
        Map<String, Object> appHome = (Map<String, Object>) features.get("app_home");
        Map<String, Object> scopes =
                (Map<String, Object>) ((Map<String, Object>) manifest.get("oauth_config")).get("scopes");
        Map<String, Object> settings = (Map<String, Object>) manifest.get("settings");
        Map<String, Object> events = (Map<String, Object>) settings.get("event_subscriptions");
        Map<String, Object> interactivity = (Map<String, Object>) settings.get("interactivity");

        assertThat(display).containsEntry("name", "docs0");
        assertThat(botUser).containsEntry("display_name", "docs0").containsEntry("always_online", true);
        assertThat(appHome).containsEntry("messages_tab_enabled", true)
                .containsEntry("messages_tab_read_only_enabled", false);
        assertThat((List<String>) scopes.get("bot")).containsExactlyInAnyOrder("app_mentions:read", "chat:write",
                "channels:history", "groups:history", "im:history", "mpim:history", "channels:read", "groups:read",
                "im:read", "mpim:read", "users:read", "reactions:read");
        assertThat((List<String>) events.get("bot_events")).containsExactlyInAnyOrder("app_mention",
                "message.channels", "message.groups", "message.im", "message.mpim", "reaction_added");
        assertThat(settings).containsEntry("socket_mode_enabled", true);
        assertThat(interactivity).containsEntry("is_enabled", true);
    }
}
