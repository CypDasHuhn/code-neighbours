package dev.codenb.core

data class FileLocation(
    val fileUri: String,
    val relativePath: String,
    val namespace: String = "",
) {
    init {
        require(fileUri.isNotBlank()) { "File URI must not be blank" }
        require(relativePath.isNotBlank()) { "Relative path must not be blank" }
    }

    companion object {
        fun normalizePath(path: String): String {
            val replaced = path.replace('\\', '/')
            require(!replaced.startsWith("/") && !WINDOWS_ABSOLUTE.matches(replaced)) {
                "Relative path must not be absolute: $path"
            }
            val segments = mutableListOf<String>()
            replaced.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> {
                        require(segments.isNotEmpty()) { "Relative path escapes its root: $path" }
                        segments.removeAt(segments.lastIndex)
                    }
                    else -> segments.add(segment)
                }
            }
            require(segments.isNotEmpty()) { "Relative path must not be empty: $path" }
            return segments.joinToString("/")
        }

        private val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:/.*""")
    }
}
