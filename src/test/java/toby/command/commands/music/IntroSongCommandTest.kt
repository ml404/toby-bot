package toby.command.commands.music

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.utils.AttachmentProxy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
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
    private lateinit var introSongCommand: IntroSongCommand

    @Mock
    lateinit var userService: IUserService

    @Mock
    lateinit var musicFileService: IMusicFileService

    @Mock
    lateinit var configService: IConfigService
    
    private lateinit var mentionedUserDto: UserDto

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        userService = Mockito.mock(IUserService::class.java)
        musicFileService = Mockito.mock(IMusicFileService::class.java)
        configService = Mockito.mock(IConfigService::class.java)
        introSongCommand = IntroSongCommand(userService, musicFileService, configService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        Mockito.reset(userService)
        Mockito.reset(musicFileService)
        Mockito.reset(configService)
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttached_setsIntroViaUrl() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val linkOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("link")).thenReturn(linkOptionMapping)
        Mockito.`when`(linkOptionMapping.asString).thenReturn("https://www.youtube.com/")
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))

        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(musicFileService, Mockito.times(1)).createNewMusicFile(
            ArgumentMatchers.any(
                MusicDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Successfully set %s's intro song to '%s' with volume '%d'"),
            ArgumentMatchers.eq("UserName"),
            ArgumentMatchers.eq("https://www.youtube.com/"),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttachedWithExistingMusicFile_setsIntroViaUrl() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val linkOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("link")).thenReturn(linkOptionMapping)
        Mockito.`when`(linkOptionMapping.asString).thenReturn("https://www.youtube.com/")
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))
        Mockito.`when`<MusicDto>(CommandTest.requestingUserDto.musicDto)
            .thenReturn(MusicDto(1L, 1L, "filename", 20, null))

        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(musicFileService, Mockito.times(1)).updateMusicFile(
            ArgumentMatchers.any(
                MusicDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Successfully updated %s's intro song to '%s' with volume '%d'"),
            ArgumentMatchers.eq("UserName"),
            ArgumentMatchers.eq("https://www.youtube.com/"),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    fun testIntroSong_withSuperuser_andMentionedMembers_setsMentionedMembersIntroViaUrl() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val linkOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("link")).thenReturn(linkOptionMapping)
        Mockito.`when`(linkOptionMapping.asString).thenReturn("https://www.youtube.com/")
        setupMentions(userOptionMapping)
        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(musicFileService, Mockito.times(1)).createNewMusicFile(
            ArgumentMatchers.any(
                MusicDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Successfully set %s's intro song to '%s' with volume '%d'"),
            ArgumentMatchers.eq("Another Username"),
            ArgumentMatchers.eq("https://www.youtube.com/"),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    fun testIntroSong_withoutPermissionsAndSomeoneMentioned_andValidLinkAttached_doesNotSetIntroViaUrl() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val linkOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        val mentions = Mockito.mock(Mentions::class.java)
        Mockito.`when`(userOptionMapping.mentions).thenReturn(mentions)
        Mockito.`when`<List<Member>>(mentions.members).thenReturn(listOf(CommandTest.member))
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("link")).thenReturn(linkOptionMapping)
        Mockito.`when`(linkOptionMapping.asString).thenReturn("https://www.youtube.com/")
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))
        Mockito.`when`(CommandTest.guild.owner).thenReturn(CommandTest.member)
        Mockito.`when`(CommandTest.member.effectiveName).thenReturn("Effective Name")
        Mockito.`when`(CommandTest.requestingUserDto.superUser).thenReturn(false)

        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name"))
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withSuperuser_andValidAttachment_setsIntroViaAttachment_andCreatesNewMusicFile() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val attachmentOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("attachment"))
            .thenReturn(attachmentOptionMapping)
        setupAttachments(attachmentOptionMapping, "mp3", 1000)
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))

        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(musicFileService, Mockito.times(1)).createNewMusicFile(
            ArgumentMatchers.any(
                MusicDto::class.java
            )
        )
        Mockito.verify(userService, Mockito.times(1))
            .updateUser(ArgumentMatchers.eq(CommandTest.requestingUserDto))
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Successfully set %s's intro song to '%s' with volume '%d'"),
            ArgumentMatchers.eq("UserName"),
            ArgumentMatchers.eq("filename"),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withSuperuser_andMentionedMembers_setsIntroViaAttachment() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val attachmentOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("attachment"))
            .thenReturn(attachmentOptionMapping)
        setupAttachments(attachmentOptionMapping, "mp3", 1000)
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))
        setupMentions(userOptionMapping)

        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(userService, Mockito.times(1)).createNewUser(ArgumentMatchers.any(UserDto::class.java))
        Mockito.verify(musicFileService, Mockito.times(1)).createNewMusicFile(
            ArgumentMatchers.any(
                MusicDto::class.java
            )
        )
        Mockito.verify(userService, Mockito.times(1)).updateUser(ArgumentMatchers.eq(mentionedUserDto))
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Successfully set %s's intro song to '%s' with volume '%d'"),
            ArgumentMatchers.eq("Another Username"),
            ArgumentMatchers.eq("filename"),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withSuperuser_andValidAttachment_setsIntroViaAttachment_andUpdatesMusicFile() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val attachmentOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("attachment"))
            .thenReturn(attachmentOptionMapping)
        setupAttachments(attachmentOptionMapping, "mp3", 1000)
        Mockito.`when`<MusicDto>(CommandTest.requestingUserDto.musicDto)
            .thenReturn(MusicDto(1L, 1L, "filename", 20, null))
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))

        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(musicFileService, Mockito.times(1)).updateMusicFile(
            ArgumentMatchers.any(
                MusicDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Successfully updated %s's intro song to '%s' with volume '%d'"),
            ArgumentMatchers.eq("UserName"),
            ArgumentMatchers.eq("filename"),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withoutSuperuser_andInvalidAttachment_doesNotSetIntroViaAttachment() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val attachmentOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("attachment"))
            .thenReturn(attachmentOptionMapping)
        setupAttachments(attachmentOptionMapping, "notMp3", 1000)
        Mockito.`when`<MusicDto>(CommandTest.requestingUserDto.musicDto)
            .thenReturn(MusicDto(1L, 1L, "filename", 20, null))
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))
        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(musicFileService, Mockito.times(0)).updateMusicFile(
            ArgumentMatchers.any(
                MusicDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(0)).sendMessageFormat(
            ArgumentMatchers.eq("Successfully updated %s's intro song to '%s' with volume '%d'"),
            ArgumentMatchers.eq("UserName"),
            ArgumentMatchers.eq("filename"),
            ArgumentMatchers.eq(20)
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("Please use mp3 files only"))
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withoutSuperuser_andTooBigAttachment_doesNotSetIntroViaAttachment() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val attachmentOptionMapping = Mockito.mock(OptionMapping::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)

        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("attachment"))
            .thenReturn(attachmentOptionMapping)
        setupAttachments(attachmentOptionMapping, "mp3", 500000)
        Mockito.`when`<MusicDto>(CommandTest.requestingUserDto.musicDto)
            .thenReturn(MusicDto(1L, 1L, "filename", 20, null))
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))
        //Act
        introSongCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(musicFileService, Mockito.times(0)).updateMusicFile(
            ArgumentMatchers.any(
                MusicDto::class.java
            )
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(0)).sendMessageFormat(
            ArgumentMatchers.eq("Successfully updated %s's intro song to '%s' with volume '%d'"),
            ArgumentMatchers.eq("UserName"),
            ArgumentMatchers.eq("filename"),
            ArgumentMatchers.eq(20)
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("Please keep the file size under 400kb"))
    }

    private fun setupMentions(userOptionMapping: OptionMapping) {
        val mentions = Mockito.mock(Mentions::class.java)
        Mockito.`when`(userOptionMapping.mentions).thenReturn(mentions)
        val mentionedMember = Mockito.mock(
            Member::class.java
        )
        Mockito.`when`(mentions.members).thenReturn(listOf(mentionedMember))
        Mockito.`when`(mentionedMember.isOwner).thenReturn(false)
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto))
        Mockito.`when`(configService.getConfigByName("DEFAULT_VOLUME", "1"))
            .thenReturn(ConfigDto("DEFAULT_VOLUME", "20", "1"))
        mentionedUserDto = UserDto(2L, 1L,
            superUser = false,
            musicPermission = true,
            digPermission = true,
            memePermission = true,
            socialCredit = 0L,
            musicDto = null
        )
        Mockito.`when`(userService.createNewUser(ArgumentMatchers.any(UserDto::class.java)))
            .thenReturn(mentionedUserDto)
        Mockito.`when`(mentionedMember.effectiveName).thenReturn("Another Username")
        Mockito.`when`(mentionedMember.guild).thenReturn(CommandTest.guild)
        Mockito.`when`(mentionedMember.idLong).thenReturn(2L)
        CommandTest.requestingUserDto.superUser = true
    }

    companion object {
        @Throws(InterruptedException::class, ExecutionException::class, IOException::class)
        private fun setupAttachments(attachmentOptionMapping: OptionMapping, mp3: String, value: Int) {
            val messageAttachment = Mockito.mock(
                Message.Attachment::class.java
            )
            val attachmentProxy = Mockito.mock(AttachmentProxy::class.java)
            val inputStream = Mockito.mock(InputStream::class.java)
            Mockito.`when`(attachmentOptionMapping.asAttachment).thenReturn(messageAttachment)
            Mockito.`when`(messageAttachment.fileExtension).thenReturn(mp3)
            Mockito.`when`(messageAttachment.size).thenReturn(value)
            Mockito.`when`(messageAttachment.proxy).thenReturn(attachmentProxy)
            Mockito.`when`(messageAttachment.fileName).thenReturn("filename")
            val completableFuture = Mockito.mock(CompletableFuture::class.java)
            Mockito.`when`(attachmentProxy.download()).thenReturn(completableFuture as CompletableFuture<InputStream>?)
            Mockito.`when`(completableFuture.get()).thenReturn(inputStream)
            Mockito.`when`(inputStream.readAllBytes()).thenReturn(ByteArray(0))
        }
    }
}