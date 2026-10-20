package dev.codenb.frontend

enum class GroupingMode {
    NAMESPACE,
    DIRECTORY,
}

data class LanguageFrontend(
    val id: String,
    val languageId: String,
    val extensions: Set<String>,
    val defaultCommand: List<String>,
    val grouping: GroupingMode,
    val rootMarkers: List<String> = emptyList(),
    val include: List<String> = listOf("**/*"),
    val exclude: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Frontend id must not be blank" }
        require(languageId.isNotBlank()) { "Language id must not be blank" }
        require(extensions.isNotEmpty()) { "Frontend must declare at least one extension" }
        require(defaultCommand.isNotEmpty()) { "Frontend must declare a default command" }
        require(extensions.all { it.startsWith(".") }) { "Extensions must start with a dot" }
    }

    fun handles(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        return dot >= 0 && fileName.substring(dot) in extensions
    }
}

object KotlinFrontend {
    val descriptor =
        LanguageFrontend(
            id = "kotlin",
            languageId = "kotlin",
            extensions = setOf(".kt", ".kts"),
            defaultCommand = listOf("kotlin-lsp", "--stdio"),
            grouping = GroupingMode.NAMESPACE,
            rootMarkers = listOf(".git", "settings.gradle.kts", "build.gradle.kts"),
            include = listOf("**/*.kt", "**/*.kts"),
        )
}

object FrontendRegistry {
    val all: List<LanguageFrontend> = listOf(KotlinFrontend.descriptor)

    fun byId(id: String): LanguageFrontend? = all.firstOrNull { it.id == id }

    fun forFileName(fileName: String): LanguageFrontend? = all.firstOrNull { it.handles(fileName) }
}
