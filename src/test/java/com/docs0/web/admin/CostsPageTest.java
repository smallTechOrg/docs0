package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.conversation.Conversation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class CostsPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    private String page() throws Exception {
        return mvc.perform(get("/admin/costs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private static String text(String html) {
        return html.replaceAll("(?s)<style.*?</style>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ")
                .replace("$ ", "$").replace(" %", "%");
    }

    @Test
    void anEmptyMonthSaysSo() throws Exception {
        assertThat(text(page())).contains("$0.00").contains("0% of the $100.00 monthly cap")
                .contains("No questions in the last 30 days.")
                .contains("No questions have cost anything this month.");
    }

    @Test
    void showsSpendAgainstTheCapWithABarADayTableAndTheDearestQuestions() throws Exception {
        Conversation thread = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        Conversation dm = TestData.conversation(jdbc, tenant.id(), "D1", "", true);
        TestData.interaction(jdbc, thread, "U1", "What is the refund policy?", "a", "9.1", "12.50");
        TestData.interaction(jdbc, dm, "U2", "My private salary question", "a", "9.2", "30.25");
        long old = TestData.interaction(jdbc, thread, "U1", "Old question", "a", "9.3", "1.00");
        TestData.backdate(jdbc, old, Instant.now().minus(3, ChronoUnit.DAYS));

        String html = page();

        // Spend is month-to-date; the old one is only counted if it is in this month, so assert the parts that are stable.
        assertThat(html).contains("monthly cap").contains("class=\"bar\"").contains("width:");
        assertThat(html).contains("<th>Tokens in</th>").contains("<th>Cost</th>").contains("1,000");
        assertThat(html).contains("What is the refund policy?");
        assertThat(html).contains("DM question").doesNotContain("My private salary question");
        assertThat(html.indexOf("DM question")).as("dearest first").isLessThan(html.indexOf("What is the refund policy?"));
        assertThat(text(html)).contains("$30.2500");
    }

    @Test
    void aMonthAtTheCapShowsAFullBarAndSaysTheCapIsUsedUp() throws Exception {
        Conversation thread = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        TestData.interaction(jdbc, thread, "U1", "Big one", "a", "9.1", "100.00");

        assertThat(page()).contains("width:100%").contains("class=\"bar over\"").contains("The cap is used up");
    }

    @Test
    void questionTextIsEscapedAndShortened() throws Exception {
        Conversation thread = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        TestData.interaction(jdbc, thread, "U1", "<script>alert(1)</script> " + "long ".repeat(100) + "THE-END", "a",
                "9.1", "1.00");

        String html = page();

        assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("THE-END");
    }

    @Test
    void otherModelCallsAreOwnLineAndCountInTheMonthTotal() throws Exception {
        Conversation thread = TestData.conversation(jdbc, tenant.id(), "C1", "1.1", false);
        TestData.interaction(jdbc, thread, "U1", "A question?", "a", "9.1", "2.00");
        new com.docs0.budget.UsageLog(jdbc).record(tenant.id(), "themes", 1000, 100, new java.math.BigDecimal("0.75"));

        String t = text(page());

        assertThat(t).contains("$2.75").contains("Answering questions $2.00")
                .contains("Other model calls (repeat-question checks and insights themes) $0.75")
                .contains("in the last 30 days (not in the table above): $0.75");
    }
}
