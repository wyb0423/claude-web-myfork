package com.claude.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping(value = {"/", "/thread/**"})
    public String index(Model model) {
        model.addAttribute("hideDropdowns", true);
        return "index";
    }

    @GetMapping("/session-api-test")
    public String sessionApiTest() {
        return "session-api-test";
    }
}
