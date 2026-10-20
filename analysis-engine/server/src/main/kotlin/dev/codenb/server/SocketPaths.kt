package dev.codenb.server

import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

enum class SocketProbe {
    LIVE,
    STALE,
    INACCESSIBLE,
}

object SocketPaths {
    private val DIRECTORY_PERMISSIONS =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )

    private val SOCKET_PERMISSIONS =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )

    fun derive(
        root: Path,
        lspId: String,
        runtimeDirectory: String? = System.getenv("XDG_RUNTIME_DIR"),
        uid: String = currentUid(),
    ): Path {
        require(lspId.isNotBlank()) { "LSP id must not be blank" }
        val base =
            runtimeDirectory
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?: Path.of("/tmp")
        val digest = sha256("${root.toAbsolutePath().normalize()}\u0000$lspId").take(16)
        val path =
            base
                .resolve("codenb-$uid")
                .resolve("$digest.sock")
                .toAbsolutePath()
                .normalize()
        validatePath(path)
        return path
    }

    fun currentUid(): String = currentUidValue()?.toString() ?: usernameUid()

    fun currentUidValue(): Int? =
        uidOf(Path.of(System.getProperty("user.home").orEmpty().ifBlank { "/" }))
            ?: uidOf(Path.of("/proc/self"))

    fun prepare(path: Path) {
        val socket = path.toAbsolutePath().normalize()
        validatePath(socket)
        val parent = socket.parent ?: throw IOException("Socket path has no parent directory: $path")
        createParent(parent)
        if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        if (Files.isSymbolicLink(socket)) {
            throw IOException("Refusing to remove symlink at socket path: $socket")
        }
        if (!isSocket(socket)) {
            throw IOException("Refusing to remove non-socket path: $socket")
        }
        when (probe(socket)) {
            SocketProbe.LIVE -> throw IOException("A daemon is already listening on $socket")
            SocketProbe.STALE -> Files.delete(socket)
            SocketProbe.INACCESSIBLE -> throw IOException("Cannot verify whether $socket is a live daemon")
        }
    }

    fun restrictSocket(path: Path) {
        forcePermissions(path.toAbsolutePath().normalize(), SOCKET_PERMISSIONS)
    }

    fun probe(path: Path): SocketProbe =
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(path.toAbsolutePath().normalize()))
            }
            SocketProbe.LIVE
        } catch (exception: ConnectException) {
            SocketProbe.STALE
        } catch (exception: NoSuchFileException) {
            SocketProbe.STALE
        } catch (exception: AccessDeniedException) {
            SocketProbe.INACCESSIBLE
        } catch (exception: IOException) {
            SocketProbe.INACCESSIBLE
        } catch (exception: UnsupportedOperationException) {
            SocketProbe.INACCESSIBLE
        }

    fun isLive(path: Path): Boolean = probe(path) == SocketProbe.LIVE

    private fun createParent(parent: Path) {
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(parent)) {
                throw IOException("Socket parent must not be a symlink: $parent")
            }
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Socket parent is not a directory: $parent")
            }
            val owner = uidOf(parent)
            val current = currentUidValue()
            if (owner != null && current != null && owner != current) {
                throw IOException("Socket parent is not owned by the current user: $parent")
            }
            forcePermissions(parent, DIRECTORY_PERMISSIONS)
            return
        }
        try {
            Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
        } catch (exception: UnsupportedOperationException) {
            Files.createDirectories(parent)
        }
        forcePermissions(parent, DIRECTORY_PERMISSIONS)
    }

    private fun forcePermissions(
        path: Path,
        permissions: Set<PosixFilePermission>,
    ) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (exception: UnsupportedOperationException) {
        }
    }

    private fun isSocket(path: Path): Boolean {
        val mode =
            runCatching {
                Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as? Int
            }.getOrNull() ?: return false
        return (mode and S_IFMT) == S_IFSOCK
    }

    private fun uidOf(path: Path): Int? =
        runCatching {
            Files.getAttribute(path, "unix:uid") as? Int
        }.getOrNull()

    private fun usernameUid(): String {
        val name = System.getProperty("user.name").orEmpty()
        return if (name.isBlank()) "unknown" else sha256(name).take(8)
    }

    private fun validatePath(path: Path) {
        val bytes = path.toString().toByteArray(StandardCharsets.UTF_8).size
        if (bytes > MAX_SOCKET_PATH_BYTES) {
            throw ConfigException("Unix socket path is too long ($bytes bytes, maximum $MAX_SOCKET_PATH_BYTES): $path")
        }
    }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private const val MAX_SOCKET_PATH_BYTES = 107
    private const val S_IFMT = 0xF000
    private const val S_IFSOCK = 0xC000
}
