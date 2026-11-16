package com.docs0.web.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link AdminGuard} in front of everything under /admin. */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminGuard guard;

    public AdminWebConfig(AdminGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/admin", "/admin/**");
    }
}
