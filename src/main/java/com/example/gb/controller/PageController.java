package com.example.gb.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

  @Value("${growthbook.sdk.apiHost}")  String gbApiHost;
  @Value("${growthbook.sdk.clientKey}") String gbClientKey;

  @GetMapping("/")
  public String home(Model model) {
    model.addAttribute("gbApiHost", gbApiHost);
    model.addAttribute("gbClientKey", gbClientKey);
    return "index";
  }

  @GetMapping("/console")
  public String console() {
    return "console";
  }
}
