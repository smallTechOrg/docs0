package com.docs0.web;

import com.docs0.web.StatusContributor.ComponentStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class StatusController {

    private final StatusService status;

    public StatusController(StatusService status) {
        this.status = status;
    }

    @GetMapping("/")
    public String page(Model model) {
        model.addAttribute("s", status.snapshot());
        return "status";
    }

    /** UP whenever the app and database respond; integrations report their own state underneath. */
    @GetMapping("/health")
    @ResponseBody
    public Map<String, Object> health() {
        var snap = status.snapshot();
        Map<String, Object> components = new LinkedHashMap<>();
        for (ComponentStatus c : snap.components()) {
            components.put(c.name(), Map.of("state", c.state().name(), "detail", c.detail() == null ? "" : c.detail()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("tenant", snap.tenantName());
        body.put("documents", snap.documents());
        body.put("questions", snap.questions());
        body.put("components", components);
        return body;
    }
}
