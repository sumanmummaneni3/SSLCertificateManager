package com.certguard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaController {
    // Client-side (React) routes that must resolve to index.html when deep-linked
    // or refreshed. These mirror the entry paths the SPA reads from window.location
    // (App.jsx); the standalone nginx image used a try_files catch-all, but now the
    // server serves the SPA directly so each deep-link route is listed explicitly.
    // API/auth/static paths (/api, /oauth2, /login, /agent, /actuator, /assets,
    // /static) are intentionally NOT matched here — they are handled by their own
    // controllers, the security filter chain, or the static resource handlers.
    @RequestMapping(value = {
        "/",
        "/dashboard", "/targets", "/certificates", "/certificates/{id}", "/agents",
        "/invite",
        "/auth/callback", "/auth/verify-email", "/auth/reset-password",
        "/scan", "/scan/{viewToken}"
    })
    public String index() { return "forward:/index.html"; }
}
