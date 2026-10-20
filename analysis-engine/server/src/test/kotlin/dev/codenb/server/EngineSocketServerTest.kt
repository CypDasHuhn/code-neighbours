package dev.codenb.server

import dev.codenb.api.EngineErrorCode
import dev.codenb.api.Expansion
import dev.codenb.api.GraphJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineSocketServerTest {
    @Test
    fun initializeReturnsGraphInfoAndSocketPath() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                val response = client.request(requestLine(JsonPrimitive(1), "initialize"))
                assertEquals("2.0", response["jsonrpc"]!!.jsonPrimitive.content)
                val result = response.resultObject()
                val graph = result["graph"]!!.jsonObject
                assertEquals(1, graph["protocolVersion"]!!.jsonPrimitive.int)
                assertEquals("kotlin", graph["lspId"]!!.jsonPrimitive.content)
                assertEquals(FakeGraph.graphId.value, graph["graphId"]!!.jsonPrimitive.content)
                assertEquals(server.socketPath.toString(), result["socketPath"]!!.jsonPrimitive.content)
                assertTrue(result["open"]!!.jsonPrimitive.boolean)
                assertEquals(FakeGraph.revision, result["revision"]!!.jsonPrimitive.content)
                val nodeKinds = (graph["nodeKinds"] as JsonArray).map { it.jsonPrimitive.content }
                assertTrue(nodeKinds.containsAll(listOf("ENTITY", "FILE", "GROUP")))
                val expandable = (result["expandableNodeKinds"] as JsonArray).map { it.jsonPrimitive.content }
                assertEquals(listOf("ENTITY"), expandable)
                val edgeKinds = (graph["edgeKinds"] as JsonArray).map { it.jsonPrimitive.content }
                assertTrue(edgeKinds.containsAll(listOf("CONTAINS", "REFERENCES", "FILE_REFERENCES", "GROUP_REFERENCES")))
            }
        }
    }

    @Test
    fun framesMultipleLinesInOneWrite() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                val first = requestLine(JsonPrimitive(1), "initialize")
                val second =
                    requestLine(
                        JsonPrimitive(2),
                        "graph/dot",
                        buildJsonObject { put("graphId", FakeGraph.graphId.value) },
                    )
                client.send("$first\n$second\n")
                val firstResponse = client.readJson()
                val secondResponse = client.readJson()
                assertEquals(1, firstResponse["id"]!!.jsonPrimitive.int)
                assertEquals(2, secondResponse["id"]!!.jsonPrimitive.int)
                assertEquals(FakeGraph.dot, secondResponse["result"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun acceptsRequestSplitAcrossReads() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                val line = requestLine(JsonPrimitive("split"), "initialize")
                val middle = line.length / 2
                client.send(line.substring(0, middle))
                Thread.sleep(50)
                client.send(line.substring(middle) + "\n")
                val response = client.readJson()
                assertEquals("split", response["id"]!!.jsonPrimitive.content)
                assertTrue(response.containsKey("result"))
            }
        }
    }

    @Test
    fun twoClientsQueryConcurrentlyWithoutCorruptingResponses() {
        withServer { server ->
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (0 until 2).map { clientIndex ->
                        executor.submit(
                            Callable {
                                TestClient(server.socketPath).use { client ->
                                    start.await()
                                    repeat(25) { requestIndex ->
                                        val nodeId = "node-$clientIndex-$requestIndex"
                                        val response =
                                            client.request(
                                                requestLine(
                                                    JsonPrimitive(nodeId),
                                                    "node/expand",
                                                    buildJsonObject {
                                                        put("graphId", FakeGraph.graphId.value)
                                                        put("nodeId", nodeId)
                                                        put("revision", FakeGraph.revision)
                                                    },
                                                ),
                                            )
                                        assertEquals(nodeId, response["id"]!!.jsonPrimitive.content)
                                        val node = response.resultObject()["node"]!!.jsonObject
                                        assertEquals(nodeId, node["label"]!!.jsonPrimitive.content)
                                    }
                                }
                                true
                            },
                        )
                    }
                start.countDown()
                futures.forEach { future -> assertTrue(future.get(20, TimeUnit.SECONDS)) }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun unknownMethodReturnsMethodNotFound() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                val response = client.request(requestLine(JsonPrimitive(3), "graph/nope"))
                val error = response.errorObject()
                assertEquals(-32601, error["code"]!!.jsonPrimitive.int)
                assertFalse(error.containsKey("data"))
            }
        }
    }

    @Test
    fun malformedJsonReturnsParseErrorAndKeepsServerAlive() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                client.sendLine("{\"jsonrpc\":")
                val parseError = client.readJson()
                val error = parseError.errorObject()
                assertEquals(-32700, error["code"]!!.jsonPrimitive.int)
                assertFalse(error.containsKey("data"))
                assertTrue(parseError.containsKey("id"))
                val response = client.request(requestLine(JsonPrimitive(4), "initialize"))
                assertEquals(4, response["id"]!!.jsonPrimitive.int)
            }
        }
    }

    @Test
    fun nonObjectRequestReturnsInvalidRequest() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                client.sendLine("[]")
                val response = client.readJson()
                assertEquals(-32600, response.errorObject()["code"]!!.jsonPrimitive.int)
            }
        }
    }

    @Test
    fun preservesStringNumberAndNullRequestIds() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                val stringResponse = client.request(requestLine(JsonPrimitive("abc"), "initialize"))
                assertEquals("abc", stringResponse["id"]!!.jsonPrimitive.content)
                val numberResponse = client.request(requestLine(JsonPrimitive(42), "initialize"))
                assertEquals(42, numberResponse["id"]!!.jsonPrimitive.int)
                val nullResponse = client.request(requestLine(JsonNull, "initialize"))
                assertTrue(nullResponse["id"] is JsonNull)
                val zeroResponse = client.request(requestLine(JsonPrimitive(0), "initialize"))
                assertEquals(0, zeroResponse["id"]!!.jsonPrimitive.int)
                val emptyResponse = client.request(requestLine(JsonPrimitive(""), "initialize"))
                assertEquals("", emptyResponse["id"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun shutdownRemovesSocketEvenWhenServiceCloseBlocks() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        val socket = directory.resolve("engine.sock")
        val service = BlockingCloseGraphService(FakeGraphService(), 5_000)
        val server = EngineSocketServer(socket, service)
        val thread = Thread({ server.run() }, "codenb-blocking-close")
        thread.isDaemon = true
        thread.start()
        try {
            awaitSocket(socket)
            server.close()
            assertTrue(server.awaitTermination(6, TimeUnit.SECONDS))
            assertFalse(Files.exists(socket))
            assertTrue(service.closed)
            assertTrue(
                Thread.getAllStackTraces().keys.none { thread -> thread.name == "codenb-service-close" && thread.isAlive },
            )
        } finally {
            server.close()
            thread.join(5_000)
            deleteTree(directory)
        }
    }

    @Test
    fun reportsFailureWhenServiceCloseIgnoresInterrupts() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        val socket = directory.resolve("engine.sock")
        val latch = CountDownLatch(1)
        val service = LatchCloseGraphService(FakeGraphService(), latch)
        val server = EngineSocketServer(socket, service)
        val failure = AtomicReference<Throwable?>()
        val thread =
            Thread(
                {
                    try {
                        server.run()
                    } catch (exception: Throwable) {
                        failure.set(exception)
                    }
                },
                "codenb-stubborn-close",
            )
        thread.isDaemon = true
        thread.start()
        try {
            awaitSocket(socket)
            server.close()
            assertFalse(server.awaitTermination(6, TimeUnit.SECONDS))
            latch.countDown()
            awaitNoCloserThread()
            assertTrue(service.closed)
            thread.join(5_000)
            assertTrue(failure.get() is IllegalStateException)
        } finally {
            server.close()
            thread.join(5_000)
            deleteTree(directory)
        }
    }

    @Test
    fun reportsFailureWhenServiceCloseThrows() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        val socket = directory.resolve("engine.sock")
        val service = ThrowingCloseGraphService(FakeGraphService())
        val server = EngineSocketServer(socket, service)
        val failure = AtomicReference<Throwable?>()
        val thread =
            Thread(
                {
                    try {
                        server.run()
                    } catch (exception: Throwable) {
                        failure.set(exception)
                    }
                },
                "codenb-throwing-close",
            )
        thread.isDaemon = true
        thread.start()
        try {
            awaitSocket(socket)
            server.close()
            assertFalse(server.awaitTermination(6, TimeUnit.SECONDS))
            assertEquals("close failed", server.closeFailure()?.message)
            awaitNoCloserThread()
            thread.join(5_000)
            assertTrue(failure.get() is IllegalStateException)
        } finally {
            server.close()
            thread.join(5_000)
            deleteTree(directory)
        }
    }

    @Test
    fun connectedClientDoesNotHideCloseFailure() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        val socket = directory.resolve("engine.sock")
        val latch = CountDownLatch(1)
        val service = LatchCloseGraphService(FakeGraphService(), latch)
        val server = EngineSocketServer(socket, service)
        val failure = AtomicReference<Throwable?>()
        val thread =
            Thread(
                {
                    try {
                        server.run()
                    } catch (exception: Throwable) {
                        failure.set(exception)
                    }
                },
                "codenb-connected-close",
            )
        thread.isDaemon = true
        thread.start()
        try {
            awaitSocket(socket)
            TestClient(socket).use { client ->
                client.request(requestLine(JsonPrimitive(1), "initialize"))
                server.close()
                assertFalse(server.awaitTermination(8, TimeUnit.SECONDS))
                latch.countDown()
                awaitNoCloserThread()
                assertTrue(service.closed)
                assertTrue(server.closeFailure() != null)
            }
            thread.join(5_000)
            assertTrue(failure.get() is IllegalStateException)
        } finally {
            server.close()
            thread.join(5_000)
            deleteTree(directory)
        }
    }

    @Test
    fun shutdownReturnsAndRemovesSocket() {
        val service = FakeGraphService()
        withServer(service) { server ->
            TestClient(server.socketPath).use { client ->
                val response = client.request(requestLine(JsonPrimitive(5), "shutdown"))
                assertTrue(response.containsKey("result"))
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (Files.exists(server.socketPath) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertFalse(Files.exists(server.socketPath))
            assertTrue(server.awaitStopped())
            assertTrue(service.closed)
        }
    }

    @Test
    fun createsOwnerOnlyDirectoryAndSocket() {
        val workspace = Path.of("/tmp/workspace")
        withServer(
            socketPathFactory = { directory -> SocketPaths.derive(workspace, "kotlin", directory.toString(), "1000") },
        ) { server ->
            val directory = server.socketPath.parent
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(directory),
            )
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
                Files.getPosixFilePermissions(server.socketPath),
            )
        }
    }

    @Test
    fun removesStaleSocket() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        try {
            val path = directory.resolve("engine.sock")
            val stale = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            stale.bind(UnixDomainSocketAddress.of(path))
            stale.close()
            assertTrue(Files.exists(path))
            withServer(socketPathFactory = { _ -> path }) { server ->
                TestClient(server.socketPath).use { client ->
                    val response = client.request(requestLine(JsonPrimitive(1), "initialize"))
                    assertTrue(response.containsKey("result"))
                }
            }
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun refusesToRemoveLiveDaemonSocket() {
        withServer { running ->
            val other = EngineSocketServer(running.socketPath, FakeGraphService())
            assertFailsWith<IOException> { other.run() }
            assertTrue(Files.exists(running.socketPath))
            TestClient(running.socketPath).use { client ->
                val response = client.request(requestLine(JsonPrimitive(1), "initialize"))
                assertTrue(response.containsKey("result"))
            }
        }
    }

    @Test
    fun restoresOwnerOnlyPermissionsOnAnExistingSocketDirectory() {
        val workspace = Path.of("/tmp/workspace")
        withServer(
            socketPathFactory = { directory ->
                val parent = directory.resolve("codenb-1000")
                Files.createDirectories(parent)
                Files.setPosixFilePermissions(
                    parent,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE,
                        PosixFilePermission.OTHERS_EXECUTE,
                    ),
                )
                SocketPaths.derive(workspace, "kotlin", directory.toString(), "1000")
            },
        ) { server ->
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(server.socketPath.parent),
            )
        }
    }

    @Test
    fun refusesToRemoveFifoAtSocketPath() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        try {
            val path = directory.resolve("engine.sock")
            val mkfifo = ProcessBuilder("mkfifo", path.toString()).start()
            assertEquals(0, mkfifo.waitFor())
            val server = EngineSocketServer(path, FakeGraphService())
            assertFailsWith<IOException> { server.run() }
            val mode = Files.getAttribute(path, "unix:mode") as Int
            assertEquals(0x1000, mode and 0xF000)
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun refusesSymlinkSocketParent() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        try {
            val link = directory.resolve("link")
            Files.createSymbolicLink(link, directory)
            val server = EngineSocketServer(link.resolve("engine.sock"), FakeGraphService())
            assertFailsWith<IOException> { server.run() }
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun refusesSymlinkAtTheSocketPath() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        try {
            val target = directory.resolve("target.sock")
            val stale = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            stale.bind(UnixDomainSocketAddress.of(target))
            stale.close()
            val link = directory.resolve("engine.sock")
            Files.createSymbolicLink(link, target)
            val server = EngineSocketServer(link, FakeGraphService())
            assertFailsWith<IOException> { server.run() }
            assertTrue(Files.isSymbolicLink(link))
            assertEquals(target, Files.readSymbolicLink(link))
            assertTrue(Files.exists(target))
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun refusesToClobberNonSocketFile() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb")
        try {
            val path = directory.resolve("engine.sock")
            Files.writeString(path, "keep-me")
            val server = EngineSocketServer(path, FakeGraphService())
            assertFailsWith<IOException> { server.run() }
            assertEquals("keep-me", Files.readString(path))
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun mapsApplicationErrorsToSingleJsonRpcErrorWithEngineErrorData() {
        val service =
            FakeGraphService().apply {
                failure =
                    engineException(
                        EngineErrorCode.UNKNOWN_NODE,
                        "No such node",
                        "nodeId" to "missing",
                    )
            }
        withServer(service) { server ->
            TestClient(server.socketPath).use { client ->
                val response =
                    client.request(
                        requestLine(
                            JsonPrimitive(9),
                            "node/get",
                            buildJsonObject {
                                put("graphId", FakeGraph.graphId.value)
                                put("nodeId", "missing")
                            },
                        ),
                    )
                assertEquals(9, response["id"]!!.jsonPrimitive.int)
                val error = response.errorObject()
                assertEquals(-32000, error["code"]!!.jsonPrimitive.int)
                val data = error["data"]!!.jsonObject
                assertEquals("unknown-node", data["code"]!!.jsonPrimitive.content)
                service.failure = null
                val followUp = client.request(requestLine(JsonPrimitive(10), "initialize"))
                assertEquals(10, followUp["id"]!!.jsonPrimitive.int)
                assertTrue(followUp.containsKey("result"))
            }
        }
    }

    @Test
    fun emitsCintErrorOnlyForApplicationFailuresWithoutRequestId() {
        val service =
            FakeGraphService().apply {
                failure = engineException(EngineErrorCode.LSP_FAILURE, "LSP went away")
            }
        withServer(service) { server ->
            TestClient(server.socketPath).use { client ->
                client.sendLine(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", "initialize")
                    }.toString(),
                )
                val notification = client.readJson()
                assertEquals("cint/error", notification["method"]!!.jsonPrimitive.content)
                val error = notification["params"]!!.jsonObject["error"]!!.jsonObject
                assertEquals("lsp-failure", error["code"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun invalidParamsIsASingleProtocolErrorWithoutCintErrorOrEngineErrorData() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                val response = client.request(requestLine(JsonPrimitive(11), "node/get"))
                val error = response.errorObject()
                assertEquals(-32602, error["code"]!!.jsonPrimitive.int)
                assertFalse(error.containsKey("data"))
                val followUp = client.request(requestLine(JsonPrimitive(12), "initialize"))
                assertEquals(12, followUp["id"]!!.jsonPrimitive.int)
            }
        }
    }

    @Test
    fun exposesStructuredAmbiguousSeedCandidates() {
        val service =
            FakeGraphService().apply {
                failure =
                    EngineException(
                        dev.codenb.api.EngineError(
                            EngineErrorCode.INVALID_REQUEST,
                            "Seed selector is ambiguous",
                            mapOf(
                                "candidateCount" to "2",
                                "candidates.0.id" to "id-0",
                                "candidates.0.fingerprint" to "fun over(a: Int, b: Int)",
                                "candidates.1.id" to "id-1",
                                "candidates.1.fingerprint" to "fun over(a: String, b: String)",
                            ),
                        ),
                    )
            }
        withServer(service) { server ->
            TestClient(server.socketPath).use { client ->
                val response =
                    client.request(
                        requestLine(
                            JsonPrimitive(13),
                            "graph/open",
                            buildJsonObject {
                                put("file", "O.kt")
                                put("qualifiedName", "app.over")
                            },
                        ),
                    )
                val error = response.errorObject()
                assertEquals(-32000, error["code"]!!.jsonPrimitive.int)
                val details = error["data"]!!.jsonObject["details"]!!.jsonObject
                assertEquals("2", details["candidateCount"]!!.jsonPrimitive.content)
                assertEquals("id-0", details["candidates.0.id"]!!.jsonPrimitive.content)
                assertEquals(
                    "fun over(a: String, b: String)",
                    details["candidates.1.fingerprint"]!!.jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun expansionKindsAreConsistentWithInitializeMetadata() {
        withServer { server ->
            TestClient(server.socketPath).use { client ->
                val initialized = client.request(requestLine(JsonPrimitive(1), "initialize")).resultObject()
                val graph = initialized["graph"]!!.jsonObject
                val nodeKinds = (graph["nodeKinds"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
                val expandable = (initialized["expandableNodeKinds"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
                val openedResponse =
                    client.request(
                        requestLine(
                            JsonPrimitive(2),
                            "graph/open",
                            buildJsonObject {
                                put("file", "/tmp/workspace/A.kt")
                                put("qualifiedName", "app.seed")
                            },
                        ),
                    )
                val opened = openedResponse.resultObject()
                val expansion = GraphJson.decode<Expansion>(opened["expansion"]!!.toString())
                val kinds = (listOf(expansion.node) + expansion.neighbours).map { it.kind.name }.toSet()
                assertTrue(kinds.containsAll(setOf("ENTITY", "FILE", "GROUP")))
                assertTrue(nodeKinds.containsAll(kinds))
                assertFalse(expandable.contains("FILE"))
                assertFalse(expandable.contains("GROUP"))
            }
        }
    }

    @Test
    fun graphMethodsFailBeforeOpenAndReportOpenState() {
        val service = FakeGraphService(serviceStatus = GraphStatus(false, null))
        withServer(service) { server ->
            TestClient(server.socketPath).use { client ->
                val before = client.request(requestLine(JsonPrimitive(1), "initialize"))
                val beforeResult = before.resultObject()
                assertFalse(beforeResult["open"]!!.jsonPrimitive.boolean)
                assertTrue(beforeResult["revision"] is JsonNull)

                val dotBefore =
                    client.request(
                        requestLine(
                            JsonPrimitive(2),
                            "graph/dot",
                            buildJsonObject { put("graphId", FakeGraph.graphId.value) },
                        ),
                    )
                val dotError = dotBefore.errorObject()
                assertEquals("invalid-graph", dotError["data"]!!.jsonObject["code"]!!.jsonPrimitive.content)

                val nodeBefore =
                    client.request(
                        requestLine(
                            JsonPrimitive(3),
                            "node/get",
                            buildJsonObject {
                                put("graphId", FakeGraph.graphId.value)
                                put("nodeId", FakeGraph.seed.id.value)
                            },
                        ),
                    )
                val nodeError = nodeBefore.errorObject()
                assertEquals("invalid-graph", nodeError["data"]!!.jsonObject["code"]!!.jsonPrimitive.content)

                client.request(
                    requestLine(
                        JsonPrimitive(4),
                        "graph/open",
                        buildJsonObject {
                            put("file", "/tmp/workspace/A.kt")
                            put("qualifiedName", "app.seed")
                        },
                    ),
                )
                val after = client.request(requestLine(JsonPrimitive(5), "initialize"))
                val afterResult = after.resultObject()
                assertTrue(afterResult["open"]!!.jsonPrimitive.boolean)
                assertEquals(FakeGraph.revision, afterResult["revision"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun graphMethodsUseTheFakeService() {
        val service = FakeGraphService()
        withServer(service) { server ->
            TestClient(server.socketPath).use { client ->
                val openResponse =
                    client.request(
                        requestLine(
                            JsonPrimitive(1),
                            "graph/open",
                            buildJsonObject {
                                put("file", "/tmp/workspace/A.kt")
                                put("qualifiedName", "app.seed")
                            },
                        ),
                    )
                val openResult = openResponse.resultObject()
                val openGraph = openResult["graph"]!!.jsonObject
                assertEquals(FakeGraph.graphId.value, openGraph["graphId"]!!.jsonPrimitive.content)
                val openExpansion = openResult["expansion"]!!.jsonObject
                val openNode = openExpansion["node"]!!.jsonObject
                assertEquals(FakeGraph.seed.id.value, openNode["id"]!!.jsonPrimitive.content)
                assertTrue(service.openedWith is Seed.Selector)

                val nodeOpen =
                    client.request(
                        requestLine(
                            JsonPrimitive(2),
                            "graph/open",
                            buildJsonObject { put("nodeId", FakeGraph.seed.id.value) },
                        ),
                    )
                assertTrue(nodeOpen.containsKey("result"))
                assertTrue(service.openedWith is Seed.ByNodeId)

                val rejectedSeed =
                    client.request(
                        requestLine(
                            JsonPrimitive(6),
                            "graph/open",
                            buildJsonObject { put("seed", "src/A.kt#app.seed") },
                        ),
                    )
                assertEquals(-32602, rejectedSeed.errorObject()["code"]!!.jsonPrimitive.int)

                val rejectedSelector =
                    client.request(
                        requestLine(
                            JsonPrimitive(7),
                            "graph/open",
                            buildJsonObject { put("selector", "src/A.kt#app.seed") },
                        ),
                    )
                assertEquals(-32602, rejectedSelector.errorObject()["code"]!!.jsonPrimitive.int)

                val fingerprintOpen =
                    client.request(
                        requestLine(
                            JsonPrimitive(8),
                            "graph/open",
                            buildJsonObject {
                                put("file", "/tmp/workspace/A.kt")
                                put("qualifiedName", "app.seed")
                                put("signatureFingerprint", "fun seed()")
                            },
                        ),
                    )
                assertTrue(fingerprintOpen.containsKey("result"))
                assertEquals(
                    Seed.Selector("/tmp/workspace/A.kt", "app.seed", "fun seed()"),
                    service.openedWith,
                )

                val expandResponse =
                    client.request(
                        requestLine(
                            JsonPrimitive(3),
                            "node/expand",
                            buildJsonObject {
                                put("graphId", FakeGraph.graphId.value)
                                put("nodeId", "entity/target")
                                put("revision", FakeGraph.revision)
                            },
                        ),
                    )
                val expandedNode = expandResponse.resultObject()["node"]!!.jsonObject
                assertEquals(
                    "entity/target",
                    expandedNode["id"]!!.jsonPrimitive.content,
                )

                val storedResponse =
                    client.request(
                        requestLine(
                            JsonPrimitive(4),
                            "node/get",
                            buildJsonObject {
                                put("graphId", FakeGraph.graphId.value)
                                put("nodeId", FakeGraph.seed.id.value)
                            },
                        ),
                    )
                val stored = GraphJson.decode<Expansion>(storedResponse["result"]!!.toString())
                assertEquals(FakeGraph.graphId, stored.graphId)
                assertEquals(FakeGraph.revision, stored.revision)
                assertEquals(FakeGraph.seed.id, stored.node.id)
                assertEquals(3, stored.neighbours.size)
                assertEquals(4, stored.edges.size)

                val dotResponse =
                    client.request(
                        requestLine(
                            JsonPrimitive(5),
                            "graph/dot",
                            buildJsonObject { put("graphId", FakeGraph.graphId.value) },
                        ),
                    )
                assertEquals(FakeGraph.dot, dotResponse["result"]!!.jsonPrimitive.content)
            }
        }
    }

    private fun awaitNoCloserThread() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < deadline) {
            val alive =
                Thread.getAllStackTraces().keys.any { thread ->
                    thread.name == "codenb-service-close" && thread.isAlive
                }
            if (!alive) {
                return
            }
            Thread.sleep(20)
        }
        assertTrue(
            Thread.getAllStackTraces().keys.none { thread -> thread.name == "codenb-service-close" && thread.isAlive },
        )
    }

    private fun JsonObject.resultObject(): JsonObject = this["result"]!!.jsonObject

    private fun JsonObject.errorObject(): JsonObject = this["error"]!!.jsonObject
}
