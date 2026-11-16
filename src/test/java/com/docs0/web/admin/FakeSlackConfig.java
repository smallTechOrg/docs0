package com.docs0.web.admin;

import com.docs0.source.slack.FakeSlack;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the Slack Web API with an in-memory workspace in a full application context. */
@TestConfiguration
public class FakeSlackConfig {

    @Bean
    @Primary
    FakeSlack fakeSlack() {
        return new FakeSlack();
    }
}
