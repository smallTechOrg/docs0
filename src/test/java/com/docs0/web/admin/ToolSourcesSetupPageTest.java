package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import com.docs0.source.linear.FakeLinearClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Without DOCS0_ENCRYPTION_KEY a key cannot be stored, so the page says what to set instead of asking for it. */
@AutoConfigureMockMvc
@Import(FakeToolClientsConfig.class)
class ToolSourcesSetupPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeLinearClient linearApi;

    @Test
    void theSectionsExplainTheMissingSettingAndTheConnectPostStoresNothing() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String html = mvc.perform(get("/admin/sources").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"linear-setup\"", "id=\"trello-setup\"", "DOCS0_ENCRYPTION_KEY")
                .doesNotContain("name=\"apiKey\"");

        mvc.perform(post("/admin/sources/linear/connect").session(session)
                        .param("_csrf", (String) session.getAttribute("docs0.csrf"))
                        .param("apiKey", FakeLinearClient.GOOD_KEY))
                .andExpect(status().isFound());

        assertThat(jdbc.sql("SELECT count(*) FROM source_credential").query(Long.class).single()).isZero();
        assertThat(linearApi.keysSeen).isEmpty();

        assertThat(html).contains("id=\"gmail-setup\"").doesNotContain("name=\"key\"");
        mvc.perform(post("/admin/sources/gmail/connect").session(session)
                        .param("_csrf", (String) session.getAttribute("docs0.csrf"))
                        .param("key", com.docs0.source.gmail.TestKeys.json()))
                .andExpect(status().isFound());
        assertThat(jdbc.sql("SELECT count(*) FROM source_credential").query(Long.class).single()).isZero();
    }
}
