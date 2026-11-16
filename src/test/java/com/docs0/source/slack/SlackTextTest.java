package com.docs0.source.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackTextTest {

    private static final Map<String, String> NAMES = Map.of("U1", "Priya", "U2", "Marco");

    private static String plain(String text) {
        return SlackText.plain(text, NAMES::get);
    }

    @Test
    void mentionsBecomeNames() {
        assertThat(plain("hi <@U1> and <@U2>, ping <@U9>")).isEqualTo("hi @Priya and @Marco, ping @U9");
        assertThat(plain("<@U9|zed> said")).isEqualTo("@zed said");
    }

    @Test
    void linksBecomeTextWithTheUrl() {
        assertThat(plain("see <https://example.com/a?b=1&amp;c=2|the doc> now"))
                .isEqualTo("see the doc (https://example.com/a?b=1&c=2) now");
        assertThat(plain("<https://example.com>")).isEqualTo("https://example.com");
        assertThat(plain("<https://example.com|https://example.com>")).isEqualTo("https://example.com");
        assertThat(plain("mail <mailto:a@b.com|a@b.com>")).isEqualTo("mail a@b.com");
    }

    @Test
    void channelsAndSpecialMentions() {
        assertThat(plain("in <#C123|general> <!here> <!channel> <!subteam^S1|@eng-team>"))
                .isEqualTo("in #general @here @channel @eng-team");
        assertThat(plain("<#C123>")).isEqualTo("#C123");
    }

    @Test
    void htmlEntitiesAreDecodedAfterMarkupAndNeverFormNewMarkup() {
        assertThat(plain("1 &lt; 2 &amp;&amp; 3 &gt; 2")).isEqualTo("1 < 2 && 3 > 2");
        assertThat(plain("&lt;@U1&gt; is literal")).isEqualTo("<@U1> is literal");
        assertThat(plain("&amp;lt; stays")).isEqualTo("&lt; stays");
    }

    @Test
    void nullAndEmptyAreEmpty() {
        assertThat(plain(null)).isEmpty();
        assertThat(plain("")).isEmpty();
    }
}
