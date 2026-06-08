package bot.toby.install

import database.dto.guild.ConfigDto.Configurations
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class InstallSummaryTest {

    private fun guild(name: String = "My Guild", id: String = "g1"): Guild = mockk(relaxed = true) {
        every { this@mockk.name } returns name
        every { this@mockk.id } returns id
    }

    private fun fieldValue(embed: net.dv8tion.jda.api.entities.MessageEmbed, nameContains: String): String? =
        embed.fields.firstOrNull { it.name?.contains(nameContains) == true }?.value

    @Test
    fun `header reflects the install mode and prompts a re-run`() {
        val reader: ConfigReader = { key ->
            when (key) {
                Configurations.INSTALL_MODE -> "express"
                Configurations.INSTALLED_AT -> "1700000000000"
                else -> null
            }
        }
        val embed = InstallSummary.embed(guild(), reader, jackpotPool = 1000, winChanceDisplay = "1", webBaseUrl = "")
        assertTrue(embed.title!!.contains("My Guild"))
        assertTrue(embed.description!!.contains("Express"))
        assertTrue(embed.description!!.contains("/install setup"))
    }

    @Test
    fun `not-installed header nudges to run setup`() {
        val embed = InstallSummary.embed(guild(), { null }, 0, "0", "")
        assertTrue(embed.description!!.contains("isn't finished", ignoreCase = true))
        assertTrue(embed.description!!.contains("/install setup"))
    }

    @Test
    fun `economy field shows the jackpot pool and win chance`() {
        val economy = fieldValue(
            InstallSummary.embed(guild(), { null }, jackpotPool = 2500, winChanceDisplay = "0.5", webBaseUrl = ""),
            "Casino",
        )!!
        assertTrue(economy.contains("2500"))
        assertTrue(economy.contains("0.5%"))
    }

    @Test
    fun `features field marks opt-ins on or off`() {
        val reader: ConfigReader = { key -> if (key == Configurations.ACTIVITY_TRACKING) "true" else null }
        val features = fieldValue(InstallSummary.embed(guild(), reader, 0, "0", ""), "Features")!!
        assertTrue(features.contains("✅ Activity tracking"))
        assertTrue(features.contains("⬜ Daily lottery"))
    }

    @Test
    fun `set channel renders a mention, unset renders not set`() {
        val channel = mockk<GuildChannel> { every { asMention } returns "<#999>" }
        val g = guild()
        every { g.getGuildChannelById(999L) } returns channel
        val reader: ConfigReader = { key -> if (key == Configurations.LEADERBOARD_CHANNEL) "999" else null }

        val channels = fieldValue(InstallSummary.embed(g, reader, 0, "0", ""), "Channels")!!
        assertTrue(channels.contains("Leaderboard: <#999>"))
        assertTrue(channels.contains("Move target: *not set*"))
    }

    @Test
    fun `web dashboard link shown only when a base url is set`() {
        assertTrue(fieldValue(InstallSummary.embed(guild(), { null }, 0, "0", "https://x"), "Web")!!.contains("https://x/profile/g1"))
        assertTrue(fieldValue(InstallSummary.embed(guild(), { null }, 0, "0", ""), "Web")!!.contains("Not configured"))
    }

    @Test
    fun `suggestions flag unset recommendations and disappear when everything relevant is set`() {
        val tips = fieldValue(InstallSummary.embed(guild(), { null }, 0, "0", ""), "Suggested")!!
        assertTrue(tips.contains("Activity tracking"))
        assertTrue(tips.contains("Daily lottery"))
        assertTrue(tips.contains("leaderboard channel"))

        val allSet: ConfigReader = { key ->
            when (key) {
                Configurations.ACTIVITY_TRACKING, Configurations.LOTTERY_DAILY_ENABLED -> "true"
                Configurations.LEADERBOARD_CHANNEL,
                Configurations.LEVEL_UP_CHANNEL,
                Configurations.ACHIEVEMENT_ANNOUNCE_CHANNEL,
                -> "123"
                else -> null
            }
        }
        assertNull(fieldValue(InstallSummary.embed(guild(), allSet, 0, "0", ""), "Suggested"))
    }
}
