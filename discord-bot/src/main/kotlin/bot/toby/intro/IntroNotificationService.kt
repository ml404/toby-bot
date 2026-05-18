package bot.toby.intro

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.InputData
import bot.toby.util.isUrl
import common.logging.DiscordLogger
import common.notification.NotificationChannelKind
import common.notification.Surface
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import database.dto.MusicDto
import database.service.MusicFileService
import database.service.UserNotificationPrefService
import bot.toby.helpers.UserDtoHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

/**
 * Owns the DM-driven "you don't have an intro yet, send me one" flow:
 * opens a private channel, prompts the user, waits for their reply via
 * EventWaiter, validates the response, and persists the resulting
 * musicDto. Lifted out of IntroHelper so the slash-command path doesn't
 * have to share a class with the EventWaiter wiring.
 */
@Service
class IntroNotificationService(
    private val userDtoHelper: UserDtoHelper,
    private val musicFileService: MusicFileService,
    private val httpHelper: HttpHelper,
    private val eventWaiter: EventWaiter,
    private val validationService: IntroValidationService,
    private val mediaLoader: IntroMediaLoader,
    // Nullable + default null so legacy callers / unit tests that
    // construct this service manually keep compiling. Null = no gate
    // (matches pre-refactor behaviour). Spring autowires the real bean
    // in production via IntroHelper's chained construction.
    private val notificationPrefService: UserNotificationPrefService? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(supervisorJob + dispatcher)

    fun promptUserForMusicInfo(user: User, guild: Guild) {
        logger.setGuildAndUserContext(guild, user)
        // Honour the per-user INTRO_PROMPT opt-out. Default is opt-in so
        // existing servers keep nudging new members; users who don't want
        // intro prompts run `/notify set INTRO_PROMPT off` and we silently
        // skip the DM (no waiter is set up either). Pref service is
        // nullable for legacy/test callers — null means "no gate".
        val gateActive = notificationPrefService
            ?.isOptedIn(user.idLong, guild.idLong, NotificationChannelKind.INTRO_PROMPT, Surface.DM)
            ?: true
        if (!gateActive) {
            logger.info { "User opted out of INTRO_PROMPT; skipping prompt for guild '${guild.name}'." }
            return
        }
        logger.info { "Prompting user to set an intro for the server that they don't have one on" }
        user.openPrivateChannel().queue { channel ->
            channel.sendMessage(
                "You don't have an intro song yet on server '${guild.name}'! " +
                        "Please reply with a YouTube URL or upload a music file, " +
                        "and optionally provide a volume level (1-100). " +
                        "E.g. 'https://www.youtube.com/watch?v=VIDEO_ID_HERE 90'"
            ).queue(invokeDeleteOnMessageResponse(5.minutes.toInt(DurationUnit.SECONDS)))

            setupWaiterForIntroMessage(user, channel, guild)
        }
    }

    private fun setupWaiterForIntroMessage(user: User, channel: PrivateChannel, guild: Guild) {
        eventWaiter.waitForMessage(
            { event -> event.author.idLong == user.idLong && event.channel == channel },
            { event -> handleUserMusicResponse(event, channel, guild) },
            5.minutes,
            {
                logger.info { "User did not set intro for the server that they don't have one on within the timeout period" }
                channel.sendMessage(
                    "You didn't respond in time, you can always use the '/setintro' command on server '${guild.name}'"
                ).queue(invokeDeleteOnMessageResponse(5.minutes.toInt(DurationUnit.SECONDS)))
            }
        )
    }

    private fun handleUserMusicResponse(event: MessageReceivedEvent, channel: PrivateChannel, guild: Guild) {
        val message = event.message
        val content = message.contentRaw
        val attachment = message.attachments.firstOrNull()

        when {
            attachment != null -> handleAttachmentInput(event, channel, guild, attachment, content)
            isUrl(content).isNotEmpty() -> handleUrlInput(event, channel, guild, content)
            else -> {
                channel.sendMessage("Please provide a valid URL or upload a file.").queue()
                setupWaiterForIntroMessage(event.author, channel, guild)
            }
        }
    }

    private fun handleAttachmentInput(
        event: MessageReceivedEvent,
        channel: PrivateChannel,
        guild: Guild,
        attachment: Attachment,
        content: String,
    ) {
        val inputData = InputData.Attachment(attachment)
        logger.info("User uploaded a music file: $inputData")

        if (!validationService.isValidAttachment(attachment)) {
            handleInvalidAttachment(channel, event.author, guild)
            return
        }

        val volume = validationService.parseVolume(content)
        saveUserMusicDto(event.author, guild, inputData, volume)
    }

    private fun handleUrlInput(event: MessageReceivedEvent, channel: PrivateChannel, guild: Guild, content: String) {
        logger.info("User provided a URL: $content")

        coroutineScope.launch {
            try {
                val isOverLimit = validationService.checkForOverlyLongIntroDuration(content)
                if (isOverLimit) {
                    handleOverLimitIntro(channel, event.author, guild)
                } else {
                    val title = runCatching { httpHelper.getYouTubeVideoTitle(content) }.getOrNull()
                    val inputData = InputData.Url(isUrl(content))
                    saveUserMusicDto(event.author, guild, inputData, validationService.parseVolume(content), title)
                }
            } catch (_: Exception) {
                logger.error { "Error checking intro length for '$content'" }
                handleOverLimitIntro(channel, event.author, guild)
            }
        }
    }

    private fun handleInvalidAttachment(channel: PrivateChannel, author: User, guild: Guild) {
        logger.info { "Intro was rejected for not adhering to attachment requirements, trying again..." }
        channel.sendMessage("Intro provided was either not an mp3 file or too large. Please try again.").queue()
        setupWaiterForIntroMessage(author, channel, guild)
    }

    private fun handleOverLimitIntro(channel: PrivateChannel, author: User, guild: Guild) {
        logger.info {
            "Intro was rejected for being over the specified intro limit length of " +
                    "${IntroValidationService.INTRO_LIMIT.inWholeSeconds} seconds, trying again..."
        }
        channel.sendMessage(
            "Intro provided was over ${IntroValidationService.INTRO_LIMIT.inWholeSeconds} seconds long, " +
                    "out of courtesy please pick a shorter intro."
        ).queue()
        setupWaiterForIntroMessage(author, channel, guild)
    }

    fun saveUserMusicDto(
        user: User,
        guild: Guild,
        inputData: InputData,
        volume: Int?,
        displayName: String? = null,
    ) {
        logger.info("Constructing musicDto from input ...")
        val requestingUserDto = userDtoHelper.calculateUserDto(user.idLong, guild.idLong)
        val fileName = displayName ?: determineFileName(inputData)
        val musicDto = MusicDto(requestingUserDto, 1, fileName, volume ?: 90, determineMusicBlob(inputData))
        musicFileService.createNewMusicFile(musicDto)
        logger.info { "User successfully uploaded intro as a result of the prompt!" }
        user.openPrivateChannel().queue { channel ->
            channel.sendMessage("Successfully set your intro on server '${guild.name}'")
                .queue(invokeDeleteOnMessageResponse(1.minutes.toInt(DurationUnit.SECONDS)))
        }
    }

    fun determineMusicBlob(input: InputData): ByteArray? = when (input) {
        is InputData.Url -> {
            logger.info("Processing URL input...")
            input.uri.toByteArray()
        }
        is InputData.Attachment -> {
            logger.info("Processing attachment input...")
            mediaLoader.downloadAttachment(input.attachment)?.let { mediaLoader.readContents(it) }
        }
    }

    fun determineFileName(input: InputData): String = when (input) {
        is InputData.Url -> input.uri.takeIf { it.isNotBlank() } ?: ""
        is InputData.Attachment -> input.attachment.fileName
    }
}
