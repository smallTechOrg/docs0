package com.docs0.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * With {@code DOCS0_AUTO_CREATE_DATABASE=true}, creates the database before Spring builds its DataSource.
 *
 * <p>It runs as an environment post-processor, after application.yml and .env are loaded (so the same URL and
 * credentials the DataSource will use are visible) and before any bean exists, so Hikari and Flyway never see a
 * missing database. Off by default: tests and the local setup never touch this.
 */
public class DatabaseBootstrapPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final Log log;

    public DatabaseBootstrapPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DatabaseBootstrapPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("docs0.database.auto-create", Boolean.class, false)) {
            return;
        }
        String url = environment.getProperty("spring.datasource.url");
        String name = DatabaseBootstrap.databaseName(url);
        DatabaseBootstrap.Outcome outcome = DatabaseBootstrap.ensureDatabase(url,
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"));
        log.info("database bootstrap: database '" + name + "' "
                + (outcome == DatabaseBootstrap.Outcome.CREATED ? "created" : "already exists"));
    }

    /** After ConfigDataEnvironmentPostProcessor (which loads application.yml and .env). */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
