package web.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController(
    @param:Value("\${spring.security.oauth2.client.registration.discord.client-id}")
    private val discordClientId: String
) {

    @GetMapping("/")
    fun home(@AuthenticationPrincipal user: OAuth2User?, model: Model): String {
        model.addAttribute(
            "inviteUrl",
            "https://discord.com/api/oauth2/authorize?client_id=$discordClientId&permissions=8&scope=bot%20applications.commands"
        )
        model.addAttribute("username", user?.getAttribute<String>("username"))
        return "home"
    }

    @GetMapping("/terms")
    fun terms(@AuthenticationPrincipal user: OAuth2User?, model: Model): String {
        model.addAttribute("username", user?.getAttribute<String>("username"))
        return "terms"
    }
}
