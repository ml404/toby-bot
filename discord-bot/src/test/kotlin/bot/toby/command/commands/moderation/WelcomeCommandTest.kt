package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.botMember
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import database.dto.AutoRoleDto
import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations
import database.service.AutoRoleService
import database.service.ConfigService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class WelcomeCommandTest : CommandTest {

    private lateinit var configService: ConfigService
    private lateinit var autoRoleService: AutoRoleService
    private lateinit var command: WelcomeCommand
    private lateinit var ephemeralReply: ReplyCallbackAction

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        configService = mockk(relaxed = true)
        autoRoleService = mockk(relaxed = true)
        command = WelcomeCommand(configService, autoRoleService)
        ephemeralReply = mockk(relaxed = true)
        every { event.reply(any<String>()) } returns ephemeralReply
        every { event.replyEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) } returns ephemeralReply
        every { ephemeralReply.setEphemeral(any()) } returns ephemeralReply
        every { ephemeralReply.queue() } just runs
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    // ---- owner gate ----

    @Test
    fun `non-owner cannot configure welcome`() {
        every { member.isOwner } returns false
        every { event.subcommandName } returns WelcomeCommand.SUB_CONFIGURE_WELCOME

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify { event.reply(match<String> { it.contains("Only the server owner") }) }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
    }

    @Test
    fun `non-owner cannot add auto-role`() {
        every { member.isOwner } returns false
        every { event.subcommandName } returns WelcomeCommand.SUB_AUTOROLE_ADD

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    @Test
    fun `show is allowed for non-owners`() {
        every { member.isOwner } returns false
        every { event.subcommandName } returns WelcomeCommand.SUB_SHOW
        every { autoRoleService.listForGuild(any()) } returns emptyList()
        every { configService.getConfigByName(any(), any()) } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { event.replyEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) }
    }

    // ---- configure-welcome happy path ----

    @Test
    fun `configure-welcome with enabled+channel+message upserts all three keys`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_CONFIGURE_WELCOME
        stubBoolOpt(WelcomeCommand.OPT_ENABLED, true)
        val channel = stubChannelOpt(WelcomeCommand.OPT_CHANNEL, 555L, "#welcomes")
        stubStringOpt(WelcomeCommand.OPT_MESSAGE, "hi {user}")

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) {
            configService.upsertAll(
                "1",
                listOf(
                    Configurations.WELCOME_ENABLED.configValue to "true",
                    Configurations.WELCOME_CHANNEL.configValue to "555",
                    Configurations.WELCOME_MESSAGE.configValue to "hi {user}",
                ),
            )
        }
        // channel mock returned to make the verify above pass; no other
        // calls expected on it. We don't strictly verify wasNot Called
        // because mockk's `relaxed = true` would record incidental
        // interactions (e.g. logging) that aren't behaviourally relevant.
        @Suppress("UNUSED_VARIABLE") val unused = channel
    }

    @Test
    fun `configure-welcome with only enabled omits channel and message rows`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_CONFIGURE_WELCOME
        stubBoolOpt(WelcomeCommand.OPT_ENABLED, false)
        every { event.getOption(WelcomeCommand.OPT_CHANNEL) } returns null
        every { event.getOption(WelcomeCommand.OPT_MESSAGE) } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) {
            configService.upsertAll(
                "1",
                listOf(Configurations.WELCOME_ENABLED.configValue to "false"),
            )
        }
    }

    @Test
    fun `configure-goodbye writes to GOODBYE_* keys not WELCOME_* keys`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_CONFIGURE_GOODBYE
        stubBoolOpt(WelcomeCommand.OPT_ENABLED, true)
        stubChannelOpt(WelcomeCommand.OPT_CHANNEL, 777L, "#farewells")
        stubStringOpt(WelcomeCommand.OPT_MESSAGE, "bye {user.name}")

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) {
            configService.upsertAll(
                "1",
                listOf(
                    Configurations.GOODBYE_ENABLED.configValue to "true",
                    Configurations.GOODBYE_CHANNEL.configValue to "777",
                    Configurations.GOODBYE_MESSAGE.configValue to "bye {user.name}",
                ),
            )
        }
    }

    // ---- autorole add ----

    @Test
    fun `autorole-add adds the role`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_AUTOROLE_ADD
        val role = stubRoleOpt(WelcomeCommand.OPT_ROLE, 100L, "Member", managed = false, public = false, canInteract = true)

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { autoRoleService.add(1L, 100L) }
    }

    @Test
    fun `autorole-add rejects everyone role`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_AUTOROLE_ADD
        stubRoleOpt(WelcomeCommand.OPT_ROLE, 100L, "@everyone", managed = false, public = true, canInteract = true)

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    @Test
    fun `autorole-add rejects managed role`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_AUTOROLE_ADD
        stubRoleOpt(WelcomeCommand.OPT_ROLE, 100L, "Twitch", managed = true, public = false, canInteract = true)

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    @Test
    fun `autorole-add rejects role above the bot`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_AUTOROLE_ADD
        stubRoleOpt(WelcomeCommand.OPT_ROLE, 100L, "Admin", managed = false, public = false, canInteract = false)

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    // ---- autorole remove ----

    @Test
    fun `autorole-remove drops the binding`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_AUTOROLE_REMOVE
        stubRoleOpt(WelcomeCommand.OPT_ROLE, 100L, "Member", managed = false, public = false, canInteract = true)

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { autoRoleService.delete(1L, 100L) }
    }

    // ---- show ----

    @Test
    fun `show reads every config key and lists auto-roles`() {
        every { member.isOwner } returns true
        every { event.subcommandName } returns WelcomeCommand.SUB_SHOW
        every {
            configService.getConfigByName(Configurations.WELCOME_ENABLED.configValue, "1")
        } returns ConfigDto(Configurations.WELCOME_ENABLED.configValue, "true", "1")
        every {
            configService.getConfigByName(Configurations.WELCOME_CHANNEL.configValue, "1")
        } returns ConfigDto(Configurations.WELCOME_CHANNEL.configValue, "555", "1")
        every {
            configService.getConfigByName(Configurations.WELCOME_MESSAGE.configValue, "1")
        } returns ConfigDto(Configurations.WELCOME_MESSAGE.configValue, "hi {user}", "1")
        every { autoRoleService.listForGuild(1L) } returns listOf(
            AutoRoleDto(guildId = 1L, roleId = 100L),
            AutoRoleDto(guildId = 1L, roleId = 200L),
        )

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { event.replyEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) }
        verify(exactly = 1) { autoRoleService.listForGuild(1L) }
    }

    // ---- subcommand sanity ----

    @Test
    fun `subCommands carry the five expected names`() {
        val names = command.subCommands.map { it.name }.toSet()
        assert(names == setOf(
            WelcomeCommand.SUB_CONFIGURE_WELCOME,
            WelcomeCommand.SUB_CONFIGURE_GOODBYE,
            WelcomeCommand.SUB_AUTOROLE_ADD,
            WelcomeCommand.SUB_AUTOROLE_REMOVE,
            WelcomeCommand.SUB_SHOW,
        )) { "subcommand names changed: $names" }
    }

    // ---- helpers ----

    private fun stubBoolOpt(name: String, value: Boolean) {
        val opt = mockk<OptionMapping>(relaxed = true)
        every { opt.asBoolean } returns value
        every { event.getOption(name) } returns opt
    }

    private fun stubStringOpt(name: String, value: String) {
        val opt = mockk<OptionMapping>(relaxed = true)
        every { opt.asString } returns value
        every { event.getOption(name) } returns opt
    }

    private fun stubChannelOpt(name: String, id: Long, displayName: String): TextChannel {
        val opt = mockk<OptionMapping>(relaxed = true)
        val channel = mockk<TextChannel>(relaxed = true)
        val union = mockk<GuildChannelUnion>(relaxed = true)
        every { channel.id } returns id.toString()
        every { channel.idLong } returns id
        every { channel.asMention } returns displayName
        // Production code reaches the channel via
        // `event.getOption(...).asChannel.asTextChannel()` so the union →
        // TextChannel resolution is what we mock through; no Kotlin cast
        // needed in the test setup.
        every { opt.asChannel } returns union
        every { union.asTextChannel() } returns channel
        every { event.getOption(name) } returns opt
        return channel
    }

    private fun stubRoleOpt(
        name: String,
        id: Long,
        roleName: String,
        managed: Boolean,
        public: Boolean,
        canInteract: Boolean,
    ): Role {
        val opt = mockk<OptionMapping>(relaxed = true)
        val role = mockk<Role>(relaxed = true)
        every { role.idLong } returns id
        every { role.id } returns id.toString()
        every { role.name } returns roleName
        every { role.isManaged } returns managed
        every { role.isPublicRole } returns public
        every { role.asMention } returns "<@&$id>"
        every { opt.asRole } returns role
        every { event.getOption(name) } returns opt
        // CommandTest wires `guild.selfMember` to return `botMember`
        // (a mocked SelfMember). Mock the canInteract probe through that.
        every { botMember.canInteract(role) } returns canInteract
        return role
    }
}
