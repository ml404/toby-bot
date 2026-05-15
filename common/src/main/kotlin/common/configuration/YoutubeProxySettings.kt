package common.configuration

data class YoutubeProxySettings(
    val host: String,
    val port: Int,
    val user: String?,
    val pass: String?,
) {
    val hasAuth: Boolean get() = user != null && pass != null

    companion object {
        const val HOST_ENV = "YOUTUBE_PROXY_HOST"
        const val PORT_ENV = "YOUTUBE_PROXY_PORT"
        const val USER_ENV = "YOUTUBE_PROXY_USER"
        const val PASS_ENV = "YOUTUBE_PROXY_PASS"

        fun fromEnv(getenv: (String) -> String? = System::getenv): YoutubeProxySettings? {
            val host = getenv(HOST_ENV)?.takeIf { it.isNotBlank() } ?: return null
            val port = getenv(PORT_ENV)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: return null
            val user = getenv(USER_ENV)?.takeIf { it.isNotBlank() }
            val pass = getenv(PASS_ENV)?.takeIf { it.isNotBlank() }
            return YoutubeProxySettings(host, port, user, pass)
        }
    }
}
