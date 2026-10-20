package dev.codenb.server

import dev.codenb.api.GraphJson
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class EngineSocketServer(
    socketPath: Path,
    private val service: GraphService,
    private val shutdownGraceMillis: Long = 2000,
) : AutoCloseable {
    private val socketPath: Path = socketPath.toAbsolutePath().normalize()
    private val channel = AtomicReference<ServerSocketChannel?>()
    private val shuttingDown = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val closeFailure = AtomicReference<Throwable?>()
    private val clients = ConcurrentHashMap<SocketChannel, Thread>()
    private val handler = JsonRpcHandler(service, this.socketPath.toString(), ::requestShutdown)

    fun run() {
        try {
            SocketPaths.prepare(socketPath)
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.set(server)
            if (shuttingDown.get()) {
                runCatching { server.close() }
                return
            }
            server.bind(UnixDomainSocketAddress.of(socketPath))
            bound.set(true)
            SocketPaths.restrictSocket(socketPath)
            acceptLoop(server)
        } catch (exception: IOException) {
            if (!shuttingDown.get()) {
                throw exception
            }
        } finally {
            cleanup()
        }
    }

    fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = finished.await(timeout, unit) && closeFailure.get() == null

    fun closeFailure(): Throwable? = closeFailure.get()

    fun requestShutdown() {
        shuttingDown.set(true)
        runCatching { channel.get()?.close() }
    }

    override fun close() {
        requestShutdown()
    }

    private fun acceptLoop(server: ServerSocketChannel) {
        while (!shuttingDown.get()) {
            val client =
                try {
                    server.accept()
                } catch (exception: AsynchronousCloseException) {
                    break
                } catch (exception: ClosedChannelException) {
                    break
                } catch (exception: ClosedByInterruptException) {
                    break
                } catch (exception: IOException) {
                    if (shuttingDown.get()) {
                        break
                    }
                    throw exception
                }
            val thread = Thread({ serve(client) }, "codenb-socket-client")
            thread.isDaemon = true
            clients[client] = thread
            thread.start()
        }
    }

    private fun serve(client: SocketChannel) {
        try {
            client.use { connection ->
                val reader =
                    BufferedReader(
                        InputStreamReader(Channels.newInputStream(connection), StandardCharsets.UTF_8),
                    )
                val writer =
                    BufferedWriter(
                        OutputStreamWriter(Channels.newOutputStream(connection), StandardCharsets.UTF_8),
                    )
                while (!shuttingDown.get()) {
                    val line =
                        try {
                            reader.readLine()
                        } catch (exception: IOException) {
                            null
                        } ?: break
                    val outputs = handler.handle(line)
                    outputs.forEach { output ->
                        writer.write(GraphJson.encode(output))
                        writer.newLine()
                    }
                    writer.flush()
                    if (shuttingDown.get()) {
                        break
                    }
                }
            }
        } catch (exception: IOException) {
        } finally {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun cleanup() {
        runCatching { channel.getAndSet(null)?.close() }
        if (bound.getAndSet(false)) {
            runCatching { Files.deleteIfExists(socketPath) }
        }
        val threads = mutableListOf<Thread>()
        clients.values.forEach { thread -> threads += thread }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownGraceMillis)
        threads.forEach { thread ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) {
                thread.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
            }
        }
        clients.forEach { (client, _) -> runCatching { client.close() } }
        clients.clear()
        val failure = closeService()
        finished.countDown()
        if (failure != null) {
            throw IllegalStateException("Service close failed", failure)
        }
    }

    private fun closeService(): Throwable? {
        val failure = AtomicReference<Throwable?>()
        val closer =
            Thread(
                {
                    try {
                        service.close()
                    } catch (exception: Throwable) {
                        failure.set(exception)
                    }
                },
                "codenb-service-close",
            )
        closer.isDaemon = true
        closer.start()
        join(closer, SERVICE_CLOSE_TIMEOUT_MILLIS)
        if (closer.isAlive) {
            closer.interrupt()
            join(closer, SERVICE_CLOSE_INTERRUPT_TIMEOUT_MILLIS)
        }
        if (closer.isAlive) {
            closer.interrupt()
            join(closer, SERVICE_CLOSE_INTERRUPT_TIMEOUT_MILLIS)
        }
        if (closer.isAlive) {
            failure.compareAndSet(null, IllegalStateException("Service close did not terminate"))
        }
        val result = failure.get()
        closeFailure.set(result)
        return result
    }

    private fun join(
        thread: Thread,
        millis: Long,
    ) {
        try {
            thread.join(millis)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val SERVICE_CLOSE_TIMEOUT_MILLIS = 2500L
        const val SERVICE_CLOSE_INTERRUPT_TIMEOUT_MILLIS = 1000L
    }
}
