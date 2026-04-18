package bot.toby.helpers

import net.dv8tion.jda.api.entities.Member

fun List<Member>.nonBots(): List<Member> = filter { !it.user.isBot }
