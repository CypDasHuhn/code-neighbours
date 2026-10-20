package dev.codenb.api

import kotlinx.serialization.Serializable

@Serializable
data class SourceSpan(
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
) {
    init {
        require(startLine >= 0 && startCharacter >= 0 && endLine >= 0 && endCharacter >= 0) {
            "Source span coordinates must be non-negative"
        }
        require(endLine > startLine || (endLine == startLine && endCharacter >= startCharacter)) {
            "Source span end must not precede start"
        }
    }
}
