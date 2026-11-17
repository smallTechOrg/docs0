package com.docs0.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.docs0.insights.DigestService;
import com.docs0.source.SyncScheduler;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** DOCS0_INTERNAL_SCHEDULER: one switch, {@link SchedulingConfig}, decides whether anything runs on a timer. */
class SchedulingSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SyncScheduler.class, () -> mock(SyncScheduler.class))
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void onByDefaultAndWhenTrue() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
        runner.withPropertyValues("docs0.scheduler.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    void offWhenFalseNothingSchedulesAndNoStartupSyncRuns() {
        runner.withPropertyValues("docs0.scheduler.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            context.publishEvent(new ApplicationReadyEvent(new org.springframework.boot.SpringApplication(),
                    new String[0], context, java.time.Duration.ZERO));
            verify(context.getBean(SyncScheduler.class), never()).onReady();
        });
    }

    @Test
    void theStartupSyncIsPartOfTheSwitchWhenItIsOn() {
        runner.run(context -> {
            context.publishEvent(new ApplicationReadyEvent(new org.springframework.boot.SpringApplication(),
                    new String[0], context, java.time.Duration.ZERO));
            verify(context.getBean(SyncScheduler.class)).onReady();
        });
    }

    @Test
    void theParsingOfTheSettingDefaultsToOn() {
        assertThat(new SchedulerProperties(null).isEnabled()).isTrue();
        assertThat(new SchedulerProperties(true).isEnabled()).isTrue();
        assertThat(new SchedulerProperties(false).isEnabled()).isFalse();
    }

    @Test
    void everyScheduledMethodOfTheAppIsOneTheSwitchCovers() {
        // Spring only runs @Scheduled methods while SchedulingConfig (@EnableScheduling) exists. If a new scheduled job
        // appears, this list changes and the author sees that it is under the switch.
        List<String> scheduled = java.util.stream.Stream.of(SyncScheduler.class, DigestService.class)
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Scheduled.class)).map(Method::getName).sorted().toList();
        assertThat(scheduled).containsExactly("scheduled", "syncAll");
    }
}
