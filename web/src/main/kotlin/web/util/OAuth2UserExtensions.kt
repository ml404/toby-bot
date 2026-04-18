package web.util

import org.springframework.security.oauth2.core.user.OAuth2User

fun OAuth2User?.displayName(): String = this?.getAttribute<String>("username") ?: "User"

fun OAuth2User.discordIdOrNull(): Long? = getAttribute<String>("id")?.toLongOrNull()

fun OAuth2User.discordIdString(): String = getAttribute<String>("id") ?: ""
