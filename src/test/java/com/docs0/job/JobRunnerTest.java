package com.docs0.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.Docs0Application;
import com.docs0.config.SchedulingConfig;
import com.docs0.slack.SlackGateway;
import com.docs0.web.StatusContributor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** Starting docs0 as a one-shot job: the arguments, the non-web context and the process exit code. */
class JobRunnerTest {

    private static String[] job(String... args) {
        List<String> all = new ArrayList<>(List.of("--spring.profiles.active=test"));
        all.addAll(Arrays.asList(args));
        return all.toArray(new String[0]);
    }

    @Test
    void recognisesAJobArgument() {
        assertThat(JobRunner.isJob(new String[] {"--docs0.job=sync"})).isTrue();
        assertThat(JobRunner.isJob(new String[] {"--server.port=1"})).isFalse();
        assertThat(JobRunner.isJob(new String[0])).isFalse();
    }

    @Test
    void parsesTheJobAndItsNarrowingArguments() {
        assertThat(JobRunner.parse(new String[] {"--docs0.job=sync"}))
                .isEqualTo(new JobRunner.Job("sync", OptionalLong.empty(), OptionalLong.empty()));
        assertThat(JobRunner.parse(new String[] {"--docs0.job=sync", "--docs0.job.tenant=3", "--docs0.job.source=9"}))
                .isEqualTo(new JobRunner.Job("sync", OptionalLong.of(3), OptionalLong.of(9)));
        assertThat(JobRunner.parse(new String[] {"--docs0.job=digest"}).name()).isEqualTo("digest");
    }

    @Test
    void rejectsAnUnknownJobOrANonNumericId() {
        assertThatThrownBy(() -> JobRunner.parse(new String[] {"--docs0.job=reindex"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobRunner.parse(new String[] {"--docs0.job=sync", "--docs0.job.tenant=abc"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theJobContextArgumentsSwitchOffTheListenerTheSchedulerAndTheWebServer() {
        List<String> args = Arrays.asList(JobRunner.contextArgs(new String[] {"--docs0.job=sync"}));

        assertThat(args).contains("--docs0.job=sync", "--docs0.scheduler.enabled=false",
                "--docs0.slack.transport=none", "--spring.main.web-application-type=none");
    }

    @Test
    void theJobContextHasNoWebServerNoSchedulerAndNoSlackListener() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Docs0Application.class)
                .web(WebApplicationType.NONE).run(JobRunner.contextArgs(job("--docs0.job=sync",
                        "--docs0.slack.bot-token=xoxb-x", "--docs0.slack.app-token=xapp-x")))) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(SchedulingConfig.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            // Even with both Socket Mode tokens set, the gateway started nothing.
            context.getBean(SlackGateway.class).start();
            assertThat(context.getBean(SlackGateway.class).status(1).state()).isNotEqualTo(StatusContributor.State.UP);
        }
    }

    @Test
    void aJobThatRunsExitsWithZero() {
        List<Integer> codes = new ArrayList<>();

        JobRunner.main(job("--docs0.job=digest"), codes::add);
        JobRunner.main(job("--docs0.job=sync"), codes::add);

        assertThat(codes).containsExactly(0, 0);
    }

    @Test
    void aJobThatCannotStartExitsNonZero() {
        List<Integer> codes = new ArrayList<>();

        JobRunner.main(job("--docs0.job=sync", "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/nope"),
                codes::add);

        assertThat(codes).singleElement().isNotEqualTo(0);
    }

    @Test
    void badArgumentsExitWithTwoAndStartNothing() {
        List<Integer> codes = new ArrayList<>();

        JobRunner.main(job("--docs0.job=nonsense"), codes::add);

        assertThat(codes).containsExactly(2);
    }
}
