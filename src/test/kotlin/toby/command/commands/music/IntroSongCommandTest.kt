package toby.command.commands.music

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.utils.AttachmentProxy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.requestingUserDto
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.jpa.service.IMusicFileService
import toby.jpa.service.IUserService
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

internal class IntroSongCommandTest : MusicCommandTest {
    lateinit var introSongCommand: IntroSongCommand

    lateinit var userService: IUserService
    lateinit var musicFileService: IMusicFileService
    lateinit var configService: IConfigService

    lateinit var mentionedUserDto: UserDto

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        userService = mockk()
        musicFileService = mockk()
        configService = mockk()
        introSongCommand = IntroSongCommand(userService, musicFileService, configService)
        mentionedUserDto = requestingUserDto
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearAllMocks()
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttached_setsIntroViaUrl() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val linkOptionMapping = mockk<OptionMapping>()
        val userOptionMapping = mockk<OptionMapping>()
        val volumeConfig = ConfigDto("DEFAULT_VOLUME", "20", "1")

        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { linkOptionMapping.asString } returns "https://www.youtube.com/"
        every { userService.listGuildUsers(1L) } returns listOf(requestingUserDto)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns volumeConfig

        // Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            mentionedUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(any()) }
        verify { CommandTest.interactionHook.sendMessageFormat(
            eq("Successfully set %s's intro song to '%s' with volume '%d'"),
            eq("UserName"),
            eq("https://www.youtube.com/"),
            eq(20)
        ) }
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttachedWithExistingMusicFile_setsIntroViaUrl() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val linkOptionMapping = mockk<OptionMapping>()
        val userOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { linkOptionMapping.asString } returns "https://www.youtube.com/"
        every { userService.listGuildUsers(1L) } returns listOf(requestingUserDto)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { requestingUserDto.musicDto } returns MusicDto(1L, 1L, "filename", 20, null)

        // Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.updateMusicFile(any()) }
        verify { CommandTest.interactionHook.sendMessageFormat(
            eq("Successfully updated %s's intro song to '%s' with volume '%d'"),
            eq("UserName"),
            eq("https://www.youtube.com/"),
            eq(20)
        ) }
    }

    @Test
    fun testIntroSong_withSuperuser_andMentionedMembers_setsMentionedMembersIntroViaUrl() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val linkOptionMapping = mockk<OptionMapping>()
        val userOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { linkOptionMapping.asString } returns "https://www.youtube.com/"
        every { userService.createNewUser(any()) } returns mentionedUserDto

        setupMentions(userOptionMapping)

        // Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(any()) }
        verify { CommandTest.interactionHook.sendMessageFormat(
            eq("Successfully set %s's intro song to '%s' with volume '%d'"),
            eq("Another Username"),
            eq("https://www.youtube.com/"),
            eq(20)
        ) }
    }

    @Test
    fun testIntroSong_withoutPermissionsAndSomeoneMentioned_andValidLinkAttached_doesNotSetIntroViaUrl() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val linkOptionMapping = mockk<OptionMapping>()
        val userOptionMapping = mockk<OptionMapping>()
        val mentions = mockk<Mentions>()

        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { userOptionMapping.mentions } returns mentions
        every { mentions.members } returns listOf(CommandTest.member)
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { linkOptionMapping.asString } returns "https://www.youtube.com/"
        every { userService.listGuildUsers(1L) } returns listOf(requestingUserDto)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { CommandTest.guild.owner } returns CommandTest.member
        every { CommandTest.member.effectiveName } returns "Effective Name"
        every { requestingUserDto.superUser } returns false

        // Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify {
            CommandTest.interactionHook.sendMessageFormat(
                eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to Effective Name")
            )
        }
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withSuperuser_andValidAttachment_setsIntroViaAttachment_andCreatesNewMusicFile() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val attachmentOptionMapping = mockk<OptionMapping>()
        val userOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { CommandTest.event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping, "mp3", 1000)
        every { userService.listGuildUsers(1L) } returns listOf(requestingUserDto)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")

        // Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(any()) }
        verify { userService.updateUser(eq(requestingUserDto)) }
        verify { CommandTest.interactionHook.sendMessageFormat(
            eq("Successfully set %s's intro song to '%s' with volume '%d'"),
            eq("UserName"),
            eq("filename"),
            eq(20)
        ) }
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withSuperuser_andMentionedMembers_setsIntroViaAttachment() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val attachmentOptionMapping = mockk<OptionMapping>()
        val userOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { CommandTest.event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping, "mp3", 1000)
        every { userService.listGuildUsers(1L) } returns listOf(requestingUserDto)
        every { userService.createNewUser(any()) } returns mentionedUserDto
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")

        setupMentions(userOptionMapping)

        // Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(any()) }
        verify { userService.updateUser(eq(mentionedUserDto)) }
        verify { CommandTest.interactionHook.sendMessageFormat(
            eq("Successfully set %s's intro song to '%s' with volume '%d'"),
            eq("Another Username"),
            eq("filename"),
            eq(20)
        ) }
    }

    private fun setupMentions(userOptionMapping: OptionMapping) {
        val mentions = mockk<Mentions>()
        val mentionedMember = mockk<Member>()
        every { userOptionMapping.mentions } returns mentions
        every { mentions.members } returns listOf(mentionedMember)
        every { mentionedMember.idLong } returns 1L
        every { userService.createNewUser(any()) } returns mentionedUserDto
        every { mentionedMember.effectiveName } returns "Another Username"
    }

    private fun setupAttachments(attachmentOptionMapping: OptionMapping, fileExtension: String, fileSize: Int) {
        val messageAttachment = mockk<Message.Attachment>()
        val attachmentProxy = mockk<AttachmentProxy>()
        val inputStream = mockk<InputStream>()

        every { attachmentOptionMapping.asAttachment } returns messageAttachment
        every { messageAttachment.fileExtension } returns fileExtension
        every { messageAttachment.size } returns fileSize
        every { messageAttachment.proxy } returns attachmentProxy
        every { attachmentProxy.download() } returns CompletableFuture.completedFuture(inputStream)
    }
}
