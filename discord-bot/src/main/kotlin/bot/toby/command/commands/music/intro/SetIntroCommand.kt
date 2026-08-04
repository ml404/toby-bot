package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.PendingIntro
import bot.toby.helpers.URLHelper
import bot.toby.intro.IntroPresenter
import bot.toby.lavaplayer.PlayerManager
import common.intro.IntroCapture
import common.intro.IntroClip
import common.intro.IntroSlots
import core.command.CommandContext
import database.dto.music.MusicDto
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
 * `/setintro link|attachment|current|copy` — add or replace an intro.
 *
 * `link` and `attachment` take optional `start`/`end` clip bounds. Without them
 * a source longer than [IntroClip.MAX_DURATION_SECONDS] is still rejected, as
 * before; with them a long track is fair game as long as the *clip* fits.
 * That's how the web form has always behaved, and how the player has always
 * read `MusicDto.startMs`/`endMs` back at join time — the slash command was
 * the only place that couldn't express it.
 *
 * `current` and `copy` exist because the two things people actually want an
 * intro to be are almost never a URL they have to hand. One is the track
 * playing in the channel right now; the other is the intro somebody else
 * already has. Both used to mean going and finding the original link again.
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
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        if (!requestingUserDto.superUser && event.getOption(USERS)?.mentions?.members.orEmpty().isNotEmpty()) {
            sendErrorMessage(event, deleteDelay)
            return
        }

        // Copying takes its volume and clip from the intro being copied, so it
        // shares none of the option parsing below.
        if (event.subcommandName == COPY) {
            handleCopy(event, requestingUserDto, deleteDelay)
            return
        }

        val introVolume = introHelper.calculateIntroVolume(event)

        // Returns null after replying with the specific parse error.
        val clip = parseClipOptions(event) ?: return
        val source = resolveSource(event, instance, clip) ?: return

        val mentionedMembers = event.getOptionMentionedMembers()

        if (mentionedMembers.isEmpty()) {
            // No mentions: use requesting user
            checkAndSetIntro(event, requestingUserDto, event.user.effectiveName, deleteDelay, introVolume, source)
        } else {
            // Mentions: map each member to their DTO inside the loop
            mentionedMembers.forEach { member ->
                val memberDto = introHelper.findUserById(member.idLong, member.guild.idLong)
                checkAndSetIntro(event, memberDto, member.effectiveName, deleteDelay, introVolume, source)
            }
        }
    }

    /**
     * Where the audio is coming from, once the subcommand has had its say.
     *
     * [title] and [sourceDurationMs] are set when the caller already knows
     * them — `current` reads both off the loaded track — which saves the
     * lookups the other subcommands have to make, and covers sources those
     * lookups can't reach.
     */
    private data class Source(
        val link: String,
        val attachment: OptionMapping?,
        val clip: Clip,
        val title: String? = null,
        val sourceDurationMs: Int? = null,
    )

    private fun resolveSource(
        event: SlashCommandInteractionEvent,
        instance: PlayerManager,
        clip: Clip,
    ): Source? = if (event.subcommandName == CURRENT) {
        resolveCurrentTrack(event, instance, clip)
    } else {
        Source(event.getOption(LINK)?.asString.orEmpty(), event.getOption(ATTACHMENT), clip)
    }

    /**
     * Reads the source off whatever the guild is playing.
     *
     * The clip defaults to a window ending at the playhead rather than
     * starting from it: you run this *after* hearing the bit you liked, so
     * fifteen seconds forward from here is the wrong fifteen seconds. Explicit
     * `start`/`end` options override either bound.
     */
    private fun resolveCurrentTrack(
        event: SlashCommandInteractionEvent,
        instance: PlayerManager,
        clip: Clip,
    ): Source? {
        val guild = event.guild ?: return refuse(event, GUILD_ONLY)
        val scheduler = instance.getMusicManager(guild).scheduler

        val track = scheduler.currentTrack()
            ?: return refuse(event, "Nothing is playing right now — start something with `/play` first.")
        if (scheduler.isIntroTrack(track)) {
            return refuse(
                event,
                "That's an intro playing, not a track. Wait for the music to come back and try again.",
            )
        }
        val title = track.info.title
        if (track.info.isStream) {
            return refuse(
                event,
                "`$title` is a live stream, so there's no fixed clip to save. " +
                    "Use `/setintro link` with a recording instead.",
            )
        }
        val url = track.info.uri?.takeIf { URLHelper.isValidURL(it) }
            ?: return refuse(event, "`$title` has no link to save, so it can't be re-played as an intro.")

        val window = IntroCapture.windowFor(track.position, track.duration)
            ?: return refuse(event, "`$title` hasn't played long enough yet to take a clip from.")

        return Source(
            link = url,
            attachment = null,
            clip = Clip(clip.startMs ?: window.startMs, clip.endMs ?: window.endMs),
            title = title,
            // Streams are already refused above, so anything left is a real
            // length — but it still has to fit the Int the clip rules use.
            sourceDurationMs = track.duration.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
        )
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
        userName: String,
        deleteDelay: Int,
        introVolume: Int,
        source: Source,
    ) {
        // One duration lookup, then the shared clip rules decide. Replaces the
        // old boolean "is this over 15 seconds" gate, which had no way to
        // account for a user-supplied clip and so rejected every long source.
        validateClip(source) { error ->
            if (error != null) {
                logger.info { "Intro was rejected: $error" }
                event.hook.sendMessage(error)
                    .setEphemeral(true)
                    .queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
            } else {
                validateAndSetIntro(event, requestingUserDto, userName, deleteDelay, introVolume, source)
            }
        }
    }

    /**
     * The same rules everywhere, but only one round trip when the source
     * duration is already known — which also covers the sources the
     * YouTube-only lookup would have shrugged at and passed.
     */
    private fun validateClip(source: Source, onResult: (String?) -> Unit) {
        source.sourceDurationMs?.let {
            onResult(IntroClip.validate(source.clip.startMs, source.clip.endMs, it))
            return
        }
        introHelper.validateClipAgainstSource(
            source.link.takeIf { it.isNotEmpty() }, source.clip.startMs, source.clip.endMs, onResult
        )
    }

    private fun validateAndSetIntro(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        userName: String,
        deleteDelay: Int,
        introVolume: Int,
        source: Source,
    ) {
        when {
            checkForOverIntroLimit(event.hook, event.member!!, requestingUserDto, introVolume, source) -> return
            source.link.isNotEmpty() -> introHelper.handleUrl(
                event, requestingUserDto, userName, deleteDelay, URLHelper.fromUrlString(source.link),
                introVolume, null, source.clip.startMs, source.clip.endMs, source.title
            )
            source.attachment != null -> introHelper.handleAttachment(
                event, requestingUserDto, userName, deleteDelay, source.attachment.asAttachment,
                introVolume, null, source.clip.startMs, source.clip.endMs
            )
            else -> event.hook.sendMessage("Please provide a valid link or attachment")
                .queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
        }
    }

    private fun checkForOverIntroLimit(
        hook: InteractionHook,
        member: Member,
        requestingUserDto: UserDto,
        introVolume: Int,
        source: Source,
    ): Boolean {
        val introList = requestingUserDto.musicDtos
        if (introList.size >= LIMIT) {
            introHelper.parkPendingIntro(
                requestingUserDto.guildId,
                requestingUserDto.discordId,
                PendingIntro(
                    attachment = source.attachment?.asAttachment,
                    url = source.link.takeIf { it.isNotEmpty() },
                    volume = introVolume,
                    startMs = source.clip.startMs,
                    endMs = source.clip.endMs,
                ),
            )
            sendReplacePrompt(hook, member, introList)
            return true
        }
        return false
    }

    // --- copy ---------------------------------------------------------------

    /**
     * Copies another member's intro into one of the caller's own slots.
     *
     * Deliberately not gated on being a super-user: this reads someone's
     * intro, which plays out loud to the whole channel every time they join,
     * and writes only to the caller's own slots. What a copy carries across
     * and what it leaves behind is [IntroHelper.copyIntroInto]'s business,
     * shared with the right-click **View intros** menu.
     */
    private fun handleCopy(event: SlashCommandInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int) {
        val guild = event.guild ?: run { refuse(event, GUILD_ONLY); return }
        val sourceMember = event.getOption(FROM)?.asMember
            ?: run { refuse(event, "Say whose intro to copy with `from:`."); return }

        val theirs = IntroPresenter.sorted(introHelper.findUserById(sourceMember.idLong, guild.idLong).musicDtos)
        if (theirs.isEmpty()) {
            refuse(event, "${sourceMember.effectiveName} hasn't set an intro on this server.")
            return
        }

        val source = pickSourceIntro(event, sourceMember, theirs) ?: return
        val destination = pickDestinationSlot(event, requestingUserDto) ?: return

        val name = IntroPresenter.displayName(source)
        val saved = introHelper.copyIntroInto(source, requestingUserDto, destination.slot, destination.replacing)

        if (saved == null) {
            logger.warn { "Failed to copy intro '$name' into slot ${destination.slot}" }
            refuse(
                event,
                "Couldn't copy **$name** into slot #${destination.slot} — you may already have it as an intro.",
            )
            return
        }

        logger.info { "Copied ${sourceMember.idLong}'s intro '$name' into slot ${destination.slot}" }
        event.hook.sendMessage(
            "Copied **$name** from ${sourceMember.effectiveName} into your slot #${destination.slot} " +
                "(volume ${source.introVolume ?: 100}%, clip ${IntroClip.describe(source.startMs, source.endMs)})."
        ).setEphemeral(true).queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
    }

    /** Which of [theirs] to copy: the named slot, or the only one they have. */
    private fun pickSourceIntro(
        event: SlashCommandInteractionEvent,
        sourceMember: Member,
        theirs: List<MusicDto>,
    ): MusicDto? {
        val requested = event.getOption(SLOT)?.asInt
        if (requested != null) {
            return theirs.firstOrNull { slotNumberOf(it) == requested }
                ?: refuse(
                    event,
                    "${sourceMember.effectiveName} has nothing in slot #$requested. " +
                        "They have ${describeSlots(theirs)} — see them with `/listintros user:`.",
                )
        }
        return theirs.singleOrNull() ?: refuse(
            event,
            "${sourceMember.effectiveName} has ${theirs.size} intros (${describeSlots(theirs)}) — " +
                "say which with `slot:`.",
        )
    }

    /** The caller's slot to write into, and what (if anything) is already there. */
    private data class Destination(val slot: Int, val replacing: MusicDto?)

    private fun pickDestinationSlot(event: SlashCommandInteractionEvent, requestingUserDto: UserDto): Destination? {
        val mine = requestingUserDto.musicDtos
        val requested = event.getOption(INTO)?.asInt
        if (requested != null) {
            if (requested !in 1..IntroSlots.MAX_INTRO_COUNT) {
                return refuse(event, "Slots run from 1 to ${IntroSlots.MAX_INTRO_COUNT}.")
            }
            // Matched on the id first: the id is what decides which row a write
            // lands on, and it can disagree with `index` on older rows.
            val occupant = mine.firstOrNull { IntroSlots.slotFromId(it.id) == requested }
                ?: mine.firstOrNull { it.index == requested }
            return Destination(requested, occupant)
        }
        // Allocated against ids for the same reason.
        val free = introHelper.freeSlotFor(requestingUserDto)
            ?: return refuse(
                event,
                "You already have ${IntroSlots.MAX_INTRO_COUNT} intros — pass `into:` to say which to " +
                    "replace, or free one up with `/deleteintro`.",
            )
        return Destination(free, null)
    }

    /** `#1, #3` — the slots a member actually has something in. */
    private fun describeSlots(intros: List<MusicDto>): String =
        intros.mapNotNull { slotNumberOf(it) }.joinToString(", ") { "#$it" }

    /** The slot number as the user reads it in `/listintros`. */
    private fun slotNumberOf(intro: MusicDto): Int? = intro.index ?: IntroSlots.slotFromId(intro.id)

    /** Sends an ephemeral refusal and returns null, so callers can `?: return`. */
    private fun refuse(event: SlashCommandInteractionEvent, message: String): Nothing? {
        event.hook.sendMessage(message).setEphemeral(true).queue()
        return null
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
                .addOptions(volumeOption())
                .addOption(OptionType.STRING, START, "Clip start, e.g. 0:12 — lets you use a longer video")
                .addOption(
                    OptionType.STRING, END,
                    "Clip end, e.g. 0:24 (max ${IntroClip.MAX_DURATION_SECONDS}s of clip)"
                )
                .addOption(OptionType.MENTIONABLE, USERS, "User whose intro to change"),
            SubcommandData(ATTACHMENT, "Set intro via file upload")
                .addOption(OptionType.ATTACHMENT, ATTACHMENT, "Attachment (file) to set as your discord intro", true)
                .addOptions(volumeOption())
                .addOption(OptionType.STRING, START, "Clip start, e.g. 0:12")
                .addOption(
                    OptionType.STRING, END,
                    "Clip end, e.g. 0:24 (max ${IntroClip.MAX_DURATION_SECONDS}s of clip)"
                )
                .addOption(OptionType.MENTIONABLE, USERS, "User whose intro to change"),
            SubcommandData(CURRENT, "Set the track playing right now as your intro")
                .addOptions(volumeOption())
                .addOption(
                    OptionType.STRING, START,
                    "Clip start — defaults to ${IntroClip.MAX_DURATION_SECONDS}s before where the track is now"
                )
                .addOption(OptionType.STRING, END, "Clip end — defaults to where the track is now")
                .addOption(OptionType.MENTIONABLE, USERS, "User whose intro to change"),
            SubcommandData(COPY, "Copy someone else's intro into one of your slots")
                .addOption(OptionType.USER, FROM, "Whose intro to copy", true)
                .addOptions(
                    OptionData(OptionType.INTEGER, SLOT, "Which of their slots to copy (see /listintros)")
                        .setRequiredRange(1, IntroSlots.MAX_INTRO_COUNT.toLong()),
                    OptionData(OptionType.INTEGER, INTO, "Which of your slots to write to")
                        .setRequiredRange(1, IntroSlots.MAX_INTRO_COUNT.toLong()),
                ),
        )

    // Declared on the option so Discord rejects an out-of-range value in
    // the client, rather than the bot silently coercing it after the fact.
    private fun volumeOption() = OptionData(OptionType.INTEGER, VOLUME, "Volume to set your intro to")
        .setRequiredRange(MIN_VOLUME.toLong(), MAX_VOLUME.toLong())

    companion object {
        private const val VOLUME = "volume"
        private const val USERS = "users"
        private const val LINK = "link"
        private const val ATTACHMENT = "attachment"
        private const val START = "start"
        private const val END = "end"

        internal const val CURRENT = "current"
        internal const val COPY = "copy"
        private const val FROM = "from"
        private const val SLOT = "slot"
        private const val INTO = "into"

        private const val MIN_VOLUME = 1
        private const val MAX_VOLUME = 100
        private const val GUILD_ONLY = "Intros are per-server — run this inside the server you want to manage."
        private val LIMIT = IntroSlots.MAX_INTRO_COUNT
    }
}
