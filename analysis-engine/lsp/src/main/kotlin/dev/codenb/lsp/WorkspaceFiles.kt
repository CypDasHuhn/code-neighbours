package dev.codenb.lsp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class WorkspaceFile(
    val relativePath: String,
    val absolutePath: Path,
)

open class WorkspaceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class WorkspaceTimeoutException(
    val command: String,
    val timeout: Duration,
) : WorkspaceException("Command timed out after $timeout: $command")

class WorkspaceStartException(
    message: String,
    cause: Throwable? = null,
) : WorkspaceException(message, cause)

class WorkspaceDrainException(
    message: String,
    cause: Throwable? = null,
) : WorkspaceException(message, cause)

class WorkspaceSymlinkException : WorkspaceException("followSymlinks is not supported for git workspaces")

data class GitCommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
)

fun interface GitCommandRunner {
    fun run(
        workingDirectory: Path,
        arguments: List<String>,
        timeout: Duration,
    ): GitCommandResult
}

class ProcessGitCommandRunner(
    private val executable: String = "git",
    private val processStarter: (List<String>, Path) -> Process = { command, directory ->
        ProcessBuilder(command).directory(directory.toFile()).start()
    },
) : GitCommandRunner {
    override fun run(
        workingDirectory: Path,
        arguments: List<String>,
        timeout: Duration,
    ): GitCommandResult {
        val command = listOf(executable) + arguments
        val process =
            try {
                processStarter(command, workingDirectory)
            } catch (exception: IOException) {
                throw WorkspaceStartException("Failed to start ${command.joinToString(" ")}", exception)
            }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val descendants = ConcurrentHashMap.newKeySet<ProcessHandle>()
        val stdoutThread = drain(process.inputStream, stdout)
        val stderrThread = drain(process.errorStream, stderr)
        val finished =
            try {
                waitForProcess(process, timeout, descendants)
            } catch (exception: InterruptedException) {
                destroyTree(process, descendants)
                Thread.currentThread().interrupt()
                throw WorkspaceException("Interrupted while running ${command.joinToString(" ")}", exception)
            }
        if (!finished) {
            destroyTree(process, descendants)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            stdoutThread.join(200)
            stderrThread.join(200)
            throw WorkspaceTimeoutException(command.joinToString(" "), timeout)
        }
        val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        joinUntil(stdoutThread, drainDeadline)
        joinUntil(stderrThread, drainDeadline)
        if (stdoutThread.isAlive || stderrThread.isAlive) {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            destroyTree(process, descendants)
            stdoutThread.join(200)
            stderrThread.join(200)
            throw WorkspaceDrainException("Failed to drain output for ${command.joinToString(" ")}")
        }
        return GitCommandResult(process.exitValue(), stdout.toByteArray(), stderr.toByteArray())
    }

    private fun waitForProcess(
        process: Process,
        timeout: Duration,
        descendants: MutableSet<ProcessHandle>,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            runCatching { process.descendants().forEach { descendants += it } }
            if (!process.isAlive) {
                return true
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                return false
            }
            val sleepMillis = minOf(remaining / 1_000_000L, 25L).coerceAtLeast(1L)
            Thread.sleep(sleepMillis)
        }
    }

    private fun destroyTree(
        process: Process,
        descendants: Set<ProcessHandle>,
    ) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        descendants.forEach { handle -> runCatching { handle.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
    }

    private fun joinUntil(
        thread: Thread,
        deadlineNanos: Long,
    ) {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining > 0L) {
            thread.join(maxOf(1L, Duration.ofNanos(remaining).toMillis()))
        }
    }

    private fun drain(
        input: InputStream,
        output: ByteArrayOutputStream,
    ): Thread =
        Thread(
            {
                runCatching { input.use { it.copyTo(output) } }
            },
            "workspace-git-drain",
        ).apply {
            isDaemon = true
            start()
        }
}

object WorkspaceFiles {
    private val EXCLUDED_DIRECTORIES =
        setOf(".git", ".gradle", ".kotlin", "build", "out", "node_modules", "target", "dist")

