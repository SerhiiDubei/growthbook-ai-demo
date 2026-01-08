package com.example.gb.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class ConsolePageController {

    @GetMapping("/gb/console")
    public String page(Model model) {
        // Початкові значення, які зручно міняти в одному місці:
        model.addAttribute("defaultUrl", "http://localhost:8080/");
        model.addAttribute("qaUserId", "qa-vitaliy");
        model.addAttribute("envs", java.util.List.of("production", "staging", "dev"));
        model.addAttribute("defaultEnv", "production");
        return "gb/console"; // templates/gb/console.html
    }
}
