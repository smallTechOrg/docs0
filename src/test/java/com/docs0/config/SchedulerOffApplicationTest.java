package com.docs0.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.source.SyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;

/** The real application with DOCS0_INTERNAL_SCHEDULER=false (and the startup sync asked for): no scheduling at all. */
@TestPropertySource(properties = {"docs0.scheduler.enabled=false", "docs0.drive.sync-on-startup=true"})
class SchedulerOffApplicationTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void noScheduledJobCanRun() {
        assertThat(context.getBeansOfType(SchedulingConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        // The beans themselves still exist (the jobs call them); they just are never triggered.
        assertThat(context.getBeansOfType(SyncScheduler.class)).hasSize(1);
    }
}
