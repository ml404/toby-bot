package web.controller

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import web.util.displayName

@Controller
class DndLookupController {

    @GetMapping("/dnd")
    fun page(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model
    ): String {
        model.addAttribute("username", user.displayName())
        return "dndLookup"
    }

    // Old campaign URLs redirect to the new lookup page so existing
    // bookmarks and Discord-posted links don't 404.
    @GetMapping("/dnd/campaign", "/dnd/campaign/**", "/dnd/encounters", "/dnd/encounters/**", "/dnd/monsters/**")
    fun campaignRedirect(): String = "redirect:/dnd"
}
