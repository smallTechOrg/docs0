package com.docs0.job;

import com.docs0.Docs0Application;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs docs0 as a one-shot job (a Cloud Run Job started with {@code --docs0.job=sync} or {@code --docs0.job=digest}):
 * the Spring context starts without a web server, with no Slack listener and no internal scheduler, the job runs to
 * completion and the process exits. Exit code 0 means the job ran (a source that failed is recorded in the sync
 * log and does not fail the job); non-zero means the job itself crashed (the context did not start, the database
 * was unreachable).
 *
 * <p>Arguments: {@code --docs0.job=sync|digest}, and for sync the optional {@code --docs0.job.tenant=<id>} and
 * {@code --docs0.job.source=<id>} (what the Sources page "Sync now" button asks for).
 */
public final class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final String JOB = "--docs0.job=";
    private static final String TENANT = "--docs0.job.tenant=";
    private static final String SOURCE = "--docs0.job.source=";

    /** What to run. Tenant and source are empty when not narrowed. */
    public record Job(String name, OptionalLong tenantId, OptionalLong sourceId) {}

    private JobRunner() {
    }

    /** True when the arguments ask for a job. */
    public static boolean isJob(String[] args) {
        for (String arg : args) {
            if (arg.startsWith(JOB)) {
                return true;
            }
        }
        return false;
    }

    /** Reads the job arguments. Throws IllegalArgumentException for an unknown job or a non-numeric id. */
    public static Job parse(String[] args) {
        String name = null;
        OptionalLong tenant = OptionalLong.empty();
        OptionalLong source = OptionalLong.empty();
        for (String arg : args) {
            if (arg.startsWith(JOB)) {
                name = arg.substring(JOB.length()).strip();
            } else if (arg.startsWith(TENANT)) {
                tenant = OptionalLong.of(id(arg.substring(TENANT.length())));
            } else if (arg.startsWith(SOURCE)) {
                source = OptionalLong.of(id(arg.substring(SOURCE.length())));
            }
        }
        if (!"sync".equals(name) && !"digest".equals(name)) {
            throw new IllegalArgumentException("Unknown job (use --docs0.job=sync or --docs0.job=digest)");
        }
        return new Job(name, tenant, source);
    }

    private static long id(String text) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Job tenant and source must be numbers");
        }
    }

    /** The arguments the job's Spring context starts with: the given ones plus the settings that make it a job. */
    public static String[] contextArgs(String[] args) {
        List<String> all = new ArrayList<>(List.of(args));
        // Command-line arguments outrank every other setting, so the environment cannot switch these back on.
        all.add("--docs0.scheduler.enabled=false");
        all.add("--docs0.slack.transport=none");
        all.add("--docs0.drive.sync-on-startup=false");
        all.add("--spring.main.web-application-type=none");
        return all.toArray(new String[0]);
    }

    /**
     * Starts the context (no web server), runs the job, closes the context and reports the exit code through
     * {@code exit}: 0 when the job ran, 1 when it crashed, 2 for bad arguments.
     */
    public static void main(String[] args, IntConsumer exit) {
        Job job;
        try {
            job = parse(args);
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            exit.accept(2);
            return;
        }
        int code = 0;
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(Docs0Application.class).web(WebApplicationType.NONE)
                    .run(contextArgs(args));
            context.getBean(JobTasks.class).run(job);
        } catch (Throwable t) {
            log.error("Job {} crashed: {}", job.name(), t.getClass().getName());
            code = 1;
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (RuntimeException e) {
                    log.warn("Context did not close cleanly: {}", e.getClass().getName());
                }
            }
        }
        exit.accept(code);
    }
}
