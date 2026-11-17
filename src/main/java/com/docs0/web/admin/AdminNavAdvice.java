package com.docs0.web.admin;

import com.docs0.config.Docs0Properties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** In multi-tenant mode every admin page shows the Start link and a Sign out button. Nothing is added otherwise. */
@ControllerAdvice(basePackageClasses = AdminNavAdvice.class)
public class AdminNavAdvice {

    private final Docs0Properties props;

    public AdminNavAdvice(Docs0Properties props) {
        this.props = props;
    }

    @ModelAttribute
    public void nav(Model model, HttpServletRequest request) {
        if (props.multiTenant()) {
            model.addAttribute("multiTenant", true);
            model.addAttribute("navCsrf", AdminGuard.csrfToken(request.getSession()));
        }
    }
}
