package dev.codenb.server

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.EngineErrorCode
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.GraphInfo
import dev.codenb.api.GroupingKind
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.api.PROTOCOL_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class FakeGraphService(
    var infoResult: GraphInfo = FakeGraph.info,
    var openResult: Expansion = FakeGraph.expansion,
    var nodeResult: Expansion = FakeGraph.expansion,
    var dotResult: String = FakeGraph.dot,
    var serviceStatus: GraphStatus = GraphStatus(true, FakeGraph.revision),
    var expandHandler: (GraphId, NodeId, String) -> Expansion = { graphId, nodeId, revision ->
        FakeGraph.expansion.copy(
            graphId = graphId,
            revision = revision,
            node =
                FakeGraph.expansion.node.copy(
                    id = nodeId,
                    label = nodeId.value,
                    qualifiedName = "app.${nodeId.value}",
                    signatureFingerprint = "fun ${nodeId.value}()",
                ),
        )
    },
) : GraphService {
    var failure: EngineException? = null
    var closed: Boolean = false
    var openedWith: Seed? = null

    override fun info(): GraphInfo {
        fail()
        return infoResult
    }

    override fun status(): GraphStatus {
        fail()
        return serviceStatus
    }

    override fun expandableNodeKinds(): List<NodeKind> = listOf(NodeKind.ENTITY)

    override fun open(seed: Seed): Expansion {
        fail()
        openedWith = seed
        serviceStatus = GraphStatus(true, openResult.revision)
        return openResult
    }

    override fun expand(
        graphId: GraphId,
        nodeId: NodeId,
        revision: String,
    ): Expansion {
        fail()
        requireOpen()
        return expandHandler(graphId, nodeId, revision)
    }

    override fun node(
        graphId: GraphId,
        nodeId: NodeId,
    ): Expansion {
        fail()
        requireOpen()
        return nodeResult
    }

    override fun dot(graphId: GraphId): String {
        fail()
        requireOpen()
        return dotResult
    }

    override fun close() {
        closed = true
    }

    private fun fail() {
        failure?.let { throw it }
    }

    private fun requireOpen() {
        if (!serviceStatus.open) {
            throw engineException(EngineErrorCode.INVALID_GRAPH, "Graph is not open")
        }
    }
}

class BlockingCloseGraphService(
    private val delegate: FakeGraphService,
    private val sleepMillis: Long,
) : GraphService by delegate {
    val closed: Boolean
        get() = delegate.closed

    override fun close() {
        try {
            Thread.sleep(sleepMillis)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            delegate.close()
        }
    }
}

class LatchCloseGraphService(
    private val delegate: FakeGraphService,
    private val latch: CountDownLatch,
) : GraphService by delegate {
    val closed: Boolean
        get() = delegate.closed

    override fun close() {
        while (true) {
            try {
                latch.await()
                break
            } catch (exception: InterruptedException) {
                Thread.interrupted()
            }
        }
        delegate.close()
    }
}

class ThrowingCloseGraphService(
    private val delegate: FakeGraphService,
) : GraphService by delegate {
    override fun close() = throw IllegalStateException("close failed")
}

