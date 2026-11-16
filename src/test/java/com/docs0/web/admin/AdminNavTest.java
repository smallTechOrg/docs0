package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The shared navigation, the status page links, and the admin guard in front of the new pages. */
@AutoConfigureMockMvc
class AdminNavTest extends IntegrationTest {

    private static final String[] PAGES = {"/admin/sources", "/admin/corrections", "/admin/costs"};

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder from(String remote, MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(remote);
            return r;
        });
    }

    @Test
    void everyAdminPageHasTheSameNavigationWithItsOwnEntryMarked() throws Exception {
        for (String page : PAGES) {
            String html = mvc.perform(get(page)).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            assertThat(html).as(page).contains("href=\"/admin/sources\"").contains("href=\"/admin/corrections\"")
                    .contains("href=\"/admin/costs\"");
            assertThat(html).as(page).contains("<a href=\"" + page + "\" class=\"on\">");
            assertThat(html).as(page).contains("prefers-color-scheme: dark").contains("width=device-width");
        }
    }

    @Test
    void theStatusPageLinksToThemAndCountsPendingCorrections() throws Exception {
        com.docs0.TestData.correction(jdbc, tenant.id(), "PENDING", "A", "q", "f", null, false);
        com.docs0.TestData.correction(jdbc, tenant.id(), "PENDING", "B", "q", "f", null, false);
        com.docs0.TestData.correction(jdbc, tenant.id(), "APPROVED", "C", "q", "f", null, false);

        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("href=\"/admin/corrections\"").contains("href=\"/admin/costs\"")
                .contains("pending corrections").containsPattern("<b>2</b><span>pending corrections");
    }

    @Test
    void theNewPagesAreForThisMachineOnlyWithoutAPassword() throws Exception {
        for (String page : new String[] {"/admin/corrections", "/admin/costs"}) {
            mvc.perform(from("203.0.113.9", get(page))).andExpect(status().isForbidden());
            mvc.perform(get(page).header("X-Forwarded-For", "203.0.113.9")).andExpect(status().isForbidden());
        }
        for (String action : new String[] {"approve", "reject", "unapprove"}) {
            mvc.perform(from("203.0.113.9", post("/admin/corrections/1/" + action))).andExpect(status().isForbidden());
        }
    }
}
