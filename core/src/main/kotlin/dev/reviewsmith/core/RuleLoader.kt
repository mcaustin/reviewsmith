package dev.reviewsmith.core

object RuleLoader {
    private val bundled = listOf("correctness-safety", "evolution-safety")

    fun loadBundled(): List<Rule> = bundled.map { id ->
        val resource = "/reviewsmith/rules/$id.md"
        val text = RuleLoader::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("bundled rule not found: $resource")
        RuleParser.parse(id, text)
    }
}
