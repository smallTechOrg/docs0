package com.docs0.web.admin;

import com.docs0.config.Docs0Properties;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link AdminGuard} in front of everything under /admin and hands controllers the {@link AdminPrincipal}. */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminGuard guard;
    private final Docs0Properties props;

    public AdminWebConfig(AdminGuard guard, Docs0Properties props) {
        this.guard = guard;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        var registration = registry.addInterceptor(guard).addPathPatterns("/admin", "/admin/**");
        if (props.multiTenant()) {
            // The two pages a signed-out admin has to reach in order to sign in.
            registration.excludePathPatterns("/admin/login", "/admin/login/callback");
        } else if (props.adminPasswordSet()) {
            // Hosted pilot: the status page lists tenant, counts and integrations, so it needs the same password.
            registry.addInterceptor(guard).addPathPatterns("/");
        }
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return AdminPrincipal.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                    NativeWebRequest request, WebDataBinderFactory binderFactory) {
                Object principal = request.getAttribute(AdminGuard.PRINCIPAL_ATTRIBUTE,
                        RequestAttributes.SCOPE_REQUEST);
                if (principal instanceof AdminPrincipal admin) {
                    return admin;
                }
                // Only reachable if a controller outside /admin asks for a principal: refuse rather than guess.
                throw new IllegalStateException("No signed-in admin on this request");
            }
        });
    }
}
