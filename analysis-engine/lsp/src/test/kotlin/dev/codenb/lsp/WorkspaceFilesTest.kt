package dev.codenb.lsp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceFilesTest {
    @Test
    fun usesGitIgnoreRulesInsideARepository() {
        val root = Files.createTempDirectory("workspace-git")
        try {
            runGit(root, "init", "-q")
            writeFile(root, ".gitignore", "ignored.kt\nignored-untracked.kt\nbuild/\n")
            writeFile(root, "src/main/A.kt")
            writeFile(root, "src/main/B.kt")
            writeFile(root, "src/main/ignored.kt")
            writeFile(root, "src/main/notes.txt")
            writeFile(root, "build/Generated.kt")
            runGit(root, "add", "-A")
            writeFile(root, "src/main/UntrackedNested.kt")
            writeFile(root, "untracked.kt")
            writeFile(root, "ignored-untracked.kt")

            val discovered =
                WorkspaceFiles.discover(
                    root,
                    include = listOf("**/*.kt"),
                    exclude = listOf("**/B.kt"),
                )

            assertEquals(
                listOf("src/main/A.kt", "src/main/UntrackedNested.kt", "untracked.kt"),
                discovered.map { it.relativePath },
            )
            assertTrue(
                discovered.all {
                    it.absolutePath.isAbsolute && it.absolutePath.startsWith(root.toAbsolutePath().normalize())
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun discoversRelativeToASubdirectoryOfAGitRepository() {
        val repository = Files.createTempDirectory("workspace-git-subdirectory")
        try {
            runGit(repository, "init", "-q")
            writeFile(repository, "README.txt")
            writeFile(repository, "other/Other.kt")
            writeFile(repository, "project/src/A.kt")
            writeFile(repository, "project/nested/B.kt")
            runGit(repository, "add", "-A")

            val root = repository.resolve("project")
            val discovered = WorkspaceFiles.discover(root, include = listOf("**/*.kt"))

            assertEquals(
                listOf("nested/B.kt", "src/A.kt"),
                discovered.map { it.relativePath },
            )
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test
    fun appliesIncludeAndExcludeGlobsOutsideGitWithoutFollowingSymlinks() {
        val root = Files.createTempDirectory("workspace-tree")
        val outside = Files.createTempDirectory("workspace-outside")
        try {
            writeFile(root, "main.kt")
            writeFile(root, "src/feature.kt")
            writeFile(root, "src/skip.kt")
            writeFile(root, "src/nested/deep.kts")
            writeFile(root, "build/generated.kt")
            writeFile(root, "notes.txt")
            writeFile(outside, "secret.kt")
            Files.createSymbolicLink(root.resolve("link.kt"), Path.of("main.kt"))
            Files.createSymbolicLink(root.resolve("linked"), outside)

            val discovered =
                WorkspaceFiles.discover(
                    root,
                    include = listOf("**/*.{kt,kts}"),
                    exclude = listOf("**/skip.kt"),
                )

            assertEquals(
                listOf("main.kt", "src/feature.kt", "src/nested/deep.kts"),
                discovered.map { it.relativePath },
            )
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun followsSymlinksOutsideGitWhenRequested() {
        val root = Files.createTempDirectory("workspace-follow")
        val outside = Files.createTempDirectory("workspace-follow-outside")
        try {
            writeFile(root, "main.kt")
            writeFile(outside, "secret.kt")
            Files.createSymbolicLink(root.resolve("link.kt"), Path.of("main.kt"))
            Files.createSymbolicLink(root.resolve("linked"), outside)

            val discovered =
                WorkspaceFiles.discover(
                    root,
                    include = listOf("**/*.kt"),
                    followSymlinks = true,
                )

            assertEquals(
                listOf("link.kt", "linked/secret.kt", "main.kt"),
                discovered.map { it.relativePath },
            )
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsFollowSymlinksForGitWorkspaces() {
        val root = Files.createTempDirectory("workspace-git-symlink")
        try {
            runGit(root, "init", "-q")
            writeFile(root, "Main.kt")
            runGit(root, "add", "-A")

            assertFailsWith<WorkspaceSymlinkException> {
                WorkspaceFiles.discover(root, include = listOf("**/*.kt"), followSymlinks = true)
            }
            assertEquals(
                listOf("Main.kt"),
                WorkspaceFiles.discover(root, include = listOf("**/*.kt")).map { it.relativePath },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun appliesDirectoryExclusionsInGitAndNonGitAndIncludeCanOverride() {
        val gitRoot = Files.createTempDirectory("workspace-git-build")
        val treeRoot = Files.createTempDirectory("workspace-tree-build")
        try {
            writeFile(gitRoot, "build/Generated.kt")
            writeFile(gitRoot, "src/A.kt")
            runGit(gitRoot, "init", "-q")
            runGit(gitRoot, "add", "-f", "build/Generated.kt", "src/A.kt")

            writeFile(treeRoot, "build/Generated.kt")
            writeFile(treeRoot, "src/A.kt")

            listOf(gitRoot, treeRoot).forEach { root ->
                assertEquals(
                    listOf("src/A.kt"),
                    WorkspaceFiles.discover(root, include = listOf("**/*.kt")).map { it.relativePath },
                )
                assertEquals(
                    listOf("build/Generated.kt", "src/A.kt"),
                    WorkspaceFiles.discover(root, include = listOf("**/*.kt", "build/**/*.kt")).map { it.relativePath },
                )
            }
        } finally {
            gitRoot.toFile().deleteRecursively()
            treeRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun boundsGitDiscoveryByTimeoutAndDestroysStalledCommand() {
        val root = Files.createTempDirectory("workspace-git-timeout")
        try {
            val script = root.resolve("fake-git")
            Files.writeString(
                script,
                """
                #!/bin/sh
                if [ "${'$'}3" = "rev-parse" ]; then
                  echo true
                  exit 0
                fi
                echo stalled
                exec sleep 5
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
            val runner = ProcessGitCommandRunner(script.toString())
            val started = System.nanoTime()

            assertFailsWith<WorkspaceTimeoutException> {
                WorkspaceFiles.discover(
                    root,
                    include = listOf("**/*.kt"),
                    gitRunner = runner,
                    gitTimeout = Duration.ofMillis(250),
                )
            }

            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(3), "git timeout took $elapsed")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failsWhenProcessExitsButDrainThreadsRemainBlocked() {
        val root = Files.createTempDirectory("workspace-git-drain")
        try {
            val script = root.resolve("fake-git")
            Files.writeString(
                script,
                """
                #!/bin/sh
                if [ "${'$'}3" = "rev-parse" ]; then
                  echo true
                  exit 0
                fi
                ( sleep 2 ) &
                echo background-child-started
                exit 0
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
            val runner = ProcessGitCommandRunner(script.toString())
            val started = System.nanoTime()

            assertFailsWith<WorkspaceException> {
                WorkspaceFiles.discover(
                    root,
                    include = listOf("**/*.kt"),
                    gitRunner = runner,
                    gitTimeout = Duration.ofSeconds(5),
                )
            }

            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(3), "drain failure took $elapsed")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failsWhenRevParseDrainRemainsBlocked() {
        val root = Files.createTempDirectory("workspace-git-detect-drain")
        try {
            val script = root.resolve("fake-git")
            Files.writeString(
                script,
                """
                #!/bin/sh
                ( sleep 2 ) &
                sleep 0.2
                echo true
                exit 0
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
            val runner = ProcessGitCommandRunner(script.toString())
            val started = System.nanoTime()

            assertFailsWith<WorkspaceDrainException> {
                WorkspaceFiles.discover(
                    root,
                    include = listOf("**/*.kt"),
                    gitRunner = runner,
                    gitTimeout = Duration.ofSeconds(5),
                )
            }

            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(3), "rev-parse drain failure took $elapsed")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeFile(
        root: Path,
        relative: String,
        content: String = "content",
    ) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun runGit(
        root: Path,
        vararg arguments: String,
    ) {
        val process =
            ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        assertTrue(finished, "git command timed out")
        assertEquals(0, process.exitValue(), output)
    }
}
