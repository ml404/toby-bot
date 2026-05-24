package database.service.guild

import common.leveling.LevelCurve
import database.dto.guild.TitleDto

object TitlePurchasePolicy {
    sealed interface Result {
        data object Ok : Result
        data class LevelLocked(val required: Int, val actor: Int) : Result
    }

    fun check(title: TitleDto, actorXp: Long): Result {
        if (title.requiredLevel <= 0) return Result.Ok
        val level = LevelCurve.levelForXp(actorXp)
        return if (level >= title.requiredLevel) Result.Ok
        else Result.LevelLocked(required = title.requiredLevel, actor = level)
    }
}
