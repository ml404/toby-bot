package common.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Kotlin-idiomatic builder for `MessageEmbed`. Replaces the verbose
 * `EmbedBuilder().setTitle(...).setColor(...).addField(...).build()`
 * chain that appears in 35+ command and helper files (heaviest
 * concentration in `MusicPlayerHelper` — 7 sites in one file).
 *
 *   val embed = embed(title = "Oops", color = Color.RED) {
 *       field("Reason", reason)
 *       setFooter("Try again later")
 *   }
 *
 * The DSL is lightweight on purpose: callers still have full
 * `EmbedBuilder` access inside the lambda for the unusual cases
 * (timestamps, thumbnails, author blocks, etc.) — only the common
 * scaffolding is pre-applied.
 */
inline fun embed(
    title: String? = null,
    description: String? = null,
    color: Color? = null,
    block: EmbedBuilder.() -> Unit = {},
): MessageEmbed = EmbedBuilder().apply {
    title?.let { setTitle(it) }
    description?.let { setDescription(it) }
    color?.let { setColor(it) }
    block()
}.build()

/**
 * Field-builder shorthand. Returns the receiver so calls chain inside
 * the [embed] block:
 *
 *   embed { field("Total", "42").field("Detail", "...", inline = true) }
 */
fun EmbedBuilder.field(name: String, value: String, inline: Boolean = false): EmbedBuilder =
    addField(name, value, inline)
