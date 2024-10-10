package toby.button.buttons

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

interface IButtonContext {

    /**
     * Returns the [net.dv8tion.jda.api.entities.Guild] for the current command/event
     *
     * @return the [net.dv8tion.jda.api.entities.Guild] for this command/event
     */
    val guild: Guild

    /**
     * Returns the [message event][net.dv8tion.jda.api.events.message.MessageReceivedEvent] that was received for this instance
     *
     * @return the [message event][net.dv8tion.jda.api.events.message.MessageReceivedEvent] that was received for this instance
     */
    val event: ButtonInteractionEvent
    val channel: TextChannel?
        /**
         * Returns the [channel][TextChannel] that the message for this event was send in
         *
         * @return the [channel][TextChannel] that the message for this event was send in
         */
        get() = event.channel.asTextChannel()
    val author: User?
        /**
         * Returns the [author][net.dv8tion.jda.api.entities.User] of the message as user
         *
         * @return the [author][net.dv8tion.jda.api.entities.User] of the message as user
         */
        get() = event.user
    val member: Member?
        /**
         * Returns the [author][net.dv8tion.jda.api.entities.Member] of the message as member
         *
         * @return the [author][net.dv8tion.jda.api.entities.Member] of the message as member
         */
        get() = event.member
    val jDA: JDA
        /**
         * Returns the current [jda][net.dv8tion.jda.api.JDA] instance
         *
         * @return the current [jda][net.dv8tion.jda.api.JDA] instance
         */
        get() = event.jda
   
    val selfMember: Member?
        /**
         * Returns the [member][net.dv8tion.jda.api.entities.Member] in the guild for the currently logged in account
         *
         * @return the [member][net.dv8tion.jda.api.entities.Member] in the guild for the currently logged in account
         */
        get() = guild.selfMember
}