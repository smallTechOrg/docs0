package com.docs0.web.admin;

import com.docs0.source.grain.FakeGrainClient;
import com.docs0.source.gmail.FakeGmailClient;
import com.docs0.source.linear.FakeLinearClient;
import com.docs0.source.trello.FakeTrelloClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the Linear and Trello network edges with fakes in a full application context. */
@TestConfiguration
public class FakeToolClientsConfig {

    @Bean
    @Primary
    FakeLinearClient fakeLinearClient() {
        return new FakeLinearClient();
    }

    @Bean
    @Primary
    FakeTrelloClient fakeTrelloClient() {
        return new FakeTrelloClient();
    }

    @Bean
    @Primary
    FakeGrainClient fakeGrainClient() {
        return new FakeGrainClient();
    }

    @Bean
    @Primary
    FakeGmailClient fakeGmailClient() {
        return new FakeGmailClient();
    }
}
