package com.docs0.web.slack;

import com.docs0.slack.FakeSlackOAuth;
import com.docs0.slack.FakeSlackSignIn;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Slack's OAuth and sign-in endpoints and the clock, replaced with fakes in a full application context. */
@TestConfiguration
public class MultiTenantTestConfig {

    @Bean
    @Primary
    FakeSlackOAuth fakeSlackOAuth() {
        return new FakeSlackOAuth();
    }

    @Bean
    @Primary
    FakeSlackSignIn fakeSlackSignIn() {
        return new FakeSlackSignIn();
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
