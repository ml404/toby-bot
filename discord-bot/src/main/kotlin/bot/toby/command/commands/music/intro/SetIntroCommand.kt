package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.PendingIntro
import bot.toby.helpers.URLHelper
import bot.toby.lavaplayer.PlayerManager
import common.intro.IntroClip
import common.intro.IntroSlots
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/setintro link|attachment` — add or replace an intro.
 *
 * Both subcommands take optional `start`/`end` clip bounds. Without them a
 * source longer than [IntroClip.MAX_DURATION_SECONDS] is still rejected, as
 * before; with them a long track is fair game as long as the *clip* fits.
 * That's how the web form has always behaved, and how the player has always
 * read `MusicDto.startMs`/`endMs` back at join time — the slash command was
 * the only place that couldn't express it.
 */
@Component
class SetIntroCommand @Autowired constructor(
    private val introHelper: IntroHelper
) : MusicCommand {

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ) {
        val event = ctx.event
        val introVolume = introHelper.calculateIntroVolume(event)
        val attachmentOption = event.getOption(ATTACHMENT)
        val linkOption = event.getOption(LINK)?.asString.orEmpty()

        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        if (!requestingUserDto.superUser && event.getOption(USERS)?.mentions?.members.orEmpty().isNotEmpty()) {
            sendErrorMessage(event, deleteDelay)
            return
        }

        // Returns null after replying with the specific parse error.
        val clip = parseClipOptions(event) ?: return

        val mentionedMembers = event.getOptionMentionedMembers()

        if (mentionedMembers.isEmpty()) {
            // No mentions: use requesting user
            checkAndSetIntro(
                event,
                requestingUserDto,
                linkOption,
                event.user.effectiveName,
                deleteDelay,
                introVolume,
                attachmentOption,
                clip
            )
        } else {
            // Mentions: map each member to their DTO inside the loop
            mentionedMembers.forEach { member ->
                val memberDto = introHelper.findUserById(member.idLong, member.guild.idLong)
                checkAndSetIntro(
                    event, memberDto, linkOption, member.effectiveName, deleteDelay,
                    introVolume, attachmentOption, clip
                )
            }
        }
    }

    /** Reads the optional `start`/`end` options into milliseconds. */
    private fun parseClipOptions(event: SlashCommandInteractionEvent): Clip? {
        val start = IntroClip.parseTimestamp(event.getOption(START)?.asString, "Clip start")
        if (start is IntroClip.Parsed.Invalid) {
            event.hook.sendMessage(start.message).setEphemeral(true).queue()
            return null
        }
        val end = IntroClip.parseTimestamp(event.getOption(END)?.asString, "Clip end")
        if (end is IntroClip.Parsed.Invalid) {
            event.hook.sendMessage(end.message).setEphemeral(true).queue()
            return null
        }
        return Clip((start as IntroClip.Parsed.Ok).ms, (end as IntroClip.Parsed.Ok).ms)
    }

    private fun checkAndSetIntro(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        linkOption: String,
        userName: String,
        deleteDelay: Int,
        introVolume: Int,
        attachmentOption: OptionMapping?,
        clip: Clip
    ) {
        // One duration lookup, then the shared clip rules decide. Replaces the
        // old boolean "is this over 15 seconds" gate, which had no way to
        // account for a user-supplied clip and so rejected every long source.
        introHelper.validateClipAgainstSource(
            linkOption.takeIf { it.isNotEmpty() }, clip.startMs, clip.endMs
        ) { error ->
            if (error != null) {
                logger.info { "Intro was rejected: $error" }
                event.hook.sendMessage(error)
                    .setEphemeral(true)
                    .queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
            } else {
                validateAndSetIntro(
                    event, requestingUserDto, linkOption, attachmentOption,
                    introVolume, userName, deleteDelay, clip
                )
            }
        }
    }

    private fun validateAndSetIntro(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        linkOption: String,
        attachmentOption: OptionMapping?,
        introVolume: Int,
        userName: String,
        deleteDelay: Int,
        clip: Clip
    ) {
        when {
            checkForOverIntroLimit(
                event.hook, event.member!!, requestingUserDto, linkOption, attachmentOption, introVolume, clip
            ) -> return
            linkOption.isNotEmpty() -> introHelper.handleUrl(
                event, requestingUserDto, userName, deleteDelay, URLHelper.fromUrlString(linkOption),
                introVolume, null, clip.startMs, clip.endMs
            )
            attachmentOption != null -> introHelper.handleAttachment(
                event, requestingUserDto, userName, deleteDelay, attachmentOption.asAttachment,
                introVolume, null, clip.startMs, clip.endMs
            )
            else -> event.hook.sendMessage("Please provide a valid link or attachment")
                .queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
        }
    }

    private fun checkForOverIntroLimit(
        hook: InteractionHook,
        member: Member,
        requestingUserDto: UserDto,
        linkOption: String? = null,
        attachmentOption: OptionMapping? = null,
        introVolume: Int,
        clip: Clip
    ): Boolean {
        val introList = requestingUserDto.musicDtos
        if (introList.size >= LIMIT) {
            introHelper.parkPendingIntro(
                requestingUserDto.guildId,
                requestingUserDto.discordId,
                PendingIntro(
                    attachment = attachmentOption?.asAttachment,
                    url = linkOption,
                    volume = introVolume,
                    startMs = clip.startMs,
                    endMs = clip.endMs,
                ),
            )
            sendReplacePrompt(hook, member, introList)
            return true
        }
        return false
    }

    private fun SlashCommandInteractionEvent.getOptionMentionedMembers(): List<Member> =
        this.getOption(USERS)?.mentions?.members.orEmpty()

    /** Parsed `start`/`end` options, in milliseconds; null means "no bound". */
    private data class Clip(val startMs: Int?, val endMs: Int?)

    override val name: String get() = "setintro"
    override val description: String get() = "Upload an **MP3** file or link to play when you join a voice channel."

    override val subCommands: List<SubcommandData>
        get() = listOf(
            SubcommandData(LINK, "Set intro via YouTube link")
                .addOption(OptionType.STRING, LINK, "Link to set as your discord intro", true)
                .addOptions(
                    OptionData(OptionType.INTEGER, VOLUME, "Volume to set your intro to")
                        .setRequiredRange(MIN_VOLUME.toLong(), MAX_VOLUME.toLong())
                )
                .addOption(OptionType.STRING, START, "Clip start, e.g. 0:12 — lets you use a longer video")
                .addOption(
                    OptionType.STRING, END,
                    "Clip end, e.g. 0:24 (max ${IntroClip.MAX_DURATION_SECONDS}s of clip)"
                )
                .addOption(OptionType.MENTIONABLE, USERS, "User whose intro to change"),
            SubcommandData(ATTACHMENT, "Set intro via file upload")
                .addOption(OptionType.ATTACHMENT, ATTACHMENT, "Attachment (file) to set as your discord intro", true)
                .addOptions(
                    OptionData(OptionType.INTEGER, VOLUME, "Volume to set your intro to")
                        .setRequiredRange(MIN_VOLUME.toLong(), MAX_VOLUME.toLong())
                )
                .addOption(OptionType.STRING, START, "Clip start, e.g. 0:12")
                .addOption(
                    OptionType.STRING, END,
                    "Clip end, e.g. 0:24 (max ${IntroClip.MAX_DURATION_SECONDS}s of clip)"
                )
                .addOption(OptionType.MENTIONABLE, USERS, "User whose intro to change")
        )

    companion object {
        private const val VOLUME = "volume"
        private const val USERS = "users"
        private const val LINK = "link"
        private const val ATTACHMENT = "attachment"
        private const val START = "start"

        // Declared on the option so Discord rejects an out-of-range value in
        // the client, rather than the bot silently coercing it after the fact.
        private const val MIN_VOLUME = 1
        private const val MAX_VOLUME = 100
        private const val END = "end"
        private val LIMIT = IntroSlots.MAX_INTRO_COUNT
    }
}
