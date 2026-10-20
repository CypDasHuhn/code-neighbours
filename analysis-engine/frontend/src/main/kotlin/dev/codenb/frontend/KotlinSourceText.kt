package dev.codenb.frontend

internal object KotlinSourceText {
    fun mask(source: String): String = mask(source, maskStrings = true)

    fun maskComments(source: String): String = mask(source, maskStrings = false)

    fun tokens(
        text: String,
        start: Int,
        end: Int,
    ): List<Token> {
        val result = mutableListOf<Token>()
        var index = start.coerceIn(0, text.length)
        val limit = end.coerceIn(index, text.length)
        while (index < limit) {
            val character = text[index]
            if (character.isWhitespace()) {
                index++
                continue
            }
            val tokenStart = index
            when {
                character == '`' -> {
                    index = backtickEnd(text, index).coerceAtMost(limit)
                    result.add(Token(text.substring(tokenStart, index), tokenStart, index, word = true))
                }
                character.isLetterOrDigit() || character == '_' -> {
                    index++
                    while (index < limit && (text[index].isLetterOrDigit() || text[index] == '_')) {
                        index++
                    }
                    result.add(Token(text.substring(tokenStart, index), tokenStart, index, word = true))
                }
                else -> {
                    result.add(Token(character.toString(), tokenStart, index + 1, word = false))
                    index++
                }
            }
        }
        return result
    }

