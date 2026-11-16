package com.docs0.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.config.Docs0Properties;
import com.docs0.knowledge.DocumentStore;
import com.docs0.slack.BoltSlackMessenger;
import com.docs0.slack.IdentityService;
import com.docs0.slack.SlackMessenger.ChannelInfo;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.slack.SlackChannel;
import com.docs0.source.slack.SlackHistoryConnection;
import com.docs0.source.slack.SlackHistoryConnector;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real-credential, read-only check of the Slack history connector: lists the channels the bot can see,
 * then syncs the last day of one channel into the test database and prints counts only. It never posts
 * anything and never prints a channel name, a message, an email or a token.
 *
 * <p>Skipped unless DOCS0_LIVE=1. Needs DOCS0_SLACK_BOT_TOKEN in the environment; set
 * DOCS0_LIVE_SLACK_CHANNEL to a channel id to pick the channel, otherwise the first public channel the
 * bot is already in is used. Run by hand, never as part of the gate.
 */
@EnabledIfEnvironmentVariable(named = "DOCS0_LIVE", matches = "1")
@EnabledIfEnvironmentVariable(named = "DOCS0_SLACK_BOT_TOKEN", matches = ".+")
class LiveSlackHistoryCheck extends IntegrationTest {

    @Autowired
    SourceRepository sources;

    @Autowired
    DocumentStore store;

    @Autowired
    SyncRunRepository runs;

    @Test
    void listsChannelsAndRendersTheLastDayOfOne() throws Exception {
        Docs0Properties props = new Docs0Properties(null, null, null,
                new Docs0Properties.Slack(System.getenv("DOCS0_SLACK_BOT_TOKEN"), ""), null, null, null, null, null);
        BoltSlackMessenger messenger = new BoltSlackMessenger(props);

        List<ChannelInfo> channels = messenger.listChannels();
        long publicCount = channels.stream().filter(c -> !c.isPrivate()).count();
        long privateCount = channels.stream().filter(ChannelInfo::isPrivate).count();
        long joined = channels.stream().filter(ChannelInfo::member).count();
        System.out.println("SLACK channels=" + channels.size() + " public=" + publicCount + " private=" + privateCount
                + " botIsIn=" + joined + " scopes=" + messenger.botScopes().size());
        assertThat(channels).isNotEmpty();

        String wanted = System.getenv("DOCS0_LIVE_SLACK_CHANNEL");
        ChannelInfo pick = channels.stream()
                .filter(c -> wanted != null && !wanted.isBlank() ? c.id().equals(wanted) : !c.isPrivate() && c.member())
                .findFirst().orElseThrow(() -> new AssertionError("no suitable channel; set DOCS0_LIVE_SLACK_CHANNEL"));

        IdentityService identities = new IdentityService(jdbc, messenger);
        SlackHistoryConnection connection = new SlackHistoryConnection(sources);
        connection.setChannels(tenant.id(), List.of(new SlackChannel(pick.id(), pick.name(), pick.isPrivate())));
        SlackHistoryConnector connector = new SlackHistoryConnector(messenger, identities, connection, store, runs,
                tenants, props, 1);

        SyncCounts counts = connector.sync(tenant.id(), connection.source(tenant.id()).orElseThrow().id());
        long documents = store.count(tenant.id(), connection.source(tenant.id()).orElseThrow().id());
        long restricted = jdbc.sql("SELECT count(*) FROM document WHERE restricted").query(Long.class).single();
        System.out.println("SLACK sync private=" + pick.isPrivate() + " counts=" + counts + " documents=" + documents
                + " restricted=" + restricted + " emailScopeMissing=" + identities.emailScopeMissing());
        assertThat(counts.failed()).isZero();
    }
}
