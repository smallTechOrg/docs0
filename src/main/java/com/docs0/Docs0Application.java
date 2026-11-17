package com.docs0;

import com.docs0.job.JobRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Docs0Application {

    public static void main(String[] args) {
        if (JobRunner.isJob(args)) {
            // Cloud Run Job: run one job to completion with no web server, then exit with its result.
            JobRunner.main(args, System::exit);
            return;
        }
        SpringApplication.run(Docs0Application.class, args);
    }
}