object FakeGraph {
    val graphId = GraphId("graph/test")
    val revision: String = "rev-1"
    val group = Node(NodeId("group/app"), NodeKind.GROUP, "app")
    val file =
        Node(
            id = NodeId("file/A.kt"),
            kind = NodeKind.FILE,
            label = "A.kt",
            fileUri = "file:///tmp/A.kt",
            parent = group.id,
        )
    val seed =
        Node(
            id = NodeId("entity/seed"),
            kind = NodeKind.ENTITY,
            label = "seed",
            qualifiedName = "app.seed",
            fileUri = "file:///tmp/A.kt",
            signatureFingerprint = "fun seed()",
            parent = file.id,
        )
    val target =
        Node(
            id = NodeId("entity/target"),
            kind = NodeKind.ENTITY,
            label = "target",
            qualifiedName = "app.target",
            fileUri = "file:///tmp/A.kt",
            signatureFingerprint = "fun target()",
            parent = file.id,
        )
    val edges: List<Edge> =
        listOf(
            Edge.of(EdgeKind.CONTAINS, seed.id, file.id, 1),
            Edge.of(EdgeKind.CONTAINS, target.id, file.id, 1),
            Edge.of(EdgeKind.CONTAINS, file.id, group.id, 1),
            Edge.of(
                EdgeKind.REFERENCES,
                seed.id,
                target.id,
                2,
                listOf(EdgeOrigin(seed.id, target.id, 2)),
            ),
        )
    val expansion = Expansion(graphId, revision, seed, listOf(file, group, target), edges)
    val info =
        GraphInfo(
            graphId = graphId,
            root = "/tmp/workspace",
            lspId = "kotlin",
            groupingKind = GroupingKind.NAMESPACE,
            nodeKinds = NodeKind.entries,
            edgeKinds = EdgeKind.entries,
            protocolVersion = PROTOCOL_VERSION,
        )
    val dot: String =
        """
        graph "code-neighbours" {
          "group/app" [label="app", shape="folder"];
          "file/A.kt" -- "group/app" [kind="CONTAINS"];
        }
        """.trimIndent() + "\n"
}

class TestServer(
    val socketPath: Path,
    val directory: Path,
    val server: EngineSocketServer,
    private val thread: Thread,
) : AutoCloseable {
    fun connect(): TestClient = TestClient(socketPath)

    fun awaitStopped(): Boolean = thread.join(5000).let { !thread.isAlive }

    override fun close() {
        server.close()
        thread.join(5000)
        deleteTree(directory)
    }
}

class TestClient(
    path: Path,
) : AutoCloseable {
    private val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    private val reader: BufferedReader
    private val writer: BufferedWriter
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "codenb-test-reader").apply { isDaemon = true }
        }

    init {
        channel.connect(UnixDomainSocketAddress.of(path))
        reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8))
    }

    fun send(text: String) {
        writer.write(text)
        writer.flush()
    }

    fun sendLine(line: String) {
        send(line + "\n")
    }

    fun readLine(): String {
        val future = executor.submit(Callable<String?> { reader.readLine() })
        val line =
            try {
                future.get(5, TimeUnit.SECONDS)
            } catch (exception: TimeoutException) {
                runCatching { channel.close() }
                throw exception
            }
        return line ?: error("Connection closed before a response line arrived")
    }

    fun readJson(): JsonObject = Json.parseToJsonElement(readLine()) as JsonObject

    fun request(line: String): JsonObject {
        sendLine(line)
        return readJson()
    }

    override fun close() {
        executor.shutdownNow()
        runCatching { channel.close() }
    }
}

fun withServer(
    service: GraphService = FakeGraphService(),
    socketPathFactory: (Path) -> Path = { directory -> directory.resolve("engine.sock") },
    block: (TestServer) -> Unit,
) {
    val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
    val socketPath = socketPathFactory(directory)
    val server = EngineSocketServer(socketPath, service)
    val thread = Thread({ server.run() }, "codenb-test-server")
    thread.isDaemon = true
    thread.start()
    TestServer(socketPath, directory, server, thread).use { testServer ->
        awaitSocket(socketPath)
        block(testServer)
    }
}

fun awaitSocket(path: Path) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        if (SocketPaths.isLive(path)) {
            return
        }
        Thread.sleep(10)
    }
    error("Socket was not created: $path")
}

fun requestLine(
    id: JsonElement,
    method: String,
    params: JsonElement? = null,
): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) {
            put("params", params)
        }
    }.toString()

fun deleteTree(path: Path) {
    if (!Files.exists(path)) {
        return
    }
    runCatching {
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { entry ->
            runCatching { Files.deleteIfExists(entry) }
        }
    }
}