    fun hasPrivateModifier(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        val tokens = tokens(text, start, end)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!token.word && token.text == "@") {
                index = skipAnnotation(tokens, index)
                continue
            }
            if (token.word && token.text == "private") {
                return true
            }
            index++
        }
        return false
    }

    fun keywordOffset(
        text: String,
        start: Int,
        end: Int,
        keywords: Set<String>,
    ): Int? = tokens(text, start, end).firstOrNull { it.word && it.text in keywords }?.start

    fun declarationKeyword(
        text: String,
        start: Int,
        end: Int,
        keywords: Set<String>,
    ): Token? {
        val tokens = tokens(text, start, end)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!token.word && token.text == "@") {
                index = skipAnnotation(tokens, index)
                continue
            }
            if (token.word && token.text in keywords) {
                return token
            }
            if (token.word && token.text == CONTEXT) {
                index++
                if (index >= tokens.size || tokens[index].text != "(") {
                    return null
                }
                index = afterBalanced(tokens, index, "(", ")")
                continue
            }
            if (token.word && token.text in MODIFIERS) {
                index++
                continue
            }
            return null
        }
        return null
    }

    fun firstParentheses(
        text: String,
        start: Int,
        end: Int,
    ): IntRange? {
        val tokens = tokens(text, start, end)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!token.word && token.text == "@") {
                index = skipAnnotation(tokens, index)
                continue
            }
            if (!token.word && token.text == "(") {
                val after = afterBalanced(tokens, index, "(", ")")
                val closeIndex = after - 1
                if (closeIndex > index && tokens[closeIndex].text == ")") {
                    return tokens[index].start..tokens[closeIndex].end
                }
                return token.start..token.end
            }
            index++
        }
        return null
    }

    fun headerEnd(
        text: String,
        start: Int,
        end: Int,
        stopAtEquals: Boolean,
    ): Int {
        var parentheses = 0
        var brackets = 0
        var index = start.coerceIn(0, text.length)
        val limit = end.coerceIn(index, text.length)
        while (index < limit) {
            if (text[index] == '`') {
                index = backtickEnd(text, index)
                continue
            }
            when (text[index]) {
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> if (parentheses == 0 && brackets == 0) return index
                '=' -> if (stopAtEquals && parentheses == 0 && brackets == 0) return index
            }
            index++
        }
        return limit
    }

    fun normalize(value: String): String = WHITESPACE.replace(value, " ").trim()

    fun packageName(source: String): String {
        val masked = mask(source)
        var index = 0
        if (index < masked.length && masked[index] == '\uFEFF') {
            index++
        }
        if (index + 1 < masked.length && masked[index] == '#' && masked[index + 1] == '!') {
            while (index < masked.length && masked[index] != '\n' && masked[index] != '\r') {
                index++
            }
        }
        while (index < masked.length) {
            while (index < masked.length && masked[index].isWhitespace()) {
                index++
            }
            if (index >= masked.length) {
                return ""
            }
            if (masked[index] == '@') {
                val next = skipFileAnnotation(masked, index)
                if (next == null) {
                    return ""
                }
                index = next
                continue
            }
            if (isWordAt(masked, index, "package")) {
                return parsePackageName(masked, index + "package".length)
            }
            return ""
        }
        return ""
    }

    private fun mask(
        source: String,
        maskStrings: Boolean,
    ): String {
        val characters = source.toCharArray()
        var index = 0
        while (index < source.length) {
            val character = source[index]
            when {
                character == '`' -> index = backtickEnd(source, index)
                character == '/' && index + 1 < source.length && source[index + 1] == '/' -> {
                    val start = index
                    index += 2
                    while (index < source.length && source[index] != '\n' && source[index] != '\r') {
                        index++
                    }
                    blank(characters, start, index)
                }
                character == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                    val start = index
                    index += 2
                    var depth = 1
                    while (index < source.length && depth > 0) {
                        when {
                            source[index] == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                                depth++
                                index += 2
                            }
                            source[index] == '*' && index + 1 < source.length && source[index + 1] == '/' -> {
                                depth--
                                index += 2
                            }
                            else -> index++
                        }
                    }
                    blank(characters, start, index)
                }
                character == '"' && index + 2 < source.length && source[index + 1] == '"' && source[index + 2] == '"' -> {
                    val start = index
                    index += 3
                    while (index + 2 < source.length && !(source[index] == '"' && source[index + 1] == '"' && source[index + 2] == '"')) {
                        index++
                    }
                    index = if (index + 2 < source.length) index + 3 else source.length
                    if (maskStrings) {
                        blank(characters, start, index)
                    }
                }
                character == '"' -> {
                    val start = index
                    index++
                    while (index < source.length && source[index] != '"' && source[index] != '\n' && source[index] != '\r') {
                        index = if (source[index] == '\\' && index + 1 < source.length) index + 2 else index + 1
                    }
                    if (index < source.length && source[index] == '"') {
                        index++
                    }
                    if (maskStrings) {
                        blank(characters, start, index)
                    }
                }
                character == '\'' -> {
                    val start = index
                    index++
                    while (index < source.length && source[index] != '\'' && source[index] != '\n' && source[index] != '\r') {
                        index = if (source[index] == '\\' && index + 1 < source.length) index + 2 else index + 1
                    }
                    if (index < source.length && source[index] == '\'') {
                        index++
                    }
                    if (maskStrings) {
                        blank(characters, start, index)
                    }
                }
                else -> index++
            }
        }
        return String(characters)
    }

    private fun blank(
        characters: CharArray,
        start: Int,
        end: Int,
    ) {
        for (index in start until end) {
            if (characters[index] != '\n' && characters[index] != '\r') {
                characters[index] = ' '
            }
        }
    }

    private fun skipAnnotation(
        tokens: List<Token>,
        start: Int,
    ): Int {
        var index = start + 1
        if (index + 1 < tokens.size && tokens[index].word && tokens[index + 1].text == ":") {
            index += 2
        }
        if (index < tokens.size && tokens[index].text == "[") {
            return afterBalanced(tokens, index, "[", "]")
        }
        if (index < tokens.size && tokens[index].word) {
            index++
            while (index + 1 < tokens.size && tokens[index].text == "." && tokens[index + 1].word) {
                index += 2
            }
        }
        if (index < tokens.size && !tokens[index].word && tokens[index].text == "(") {
            index = afterBalanced(tokens, index, "(", ")")
        }
        return index
    }

    private fun afterBalanced(
        tokens: List<Token>,
        start: Int,
        open: String,
        close: String,
    ): Int {
        var depth = 0
        var index = start
        while (index < tokens.size) {
            when (tokens[index].text) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) {
                        return index + 1
                    }
                }
            }
            index++
        }
        return tokens.size
    }

    private fun skipFileAnnotation(
        text: String,
        start: Int,
    ): Int? {
        var index = start + 1
        if (!isWordAt(text, index, "file")) {
            return null
        }
        index += "file".length
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
        if (index >= text.length || text[index] != ':') {
            return null
        }
        index++
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
        if (index < text.length && text[index] == '[') {
            val end = matchingBracketGroup(text, index) ?: return null
            return end + 1
        }
        if (index < text.length && text[index] == '`') {
            index = backtickEnd(text, index)
        } else {
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '.')) {
                index++
            }
        }
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
        if (index < text.length && text[index] == '(') {
            val end = matchingParenthesis(text, index) ?: return null
            index = end + 1
        }
        return index
    }

    private fun matchingBracketGroup(
        text: String,
        start: Int,
    ): Int? {
        var parentheses = 0
        var brackets = 0
        var index = start
        while (index < text.length) {
            if (text[index] == '`') {
                index = backtickEnd(text, index)
                continue
            }
            when (text[index]) {
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                '[' -> if (parentheses == 0) brackets++
                ']' -> {
                    if (parentheses == 0) {
                        brackets--
                        if (brackets == 0) {
                            return index
                        }
                    }
                }
            }
            index++
        }
        return null
    }

    private fun matchingParenthesis(
        text: String,
        start: Int,
    ): Int? {
        var depth = 0
        var index = start
        while (index < text.length) {
            if (text[index] == '`') {
                index = backtickEnd(text, index)
                continue
            }
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
            }
            index++
        }
        return null
    }

    private fun parsePackageName(
        text: String,
        start: Int,
    ): String {
        var index = start
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
        val segments = mutableListOf<String>()
        while (index < text.length) {
            val segment =
                when {
                    text[index] == '`' -> {
                        val end = text.indexOf('`', index + 1)
                        if (end < 0) {
                            return segments.joinToString(".")
                        }
                        text.substring(index + 1, end).also { index = end + 1 }
                    }
                    text[index].isLetter() || text[index] == '_' -> {
                        val segmentStart = index
                        index++
                        while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) {
                            index++
                        }
                        text.substring(segmentStart, index)
                    }
                    else -> break
                }
            segments.add(segment)
            while (index < text.length && (text[index] == ' ' || text[index] == '\t')) {
                index++
            }
            if (index >= text.length || text[index] != '.') {
                break
            }
            index++
            while (index < text.length && (text[index] == ' ' || text[index] == '\t')) {
                index++
            }
        }
        return segments.joinToString(".")
    }

    fun startsWithInitBlock(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        val tokens = tokens(text, start, end)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!token.word && token.text == "@") {
                index = skipAnnotation(tokens, index)
                continue
            }
            if (token.word && token.text in MODIFIERS) {
                index++
                continue
            }
            return token.word && token.text == "init" && index + 1 < tokens.size && tokens[index + 1].text == "{"
        }
        return false
    }

    private fun backtickEnd(
        text: String,
        start: Int,
    ): Int {
        val end = text.indexOf('`', start + 1)
        return if (end < 0) text.length else end + 1
    }

    private fun isWordAt(
        text: String,
        start: Int,
        word: String,
    ): Boolean {
        if (!text.startsWith(word, start)) {
            return false
        }
        val before = start - 1
        val after = start + word.length
        val beforeValid = before < 0 || !(text[before].isLetterOrDigit() || text[before] == '_')
        val afterValid = after >= text.length || !(text[after].isLetterOrDigit() || text[after] == '_')
        return beforeValid && afterValid
    }

    private const val CONTEXT = "context"

    private val MODIFIERS =
        setOf(
            "public",
            "private",
            "protected",
            "internal",
            "final",
            "open",
            "abstract",
            "sealed",
            "data",
            "enum",
            "annotation",
            "companion",
            "inner",
            "value",
            "inline",
            "noinline",
            "crossinline",
            "vararg",
            "lateinit",
            "override",
            "suspend",
            "tailrec",
            "operator",
            "infix",
            "external",
            "const",
            "actual",
            "expect",
            "reified",
            "fun",
        )

    private val WHITESPACE = Regex("""\s+""")

    data class Token(
        val text: String,
        val start: Int,
        val end: Int,
        val word: Boolean,
    )
}
