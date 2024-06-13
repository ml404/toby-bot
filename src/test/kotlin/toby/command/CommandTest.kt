package toby.command

import io.mockk.*
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.ItemComponent
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import net.dv8tion.jda.internal.requests.restaction.WebhookMessageCreateActionImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import toby.jpa.dto.UserDto

interface CommandTest {
    @BeforeEach
    fun setUpCommonMocks() {
        every { event.hook } returns interactionHook
        every { event.deferReply() } returns replyCallbackAction
        every { event.guild } returns guild
        every { event.user } returns user
        every { event.reply(any<String>()) } just awaits
        every { event.replyFormat(any()) } returns replyCallbackAction
        every { event.member } returns member
        every { event.channel } returns messageChannelUnion
        every { event.hook.deleteOriginal() } returns restAction as RestAction<Void>
        every { event.hook.sendMessage(any<String>()) } returns webhookMessageCreateAction
        every { event.hook.sendMessageFormat(any(), *anyVararg()) } returns webhookMessageCreateAction
        every { event.hook.sendMessageEmbeds(any(), any<MessageEmbed>()) } returns webhookMessageCreateAction
        every { event.hook.sendMessageEmbeds(any(), *anyVararg()) } returns webhookMessageCreateAction
        every { event.options } returns emptyList()
        every { user.effectiveName } returns "UserName"
        every { user.idLong } returns 1L
        every { user.id } returns "1"
        every { user.name } returns "UserName"
        every { user.isBot } returns false
        every { interactionHook.deleteOriginal() } returns restAction
        every { interactionHook.sendMessage(any<String>()) } returns webhookMessageCreateAction
        every { interactionHook.sendMessage(any<String>()).queue(any()) } just Runs
        every { interactionHook.sendMessageFormat(any(), *anyVararg()) } returns webhookMessageCreateAction
        every { interactionHook.retrieveOriginal() } returns restAction as RestAction<Message>
        every { interactionHook.sendMessageEmbeds(any(), any<MessageEmbed>()) } returns webhookMessageCreateAction
        every { replyCallbackAction.setEphemeral(any()) } returns replyCallbackAction
        every { replyCallbackAction.queue() } just runs
        every { webhookMessageCreateAction.addActionRow(any<ItemComponent>()) } just Awaits
        every { webhookMessageCreateAction.addContent(any()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.setEphemeral(any()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.setActionRow(*anyVararg()).queue() } just Runs
        every { webhookMessageCreateAction.setEphemeral(any()).queue(any()) } just Runs
        every { webhookMessageCreateAction.queue(any()) } just Runs
        every { messageChannelUnion.sendMessage(any<String>()) } returns messageCreateAction
        every { messageCreateAction.addContent(any()) } returns messageCreateAction
        every { guild.jda } returns jda
        every { guild.idLong } returns 1L
        every { guild.id } returns "1"
        every { guild.owner } returns member
        every { guild.selfMember } returns botMember
        every { guild.channels} returns emptyList()
        every { guild.maxBitrate} returns  Int.MAX_VALUE
        every { member.nickname } returns "Member Nickname"
        every { member.idLong } returns 1L
        every { member.effectiveName } returns "Effective Name"
        every { member.guild } returns guild
        every { member.voiceState } returns guildVoiceState
        every { member.isOwner } returns false
        every { targetMember.nickname } returns "Target Nickname"
        every { targetMember.idLong } returns 1L
        every { targetMember.isOwner } returns false
        every { targetMember.effectiveName } returns "Target Effective Name"
        every { targetMember.guild } returns guild
        every { botMember.nickname } returns "Bot Nickname"
        every { botMember.effectiveName } returns "Bot Effective Name"
        every { botMember.guild } returns guild
        every { requestingUserDto.superUser } returns true
        every { requestingUserDto.memePermission } returns true
        every { requestingUserDto.musicPermission } returns true
        every { requestingUserDto.digPermission } returns true
        every { requestingUserDto.discordId } returns 1L
        every { requestingUserDto.guildId } returns 1L
        every { requestingUserDto.socialCredit } returns 0L
        every { requestingUserDto.musicDto } returns null
        every { guildVoiceState.channel } returns audioChannelUnion
        every { audioChannelUnion.members } returns listOf(member)
    }

    @AfterEach
    fun tearDownCommonMocks() {
        clearAllMocks()
    }

    companion object {
        val event: SlashCommandInteractionEvent = mockk()
        val interactionHook: InteractionHook = mockk()
        val message: Message = mockk()
        val guild: Guild = mockk(relaxed = true)
        val user: User = mockk()
        val jda: JDA = mockk()
        val member: Member = mockk()
        val targetMember: Member = mockk()
        val botMember: Member = mockk()
        val messageChannelUnion: MessageChannelUnion = mockk()
        val requestingUserDto: UserDto = mockk(relaxed = true)
        val webhookMessageCreateAction: WebhookMessageCreateActionImpl<Message> = mockk()
        val messageCreateAction: MessageCreateAction = mockk()
        val replyCallbackAction: ReplyCallbackAction = mockk()
        val restAction: RestAction<*> = mockk()
        val guildVoiceState: GuildVoiceState = mockk()
        val audioChannelUnion: AudioChannelUnion = mockk()
    }
}
