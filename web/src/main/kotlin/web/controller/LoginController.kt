package web.controller

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class LoginController {

    @GetMapping("/login")
    fun login(@RequestParam(required = false) error: String?, model: Model): String {
        if (error != null) model.addAttribute("loginError", true)
        return "login"
    }
}
