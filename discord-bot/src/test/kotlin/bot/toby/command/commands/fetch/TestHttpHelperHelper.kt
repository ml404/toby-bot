package bot.toby.command.commands.fetch

import bot.toby.helpers.HttpHelper
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object TestHttpHelperHelper {
    //GET requests
    const val DND_URL_START = "https://www.dnd5eapi.co/api"
    const val FIREBALL_INITIAL_URL = "$DND_URL_START/spells/fireball"
    const val BLIND_INITIAL_URL = "$DND_URL_START/conditions/blind"
    const val BLIND_QUERY_URL = "$DND_URL_START/conditions?name=blind"
    const val GRAPPLED_INITIAL_URL = "$DND_URL_START/conditions/grappled"
    const val COVER_INITIAL_URL = "$DND_URL_START/rule-sections/cover"
    const val ACTION_SURGE_INITIAL_URL = "$DND_URL_START/features/action-surge-1-use"
    const val BIN_INITIAL_URL = "$DND_URL_START/conditions/bin"
    const val BIN_QUERY_URL = "$DND_URL_START/conditions?name=bin"

    //Expected responses
    const val FIREBALL_INITIAL_RESPONSE = """{"index":"fireball","name":"Fireball","desc":["A bright streak flashes from your pointing finger to a point you choose within range and then blossoms with a low roar into an explosion of flame. Each creature in a 20-foot-radius sphere centered on that point must make a dexterity saving throw. A target takes 8d6 fire damage on a failed save, or half as much damage on a successful one.","The fire spreads around corners. It ignites flammable objects in the area that aren't being worn or carried."],"higher_level":["When you cast this spell using a spell slot of 4th level or higher, the damage increases by 1d6 for each slot level above 3rd."],"range":"150 feet","components":["V","S","M"],"material":"A tiny ball of bat guano and sulfur.","ritual":false,"duration":"Instantaneous","concentration":false,"casting_time":"1 action","level":3,"damage":{"damage_type":{"index":"fire","name":"Fire","url":"/api/damage-types/fire"},"damage_at_slot_level":{"3":"8d6","4":"9d6","5":"10d6","6":"11d6","7":"12d6","8":"13d6","9":"14d6"}},"dc":{"dc_type":{"index":"dex","name":"DEX","url":"/api/ability-scores/dex"},"dc_success":"half"},"area_of_effect":{"type":"sphere","size":20},"school":{"index":"evocation","name":"Evocation","url":"/api/magic-schools/evocation"},"classes":[{"index":"sorcerer","name":"Sorcerer","url":"/api/classes/sorcerer"},{"index":"wizard","name":"Wizard","url":"/api/classes/wizard"}],"subclasses":[{"index":"lore","name":"Lore","url":"/api/subclasses/lore"},{"index":"fiend","name":"Fiend","url":"/api/subclasses/fiend"}],"url":"/api/spells/fireball"}"""
    const val GRAPPLED_INITIAL_RESPONSE = """{"index":"grappled","name":"Grappled","desc":["- A grappled creature's speed becomes 0, and it can't benefit from any bonus to its speed.","- The condition ends if the grappler is incapacitated (see the condition).","- The condition also ends if an effect removes the grappled creature from the reach of the grappler or grappling effect, such as when a creature is hurled away by the thunderwave spell."],"url":"/api/conditions/grappled"}"""
    const val COVER_INITIAL_RESPONSE = """{"name":"Cover","index":"cover","desc":"## Cover\n\nWalls, trees, creatures, and other obstacles can provide cover during combat, making a target more difficult to harm. A target can benefit from cover only when an attack or other effect originates on the opposite side of the cover.\n\nThere are three degrees of cover. If a target is behind multiple sources of cover, only the most protective degree of cover applies; the degrees aren't added together. For example, if a target is behind a creature that gives half cover and a tree trunk that gives three-quarters cover, the target has three-quarters cover.\n\nA target with **half cover** has a +2 bonus to AC and Dexterity saving throws. A target has half cover if an obstacle blocks at least half of its body. The obstacle might be a low wall, a large piece of furniture, a narrow tree trunk, or a creature, whether that creature is an enemy or a friend.\n\nA target with **three-quarters cover** has a +5 bonus to AC and Dexterity saving throws. A target has three-quarters cover if about three-quarters of it is covered by an obstacle. The obstacle might be a portcullis, an arrow slit, or a thick tree trunk.\n\nA target with **total cover** can't be targeted directly by an attack or a spell, although some spells can reach such a target by including it in an area of effect. A target has total cover if it is completely concealed by an obstacle.\n","url":"/api/rule-sections/cover"}"""
    const val ACTION_SURGE_RESPONSE = """{"index":"action-surge-1-use","class":{"index":"fighter","name":"Fighter","url":"/api/classes/fighter"},"name":"Action Surge (1 use)","level":2,"prerequisites":[],"desc":["Starting at 2nd level, you can push yourself beyond your normal limits for a moment. On your turn, you can take one additional action on top of your regular action and a possible bonus action.","Once you use this feature, you must finish a short or long rest before you can use it again. Starting at 17th level, you can use it twice before a rest, but only once on the same turn."],"url":"/api/features/action-surge-1-use"}"""
    const val BLIND_QUERY_RESPONSE =
        """{"count":1,"results":[{"index":"blinded","name":"Blinded","url":"/api/conditions/blinded"}]}"""
    const val ERROR_NOT_FOUND_RESPONSE = """{"error":"Not found"}"""
    const val EMPTY_QUERY_RESPONSE = """{"count":0,"results":[]}"""



    fun createMockHttpClient(
        initialUrlRequest: String = "",
        initialResponseJson: String = "",
        queryRematchUrlRequest: String = "",
        queryRematchResponse: String = "",
        initialResponseType: HttpStatusCode = HttpStatusCode.OK,
        queryResponseType: HttpStatusCode = HttpStatusCode.OK,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): HttpHelper {
        val mockEngine = MockEngine { request ->
            // Check the request URL and provide a response
            when (request.url.toString()) {
                initialUrlRequest -> {
                    respond(
                        content = initialResponseJson,
                        status = initialResponseType,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }

                queryRematchUrlRequest -> {
                    respond(
                        content = queryRematchResponse,
                        status = queryResponseType,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }

                else -> respond(
                    content = ERROR_NOT_FOUND_RESPONSE,
                    status = HttpStatusCode.NotFound
                )
            }
        }

        // Create HttpClient with MockEngine
        val client = HttpClient(mockEngine)

        // Create an instance of HttpHelper
        return HttpHelper(client, dispatcher)
    }
}