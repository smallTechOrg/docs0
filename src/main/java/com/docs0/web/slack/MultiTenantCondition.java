package com.docs0.web.slack;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** True when the Slack client id, client secret and signing secret are all set: multi-tenant mode. */
public class MultiTenantCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        return StringUtils.hasText(env.getProperty("docs0.slack.client-id"))
                && StringUtils.hasText(env.getProperty("docs0.slack.client-secret"))
                && StringUtils.hasText(env.getProperty("docs0.slack.signing-secret"));
    }
}
