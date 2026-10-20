package dev.codenb.server

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

fun interface ContentRevisionProvider {
    fun revision(
        root: Path,
        paths: Set<Path>,
    ): String
}

fun interface CommitGate {
    fun beforeCommit()
}

object ContentRevisions {
    fun hash(
        root: Path,
        paths: Set<Path>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths
            .map { path -> root.relativize(path).toString().replace('\\', '/') to path }
            .sortedBy { it.first }
            .forEach { (relative, path) ->
                digest.update(relative.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                digest.update(Files.readAllBytes(path))
                digest.update('\n'.code.toByte())
            }
        return digest.digest().joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
    }
}
