package com.ticketing.gateway.contoller;


import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Authentication auth, Model model) {
        model.addAttribute("username", auth != null ? auth.getName() : "anonymous");
        model.addAttribute("authorities", auth != null ? auth.getAuthorities() : null);
        return "dashboard";
    }
}