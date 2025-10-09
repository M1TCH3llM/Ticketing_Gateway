package com.ticketing.gateway.contoller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/history")
    public String history() {
        return "history"; // resolves templates/history.html
    }
}
