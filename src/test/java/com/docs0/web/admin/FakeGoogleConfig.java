package com.docs0.web.admin;

import com.docs0.source.drive.FakeDriveClient;
import com.docs0.source.drive.FakeGoogleOAuth;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the two Google network edges with fakes in a full application context. */
@TestConfiguration
public class FakeGoogleConfig {

    @Bean
    @Primary
    FakeDriveClient fakeDriveClient() {
        return new FakeDriveClient();
    }

    @Bean
    @Primary
    FakeGoogleOAuth fakeGoogleOAuth() {
        return new FakeGoogleOAuth();
    }
}
