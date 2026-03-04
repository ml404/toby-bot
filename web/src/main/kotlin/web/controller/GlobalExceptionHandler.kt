package web.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(
        request: HttpServletRequest,
        ra: RedirectAttributes
    ): String {
        ra.addFlashAttribute("error", "File too large. Maximum size is 550KB.")
        val referer = request.getHeader("Referer") ?: "/intro/guilds"
        return "redirect:$referer"
    }
}
