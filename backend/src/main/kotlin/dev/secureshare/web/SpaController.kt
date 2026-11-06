package dev.secureshare.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class SpaController {
    @GetMapping("/", "/login", "/register", "/app", "/files")
    fun index(): String = "forward:/index.html"
}