    fun discover(
        root: Path,
        include: List<String> = listOf("**/*"),
        exclude: List<String> = emptyList(),
        followSymlinks: Boolean = false,
        gitRunner: GitCommandRunner = ProcessGitCommandRunner(),
        gitTimeout: Duration = Duration.ofSeconds(10),
    ): List<WorkspaceFile> {
        val normalizedRoot = root.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedRoot)) { "Workspace root is not a directory: $normalizedRoot" }
        val includes = include.map(::GlobPattern)
        val excludes = exclude.map(::GlobPattern)
        val explicitDirectories = explicitDefaultDirectories(include)
        val isGit = isGitRepository(normalizedRoot, gitRunner, gitTimeout)
        if (isGit && followSymlinks) {
            throw WorkspaceSymlinkException()
        }
        val candidates =
            if (isGit) {
                gitPaths(normalizedRoot, gitRunner, gitTimeout)
            } else {
                walkPaths(normalizedRoot, followSymlinks, explicitDirectories)
            }
        return candidates
            .asSequence()
            .map { it.replace('\\', '/').removePrefix("./") }
            .filter { relative ->
                includes.any { it.matches(relative) } &&
                    excludes.none { it.matches(relative) } &&
                    !isInDefaultExcludedDirectory(relative, explicitDirectories)
            }.distinct()
            .mapNotNull { relative -> toWorkspaceFile(normalizedRoot, relative, followSymlinks) }
            .sortedBy { it.relativePath }
            .toList()
    }

    private fun explicitDefaultDirectories(patterns: List<String>): Set<String> =
        patterns
            .asSequence()
            .flatMap { it.replace('\\', '/').split('/').asSequence() }
            .filter { it in EXCLUDED_DIRECTORIES }
            .toSet()

    private fun isInDefaultExcludedDirectory(
        relative: String,
        explicitDirectories: Set<String>,
    ): Boolean =
        relative
            .split('/')
            .dropLast(1)
            .any { it in EXCLUDED_DIRECTORIES && it !in explicitDirectories }

    private fun toWorkspaceFile(
        root: Path,
        relative: String,
        followSymlinks: Boolean,
    ): WorkspaceFile? {
        val candidate = root.resolve(relative).normalize()
        if (!candidate.startsWith(root)) {
            return null
        }
        val regular =
            if (followSymlinks) {
                Files.isRegularFile(candidate)
            } else {
                Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
            }
        if (!regular) {
            return null
        }
        return WorkspaceFile(relative, candidate)
    }

    private fun isGitRepository(
        root: Path,
        gitRunner: GitCommandRunner,
        timeout: Duration,
    ): Boolean {
        val result =
            try {
                gitRunner.run(root, listOf("-C", root.toString(), "rev-parse", "--is-inside-work-tree"), timeout)
            } catch (exception: WorkspaceStartException) {
                return false
            }
        return result.exitCode == 0 && result.stdout.toString(Charsets.UTF_8).trim() == "true"
    }

    private fun gitPaths(
        root: Path,
        gitRunner: GitCommandRunner,
        timeout: Duration,
    ): List<String> {
        val result =
            gitRunner.run(
                root,
                listOf("-C", root.toString(), "ls-files", "--cached", "--others", "--exclude-standard", "-z"),
                timeout,
            )
        if (result.exitCode != 0) {
            throw WorkspaceException(
                "git ls-files failed with exit code ${result.exitCode}: ${result.stderr.toString(Charsets.UTF_8)}",
            )
        }
        return result.stdout
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .filter { it.isNotEmpty() }
    }

    private fun walkPaths(
        root: Path,
        followSymlinks: Boolean,
        explicitDirectories: Set<String>,
    ): List<String> {
        val result = mutableListOf<String>()
        val options = if (followSymlinks) setOf(FileVisitOption.FOLLOW_LINKS) else emptySet()
        Files.walkFileTree(
            root,
            options,
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root) {
                        if (!followSymlinks && Files.isSymbolicLink(dir)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        val name = dir.fileName.toString()
                        if (name in EXCLUDED_DIRECTORIES && name !in explicitDirectories) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if ((followSymlinks || !attrs.isSymbolicLink) && attrs.isRegularFile) {
                        result += root.relativize(file).toString()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exception: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return result
    }
}

internal class GlobPattern(
    pattern: String,
) {
    private val regex = Regex(globToRegex(pattern.replace('\\', '/'), anchored = true))

    fun matches(value: String): Boolean = regex.matches(value)
}

private fun globToRegex(
    glob: String,
    anchored: Boolean,
): String {
    val regex = StringBuilder()
    if (anchored) {
        regex.append('^')
    }
    var index = 0
    while (index < glob.length) {
        val character = glob[index]
        when (character) {
            '*' -> {
                if (index + 1 < glob.length && glob[index + 1] == '*') {
                    index++
                    if (index + 1 < glob.length && glob[index + 1] == '/') {
                        index++
                        regex.append("(?:.*/)?")
                    } else {
                        regex.append(".*")
                    }
                } else {
                    regex.append("[^/]*")
                }
            }
            '?' -> regex.append("[^/]")
            '[' -> {
                val close = glob.indexOf(']', index + 1)
                if (close == -1) {
                    regex.append("\\[")
                } else {
                    var content = glob.substring(index + 1, close)
                    if (content.startsWith("!")) {
                        content = "^" + content.substring(1)
                    }
                    regex.append('[').append(content).append(']')
                    index = close
                }
            }
            '{' -> {
                val close = glob.indexOf('}', index + 1)
                if (close == -1) {
                    regex.append("\\{")
                } else {
                    val alternatives = glob.substring(index + 1, close).split(',')
                    regex.append("(?:")
                    alternatives.forEachIndexed { alternativeIndex, alternative ->
                        if (alternativeIndex > 0) {
                            regex.append('|')
                        }
                        regex.append(globToRegex(alternative, anchored = false))
                    }
                    regex.append(')')
                    index = close
                }
            }
            else -> regex.append(Regex.escape(character.toString()))
        }
        index++
    }
    if (anchored) {
        regex.append('$')
    }
    return regex.toString()
}
