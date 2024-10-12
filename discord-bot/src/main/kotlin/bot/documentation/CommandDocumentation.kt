package bot.documentation

data class CommandDocumentation(
    val name: String,
    val description: String,
    val options: List<OptionDocumentation>
)

data class OptionDocumentation(
    val name: String,
    val description: String,
    val type: String,
    val choices: List<ChoiceDocumentation>? = null
)

data class ChoiceDocumentation(
    val name: String,
    val value: String
)