package com.certguard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaController {
    @RequestMapping(value = {"/", "/dashboard", "/targets", "/certificates", "/agents"})
    public String index() { return "forward:/index.html"; }
}
