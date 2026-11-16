package com.docs0;

import com.docs0.web.admin.FakeSlackConfig;
import com.docs0.web.admin.FakeThemesLlmConfig;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The Phase 6 integration tests share one application context (fake Slack, a fake model that answers the themes
 * call, and a configured API key), so the suite does not open a database pool per test class.
 */
@AutoConfigureMockMvc
@Import({FakeSlackConfig.class, FakeThemesLlmConfig.class})
@TestPropertySource(properties = "docs0.anthropic.api-key=test-key")
public abstract class Phase6IntegrationTest extends IntegrationTest {
}
