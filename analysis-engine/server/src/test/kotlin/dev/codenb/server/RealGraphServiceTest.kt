package dev.codenb.server

import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.EngineErrorCode
import dev.codenb.api.Expansion
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.frontend.KotlinFrontend
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealGraphServiceTest {
    @Test
    fun opensRootRelativeAndAbsoluteSeedsAndReportsStatus() {
        withFixture { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            val path = fixture.write("A.kt", source)
            fixture.lsp.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
            val service = fixture.service
            assertFalse(service.status().open)
            assertEquals(
                EngineErrorCode.INVALID_GRAPH,
                code { service.dot(service.info().graphId) },
            )
            assertEquals(
                EngineErrorCode.INVALID_GRAPH,
                code { service.node(service.info().graphId, NodeId("missing")) },
            )
            val relative = service.open(Seed.Selector("A.kt", "app.seed"))
            assertEquals("app.seed", relative.node.qualifiedName)
            assertTrue(service.status().open)
            assertEquals(relative.revision, service.status().revision)
            assertEquals(1, fixture.lsp.factoryCalls.get())
            val absolute = service.open(Seed.Selector(path.toString(), "app.seed"))
            assertEquals(relative.revision, absolute.revision)
            assertEquals(relative.node.id, absolute.node.id)
            assertEquals(
                EngineErrorCode.INVALID_GRAPH,
                code { service.expand(service.info().graphId, relative.node.id, "stale") },
            )
            assertEquals(listOf(NodeKind.ENTITY), service.expandableNodeKinds())
            assertTrue(service.info().nodeKinds.containsAll(listOf(NodeKind.ENTITY, NodeKind.FILE, NodeKind.GROUP)))
            val stored = service.node(service.info().graphId, relative.node.id)
            assertEquals(relative.revision, stored.revision)
            assertEquals(relative.node.id, stored.node.id)
        }
    }

    @Test
    fun rejectsExcludedSeedBeforeStartingLsp() {
        val config =
            EngineConfigLoader.defaults().copy(
                workspace =
                    EngineConfigLoader.defaults().workspace.copy(
                        exclude = listOf("A.kt"),
                    ),
            )
        withFixture(config) { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            fixture.write("A.kt", source)
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("A.kt", "app.seed")) }
            assertEquals(EngineErrorCode.WORKSPACE_ERROR, exception.error.code)
            assertEquals(0, fixture.lsp.factoryCalls.get())
        }
    }

    @Test
    fun rejectsSeedOutsideTheWorkspaceRoot() {
        withFixture { fixture ->
            val outside = Files.createTempDirectory(Path.of("/tmp"), "codenb-outside")
            try {
                val path = outside.resolve("A.kt")
                Files.writeString(path, "package app\n\nfun seed(): Int = 1\n")
                val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector(path.toString(), "app.seed")) }
                assertEquals(EngineErrorCode.WORKSPACE_ERROR, exception.error.code)
                assertEquals(0, fixture.lsp.factoryCalls.get())
            } finally {
                deleteTree(outside)
            }
        }
    }

    @Test
    fun ignoresReferencesIntoExcludedFiles() {
        val config =
            EngineConfigLoader.defaults().copy(
                workspace =
                    EngineConfigLoader.defaults().workspace.copy(
                        exclude = listOf("B.kt"),
                    ),
            )
        withFixture(config) { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun caller(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "caller", "fun caller()", "fun caller"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val callerReference = bSource.indexOf("seed", bSource.indexOf("fun caller"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, callerReference, "seed"))
            val expansion = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertFalse(expansion.neighbours.any { it.qualifiedName == "app.caller" })
        }
    }

    @Test
    fun ambiguousOverloadRequiresSignatureFingerprint() {
        withFixture { fixture ->
            val source =
                """
                package app

                fun over(a: Int, b: Int): Int = 1

                fun over(a: String, b: String): Int = 2
                """.trimIndent()
            val path = fixture.write("O.kt", source)
            val secondStart = source.indexOf("fun over", source.indexOf("fun over") + 1)
            val symbols =
                listOf(
                    functionSymbol(source, "over", "fun over(a: Int, b: Int)", "fun over"),
                    functionSymbol(source, "over", "fun over(a: String, b: String)", "fun over", secondStart),
                )
            fixture.lsp.symbols[path.toUri().toString()] = symbols
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("O.kt", "app.over")) }
            assertEquals(EngineErrorCode.INVALID_REQUEST, exception.error.code)
            val details = exception.error.details
            assertEquals("2", details.getValue("candidateCount"))
            assertTrue(details.getValue("candidates.0.fingerprint").contains("a: Int, b: Int"))
            assertTrue(details.getValue("candidates.1.fingerprint").contains("a: String, b: String"))
            assertTrue(details.getValue("candidates.0.id").isNotBlank())
            assertTrue(details.getValue("candidates.1.id").isNotBlank())
            val selected =
                fixture.service.open(
                    Seed.Selector("O.kt", "app.over", "fun over(a: String, b: String)"),
                )
            assertEquals("fun over(a: String, b: String)", selected.node.signatureFingerprint)
        }
    }

    @Test
    fun sameRevisionNoFingerprintSelectorStillReportsOverloadAmbiguity() {
        withFixture { fixture ->
            val source =
                """
                package app

                fun over(a: Int, b: Int): Int = 1

                fun over(a: String, b: String): Int = 2
                """.trimIndent()
            val path = fixture.write("O.kt", source)
            val secondStart = source.indexOf("fun over", source.indexOf("fun over") + 1)
            fixture.lsp.symbols[path.toUri().toString()] =
                listOf(
                    functionSymbol(source, "over", "fun over(a: Int, b: Int)", "fun over"),
                    functionSymbol(source, "over", "fun over(a: String, b: String)", "fun over", secondStart),
                )
            fixture.service.open(Seed.Selector("O.kt", "app.over", "fun over(a: Int, b: Int)"))
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("O.kt", "app.over")) }
            assertEquals(EngineErrorCode.INVALID_REQUEST, exception.error.code)
            assertEquals("2", exception.error.details.getValue("candidateCount"))
            fixture.lsp.queryFailure = IllegalStateException("LSP must not be queried")
            val offline = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("O.kt", "app.over")) }
            assertEquals(EngineErrorCode.INVALID_REQUEST, offline.error.code)
            assertEquals("2", offline.error.details.getValue("candidateCount"))
        }
    }

    @Test
    fun neighbourDiscoveredOverloadsRemainAmbiguousWithLspUnavailable() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val oSource =
                """
                package app

                fun over(a: Int, b: Int): Int = 1

                fun over(a: String, b: String): Int = 2
                """.trimIndent()
            val aPath = fixture.write("A.kt", aSource)
            val oPath = fixture.write("O.kt", oSource)
            val aUri = aPath.toUri().toString()
            val oUri = oPath.toUri().toString()
            val secondStart = oSource.indexOf("fun over", oSource.indexOf("fun over") + 1)
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[oUri] =
                listOf(
                    functionSymbol(oSource, "over", "fun over(a: Int, b: Int)", "fun over"),
                    functionSymbol(oSource, "over", "fun over(a: String, b: String)", "fun over", secondStart),
                )
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val firstOver = oSource.indexOf("over", oSource.indexOf("fun "))
            val secondOver = oSource.indexOf("over", secondStart)
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(
                    locationAt(oUri, oSource, firstOver, "over"),
                    locationAt(oUri, oSource, secondOver, "over"),
                )
            fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            fixture.lsp.queryFailure = IllegalStateException("LSP must not be queried")
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("O.kt", "app.over")) }
            assertEquals(EngineErrorCode.INVALID_REQUEST, exception.error.code)
            assertEquals("2", exception.error.details.getValue("candidateCount"))
        }
    }

    @Test
    fun changedRevisionReusesDiscoveryFoundByFailedCandidate() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val aPath = fixture.write("A.kt", aSource)
            fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 1\n")
            val oSource =
                """
                package app

                fun over(a: Int, b: Int): Int = 1

                fun over(a: String, b: String): Int = 2
                """.trimIndent()
            val oPath = fixture.write("O.kt", oSource)
            val secondStart = oSource.indexOf("fun over", oSource.indexOf("fun over") + 1)
            fixture.lsp.symbols[aPath.toUri().toString()] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[oPath.toUri().toString()] =
                listOf(
                    functionSymbol(oSource, "over", "fun over(a: Int, b: Int)", "fun over"),
                    functionSymbol(oSource, "over", "fun over(a: String, b: String)", "fun over", secondStart),
                )
            fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 2\n")
            val first = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("O.kt", "app.over")) }
            assertEquals(EngineErrorCode.INVALID_REQUEST, first.error.code)
            fixture.lsp.queryFailure = IllegalStateException("LSP must not be queried")
            val second = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("O.kt", "app.over")) }
            assertEquals(EngineErrorCode.INVALID_REQUEST, second.error.code)
            assertEquals("2", second.error.details.getValue("candidateCount"))
        }
    }

    @Test
    fun failedComputeDoesNotPoisonDiscoveryCache() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val cSource = "package app\n\nfun over(a: Int): Int = 1\n"
            val aPath = fixture.write("A.kt", aSource)
            val cPath = fixture.write("C.kt", cSource)
            val aUri = aPath.toUri().toString()
            val cUri = cPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[cUri] = listOf(functionSymbol(cSource, "over", "fun over(a: Int)", "fun over"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(cUri, cSource, cSource.indexOf("over", cSource.indexOf("fun ")), "over"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertEquals(1, fixture.service.discoveredForTest(first.revision, cUri)?.size)
            val twoOverloads =
                """
                package app

                fun over(a: Int): Int = 1

                fun over(a: String): Int = 2
                """.trimIndent()
            val twoOverloadSymbols =
                listOf(
                    functionSymbol(twoOverloads, "over", "fun over(a: Int)", "fun over"),
                    functionSymbol(
                        twoOverloads,
                        "over",
                        "fun over(a: String)",
                        "fun over",
                        twoOverloads.indexOf("fun over", twoOverloads.indexOf("fun over") + 1),
                    ),
                )
            fixture.write("C.kt", twoOverloads)
            fixture.lsp.symbols[cUri] = twoOverloadSymbols
            var reverted = false
            fixture.lsp.referencesBlock = {
                if (!reverted) {
                    reverted = true
                    fixture.write("C.kt", cSource)
                    fixture.lsp.symbols[cUri] = listOf(functionSymbol(cSource, "over", "fun over(a: Int)", "fun over"))
                }
            }
            fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertEquals(first.revision, fixture.service.status().revision)
            assertEquals(1, fixture.service.discoveredForTest(first.revision, cUri)?.size)
            fixture.write("C.kt", twoOverloads)
            fixture.lsp.symbols[cUri] = twoOverloadSymbols
            val paths =
                Files.walk(fixture.workspace).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.toList().toSet()
                }
            val r2 = ContentRevisions.hash(fixture.workspace, paths)
            assertNull(fixture.service.discoveredForTest(r2, cUri))
            fixture.lsp.queryFailure = dev.codenb.lsp.LspTransportException("LSP must not be queried")
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("C.kt", "app.over")) }
            assertEquals(EngineErrorCode.LSP_FAILURE, exception.error.code)
        }
    }

    @Test
    fun currentRevisionDiscoverySurvivesFailedCandidateEviction() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val aPath = fixture.write("A.kt", aSource)
            val original = "package app\n\nfun unrelated(): Int = 1\n"
            fixture.write("C.kt", original)
            fixture.lsp.symbols[aPath.toUri().toString()] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            repeat(17) { index ->
                fixture.write("C.kt", "package app\n\nfun unrelated(): Int = ${index + 2}\n")
                assertFailsWith<EngineException> {
                    fixture.service.open(Seed.Selector("A.kt", "app.missing"))
                }
            }
            fixture.write("C.kt", original)
            val revision = fixture.service.status().revision ?: error("Graph is not open")
            assertTrue(fixture.service.discoveredForTest(revision, aPath.toUri().toString()) != null)
            fixture.lsp.queryFailure = IllegalStateException("LSP must not be queried")
            val reopened = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertEquals("app.seed", reopened.node.qualifiedName)
        }
    }

    @Test
    fun statusReportsClosedWhenCloseInterruptsContentHash() {
        val blocked = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider =
            ContentRevisionProvider { base, paths ->
                val revision = ContentRevisions.hash(base, paths)
                if (blocked.get()) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                revision
            }
        withFixture(revisionProvider = provider) { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            val path = fixture.write("A.kt", source)
            fixture.lsp.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
            fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            blocked.set(true)
            val result = AtomicReference<GraphStatus?>()
            val statusThread =
                Thread(
                    {
                        result.set(fixture.service.status())
                    },
                    "codenb-blocked-status",
                )
            statusThread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closeThread = Thread({ fixture.service.close() }, "codenb-close-status")
            closeThread.start()
            Thread.sleep(100)
            closeThread.interrupt()
            closeThread.join(3000)
            release.countDown()
            statusThread.join(3000)
            assertFalse(closeThread.isAlive)
            assertFalse(statusThread.isAlive)
            assertEquals(GraphStatus(false, null), result.get())
        }
    }

    @Test
    fun commitGateInterruptionOnCacheMissExpandLeavesNoMutation() {
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate =
            CommitGate {
                if (armed.get()) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        withFixture(commitGate = gate) { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun target(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "target", "fun target()", "fun target"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val targetReference = bSource.indexOf("seed", bSource.indexOf("fun target"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, targetReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val graphId = fixture.service.info().graphId
            val target = first.neighbours.single { it.qualifiedName == "app.target" }
            val snapshotBefore = fixture.service.snapshotForTest()
            assertNull(fixture.service.cachedExpansionForTest(target.id, first.revision))
            armed.set(true)
            val failure = AtomicReference<Throwable?>()
            val expandThread =
                Thread(
                    {
                        try {
                            fixture.service.expand(graphId, target.id, first.revision)
                        } catch (exception: Throwable) {
                            failure.set(exception)
                        }
                    },
                    "codenb-gate-expand",
                )
            expandThread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closeThread = Thread({ fixture.service.close() }, "codenb-gate-expand-close")
            closeThread.start()
            Thread.sleep(100)
            closeThread.interrupt()
            closeThread.join(3000)
            release.countDown()
            expandThread.join(3000)
            assertFalse(closeThread.isAlive)
            assertFalse(expandThread.isAlive)
            val exception = failure.get()
            assertTrue(exception is EngineException)
            assertEquals(EngineErrorCode.INVALID_GRAPH, exception.error.code)
            assertEquals(snapshotBefore, fixture.service.snapshotForTest())
            assertNull(fixture.service.cachedExpansionForTest(target.id, first.revision))
        }
    }

    @Test
    fun commitGateInterruptionOnChangedRevisionOpenLeavesNoMutation() {
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate =
            CommitGate {
                if (armed.get()) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        withFixture(commitGate = gate) { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val aPath = fixture.write("A.kt", aSource)
            fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 1\n")
            val aUri = aPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 2\n")
            assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("A.kt", "app.missing")) }
            val paths =
                Files.walk(fixture.workspace).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.toList().toSet()
                }
            val r2 = ContentRevisions.hash(fixture.workspace, paths)
            val discoveredBefore = fixture.service.discoveredForTest(r2, aUri)
            assertTrue(discoveredBefore != null)
            val snapshotBefore = fixture.service.snapshotForTest()
            val cacheBefore = fixture.service.cachedExpansionForTest(first.node.id, first.revision)
            assertNull(fixture.service.cachedExpansionForTest(first.node.id, r2))
            armed.set(true)
            val failure = AtomicReference<Throwable?>()
            val openThread =
                Thread(
                    {
                        try {
                            fixture.service.open(Seed.Selector("A.kt", "app.seed"))
                        } catch (exception: Throwable) {
                            failure.set(exception)
                        }
                    },
                    "codenb-gate-open",
                )
            openThread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closeThread = Thread({ fixture.service.close() }, "codenb-gate-open-close")
            closeThread.start()
            Thread.sleep(100)
            closeThread.interrupt()
            closeThread.join(3000)
            release.countDown()
            openThread.join(3000)
            assertFalse(closeThread.isAlive)
            assertFalse(openThread.isAlive)
            val exception = failure.get()
            assertTrue(exception is EngineException)
            assertEquals(EngineErrorCode.INVALID_GRAPH, exception.error.code)
            assertEquals(snapshotBefore, fixture.service.snapshotForTest())
            assertEquals(cacheBefore, fixture.service.cachedExpansionForTest(first.node.id, first.revision))
            assertEquals(discoveredBefore, fixture.service.discoveredForTest(r2, aUri))
            assertNull(fixture.service.cachedExpansionForTest(first.node.id, r2))
        }
    }

    @Test
    fun discoveredForTestDoesNotRefreshEvictionOrder() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val aPath = fixture.write("A.kt", aSource)
            val aUri = aPath.toUri().toString()
            fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 1\n")
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val revisions = mutableListOf<String>()
            for (index in 2..16) {
                fixture.write("C.kt", "package app\n\nfun unrelated(): Int = $index\n")
                revisions.add(contentRevisionOf(fixture))
                assertFailsWith<EngineException> {
                    fixture.service.open(Seed.Selector("A.kt", "app.missing"))
                }
            }
            val r2 = revisions[0]
            val r3 = revisions[1]
            assertNotNull(fixture.service.discoveredForTest(r2, aUri))
            fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 17\n")
            assertFailsWith<EngineException> {
                fixture.service.open(Seed.Selector("A.kt", "app.missing"))
            }
            assertNull(fixture.service.discoveredForTest(r2, aUri))
            assertNotNull(fixture.service.discoveredForTest(r3, aUri))
        }
    }

    private fun contentRevisionOf(fixture: Fixture): String {
        val paths =
            Files.walk(fixture.workspace).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList().toSet()
            }
        return ContentRevisions.hash(fixture.workspace, paths)
    }

    @Test
    fun commitGateInterruptionLeavesCacheAndStoreUntouched() {
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate =
            CommitGate {
                if (armed.get()) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        withFixture(commitGate = gate) { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun oldTarget(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "oldTarget", "fun oldTarget()", "fun oldTarget"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val oldReference = bSource.indexOf("seed", bSource.indexOf("fun oldTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, oldReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val graphId = fixture.service.info().graphId
            val oldTarget = first.neighbours.single { it.qualifiedName == "app.oldTarget" }
            val oldTargetPosition = positionAt(bSource, bSource.indexOf("oldTarget", bSource.indexOf("fun ")))
            fixture.lsp.referencesByCall[LspCall(bUri, oldTargetPosition)] =
                listOf(locationAt(aUri, aSource, aSource.indexOf("seed", aSource.indexOf("fun ")), "seed"))
            fixture.service.expand(graphId, oldTarget.id, first.revision)
            val snapshotBefore = fixture.service.snapshotForTest()
            val cacheBefore = fixture.service.cachedExpansionForTest(first.node.id, first.revision)
            armed.set(true)
            val failure = AtomicReference<Throwable?>()
            val nodeThread =
                Thread(
                    {
                        try {
                            fixture.service.node(graphId, first.node.id)
                        } catch (exception: Throwable) {
                            failure.set(exception)
                        }
                    },
                    "codenb-commit-gate-node",
                )
            nodeThread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closeThread = Thread({ fixture.service.close() }, "codenb-commit-gate-close")
            closeThread.start()
            Thread.sleep(100)
            closeThread.interrupt()
            closeThread.join(3000)
            release.countDown()
            nodeThread.join(3000)
            assertFalse(closeThread.isAlive)
            assertFalse(nodeThread.isAlive)
            val exception = failure.get()
            assertTrue(exception is EngineException)
            assertEquals(EngineErrorCode.INVALID_GRAPH, exception.error.code)
            assertEquals(snapshotBefore, fixture.service.snapshotForTest())
            assertEquals(cacheBefore, fixture.service.cachedExpansionForTest(first.node.id, first.revision))
        }
    }

    @Test
    fun selectorValidationIsConsistentForFreshAndCachedOpens() {
        withFixture { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            val path = fixture.write("A.kt", source)
            fixture.lsp.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
            assertEquals(
                EngineErrorCode.INVALID_REQUEST,
                code { fixture.service.open(Seed.Selector("A.kt", "")) },
            )
            assertEquals(
                EngineErrorCode.INVALID_REQUEST,
                code { fixture.service.open(Seed.Selector("A.kt", "app.seed", "")) },
            )
            assertEquals(
                EngineErrorCode.INVALID_REQUEST,
                code { fixture.service.open(Seed.Selector("bad\u0000path", "app.seed")) },
            )
            fixture.service.open(Seed.Selector("A.kt", "app.seed", "fun seed()"))
            assertEquals(
                EngineErrorCode.INVALID_REQUEST,
                code { fixture.service.open(Seed.Selector("A.kt", "")) },
            )
            assertEquals(
                EngineErrorCode.INVALID_REQUEST,
                code { fixture.service.open(Seed.Selector("A.kt", "app.seed", "")) },
            )
        }
    }

    @Test
    fun reopenAfterEditReplacesTheOldGraphAndRetiresItsRevision() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun oldTarget(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "oldTarget", "fun oldTarget()", "fun oldTarget"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val oldReference = bSource.indexOf("seed", bSource.indexOf("fun oldTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, oldReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val oldTarget = first.neighbours.single { it.qualifiedName == "app.oldTarget" }
            val oldRevision = first.revision
            val renamedSource = "package app\n\nfun newTarget(): Int = seed()\n"
            fixture.write("B.kt", renamedSource)
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(renamedSource, "newTarget", "fun newTarget()", "fun newTarget"))
            val newReference = renamedSource.indexOf("seed", renamedSource.indexOf("fun newTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, renamedSource, newReference, "seed"))
            val second = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertNotEquals(oldRevision, second.revision)
            assertEquals(second.revision, fixture.service.status().revision)
            assertFalse(second.neighbours.any { it.id == oldTarget.id })
            assertTrue(second.neighbours.any { it.qualifiedName == "app.newTarget" })
            assertEquals(
                EngineErrorCode.UNKNOWN_NODE,
                code { fixture.service.node(fixture.service.info().graphId, oldTarget.id) },
            )
            val retired =
                assertFailsWith<EngineException> {
                    fixture.service.expand(fixture.service.info().graphId, second.node.id, oldRevision)
                }
            assertEquals(EngineErrorCode.INVALID_GRAPH, retired.error.code)
            assertEquals(oldRevision, retired.error.details.getValue("revision"))
            assertEquals(second.revision, retired.error.details.getValue("currentRevision"))
        }
    }

    @Test
    fun nodeIdOpenAfterEditRequiresASelectorReopen() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun target(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "target", "fun target()", "fun target"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val targetReference = bSource.indexOf("seed", bSource.indexOf("fun target"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, targetReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            fixture.write("B.kt", "package app\n\nfun target(): Int = 1\n")
            assertFalse(fixture.service.status().open)
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.ByNodeId(first.node.id)) }
            assertEquals(EngineErrorCode.INVALID_GRAPH, exception.error.code)
            assertEquals(first.revision, exception.error.details.getValue("revision"))
            val contentRevision = exception.error.details.getValue("currentRevision")
            assertTrue(contentRevision.isNotBlank())
            assertNotEquals(first.revision, contentRevision)
        }
    }

    @Test
    fun failedFirstOpenLeavesGraphClosed() {
        withFixture { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            val path = fixture.write("A.kt", source)
            fixture.lsp.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
            fixture.lsp.referencesFailure = dev.codenb.lsp.LspTransportException("references failed")
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("A.kt", "app.seed")) }
            assertEquals(EngineErrorCode.LSP_FAILURE, exception.error.code)
            assertEquals(GraphStatus(false, null), fixture.service.status())
            assertEquals(
                EngineErrorCode.INVALID_GRAPH,
                code { fixture.service.dot(fixture.service.info().graphId) },
            )
        }
    }

    @Test
    fun failedReopenLeavesThePreviousGraphIntact() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun target(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "target", "fun target()", "fun target"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val targetReference = bSource.indexOf("seed", bSource.indexOf("fun target"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, targetReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val oldDot = fixture.service.dot(fixture.service.info().graphId)
            fixture.write("B.kt", "package app\n\nfun target(): Int = 1\n")
            fixture.lsp.referencesFailure = dev.codenb.lsp.LspTransportException("references failed")
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("A.kt", "app.seed")) }
            assertEquals(EngineErrorCode.LSP_FAILURE, exception.error.code)
            assertEquals(GraphStatus(false, first.revision), fixture.service.status())
            assertEquals(
                EngineErrorCode.INVALID_GRAPH,
                code { fixture.service.node(fixture.service.info().graphId, first.node.id) },
            )
            assertEquals(oldDot, fixture.service.dot(fixture.service.info().graphId))
        }
    }

    @Test
    fun nodeGetExpandOpenAndDotAgreeAfterEdgeMetadataGrows() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun oldTarget(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "oldTarget", "fun oldTarget()", "fun oldTarget"))
            val seedIndex = aSource.indexOf("seed", aSource.indexOf("fun "))
            val seedPosition = positionAt(aSource, seedIndex)
            val oldReference = bSource.indexOf("seed", bSource.indexOf("fun oldTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, oldReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val graphId = fixture.service.info().graphId
            val seed = first.node
            val oldTarget = first.neighbours.single { it.qualifiedName == "app.oldTarget" }
            val oldTargetPosition = positionAt(bSource, bSource.indexOf("oldTarget", bSource.indexOf("fun ")))
            fixture.lsp.referencesByCall[LspCall(bUri, oldTargetPosition)] =
                listOf(locationAt(aUri, aSource, seedIndex, "seed"))
            val oldTargetBefore = fixture.service.node(graphId, oldTarget.id)
            assertContainmentClosure(oldTargetBefore, oldTarget.id)
            fixture.service.expand(graphId, oldTarget.id, first.revision)
            val oldTargetAfter = fixture.service.node(graphId, oldTarget.id)
            assertContainmentClosure(oldTargetAfter, oldTarget.id)
            val stored = fixture.service.node(graphId, seed.id)
            val expanded = fixture.service.expand(graphId, seed.id, first.revision)
            val reopened = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val dot = fixture.service.dot(graphId)
            val expectedOrigins =
                setOf(
                    dev.codenb.api.EdgeOrigin(oldTarget.id, seed.id, 1),
                    dev.codenb.api.EdgeOrigin(seed.id, oldTarget.id, 1),
                )
            listOf(stored, expanded, reopened).forEach { expansion ->
                assertContainmentClosure(expansion, seed.id)
                val edge = referenceEdge(expansion, seed.id, oldTarget.id)
                assertEquals(2, edge.count)
                assertEquals(expectedOrigins, edge.origins.toSet())
            }
            assertEquals(stored.edges.toSet(), expanded.edges.toSet())
            assertEquals(stored.edges.toSet(), reopened.edges.toSet())
            assertTrue(stored.edges.any { it.kind == EdgeKind.FILE_REFERENCES })
            assertDotMatches(dot, stored)
            assertTrue(dot.contains("label=\"2\""))
        }
    }

    private fun assertContainmentClosure(
        expansion: Expansion,
        entityId: NodeId,
    ) {
        val file =
            expansion.neighbours.single { node ->
                node.kind == NodeKind.FILE && node.fileUri == expansion.node.fileUri
            }
        val group = expansion.neighbours.single { it.id == file.parent }
        assertTrue(
            expansion.edges.any { edge ->
                edge.kind == dev.codenb.api.EdgeKind.CONTAINS &&
                    setOf(edge.from, edge.to) == setOf(entityId, file.id)
            },
        )
        assertTrue(
            expansion.edges.any { edge ->
                edge.kind == dev.codenb.api.EdgeKind.CONTAINS &&
                    setOf(edge.from, edge.to) == setOf(file.id, group.id)
            },
        )
    }

    @Test
    fun sameRevisionSelectorOpenRetriesAfterContentChange() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun oldTarget(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 1\n")
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "oldTarget", "fun oldTarget()", "fun oldTarget"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val oldReference = bSource.indexOf("seed", bSource.indexOf("fun oldTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, oldReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            var edited = false
            fixture.lsp.referencesBlock = {
                if (!edited) {
                    edited = true
                    fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 2\n")
                }
            }
            val reopened = fixture.service.open(Seed.Selector("B.kt", "app.oldTarget"))
            assertEquals("app.oldTarget", reopened.node.qualifiedName)
            assertNotEquals(first.revision, reopened.revision)
            assertTrue(fixture.service.status().open)
            assertEquals(reopened.revision, fixture.service.status().revision)
        }
    }

    @Test
    fun twoContentChangesDuringOpenLeavePriorStateUntouched() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun oldTarget(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            fixture.write("C.kt", "package app\n\nfun unrelated(): Int = 1\n")
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "oldTarget", "fun oldTarget()", "fun oldTarget"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val oldReference = bSource.indexOf("seed", bSource.indexOf("fun oldTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, oldReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val before = fixture.service.dot(fixture.service.info().graphId)
            var edits = 0
            fixture.lsp.referencesBlock = {
                edits++
                fixture.write("C.kt", "package app\n\nfun unrelated(): Int = ${edits + 10}\n")
            }
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("B.kt", "app.oldTarget")) }
            assertEquals(EngineErrorCode.INVALID_GRAPH, exception.error.code)
            assertEquals(first.revision, exception.error.details.getValue("revision"))
            assertNotEquals(
                first.revision,
                exception.error.details.getValue("currentRevision"),
            )
            val status = fixture.service.status()
            assertFalse(status.open)
            assertEquals(first.revision, status.revision)
            assertEquals(before, fixture.service.dot(fixture.service.info().graphId))
        }
    }

    @Test
    fun cacheHitAfterContentChangeIsRejected() {
        val editAction = AtomicReference<(() -> Unit)?>(null)
        val provider =
            ContentRevisionProvider { base, paths ->
                val revision = ContentRevisions.hash(base, paths)
                editAction.getAndSet(null)?.invoke()
                revision
            }
        withFixture(revisionProvider = provider) { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun target(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "target", "fun target()", "fun target"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val targetReference = bSource.indexOf("seed", bSource.indexOf("fun target"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, targetReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            editAction.set {
                fixture.write("B.kt", "package app\n\nfun target(): Int = 1\n")
            }
            val exception =
                assertFailsWith<EngineException> {
                    fixture.service.expand(fixture.service.info().graphId, first.node.id, first.revision)
                }
            assertEquals(EngineErrorCode.INVALID_GRAPH, exception.error.code)
            assertEquals(first.revision, exception.error.details.getValue("revision"))
            assertNotEquals(first.revision, exception.error.details.getValue("currentRevision"))
        }
    }

    @Test
    fun nodeGetFailsAfterInterruptedClose() {
        val blocked = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider =
            ContentRevisionProvider { base, paths ->
                val revision = ContentRevisions.hash(base, paths)
                if (blocked.get()) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                revision
            }
        withFixture(revisionProvider = provider) { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            val path = fixture.write("A.kt", source)
            fixture.lsp.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            blocked.set(true)
            val failure = AtomicReference<Throwable?>()
            val nodeThread =
                Thread(
                    {
                        try {
                            fixture.service.node(fixture.service.info().graphId, first.node.id)
                        } catch (exception: Throwable) {
                            failure.set(exception)
                        }
                    },
                    "codenb-blocked-node-get",
                )
            nodeThread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closeThread = Thread({ fixture.service.close() }, "codenb-close-node-get")
            closeThread.start()
            Thread.sleep(100)
            closeThread.interrupt()
            closeThread.join(3000)
            release.countDown()
            nodeThread.join(3000)
            assertFalse(closeThread.isAlive)
            assertFalse(nodeThread.isAlive)
            val exception = failure.get()
            assertTrue(exception is EngineException)
            assertEquals(EngineErrorCode.INVALID_GRAPH, exception.error.code)
            assertFalse(fixture.service.status().open)
        }
    }

    @Test
    fun interruptedCloseDoesNotAdoptANewClient() {
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "codenb-close-client")
        val source = "package app\n\nfun seed(): Int = 1\n"
        val path = workspace.resolve("A.kt")
        Files.writeString(path, source)
        val fake = FakeGraphLsp()
        fake.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val service =
            RealGraphService(
                root = workspace,
                frontend = KotlinFrontend.descriptor,
                config = EngineConfigLoader.defaults(),
                clientFactory = { _, _ ->
                    factoryEntered.countDown()
                    releaseFactory.await(5, TimeUnit.SECONDS)
                    fake
                },
            )
        try {
            val failure = AtomicReference<Throwable?>()
            val openThread =
                Thread(
                    {
                        try {
                            service.open(Seed.Selector("A.kt", "app.seed"))
                        } catch (exception: Throwable) {
                            failure.set(exception)
                        }
                    },
                    "codenb-blocking-factory-open",
                )
            openThread.start()
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))
            val closeThread = Thread({ service.close() }, "codenb-blocking-factory-close")
            closeThread.start()
            Thread.sleep(100)
            closeThread.interrupt()
            closeThread.join(3000)
            releaseFactory.countDown()
            openThread.join(5000)
            assertFalse(closeThread.isAlive)
            assertTrue(fake.closed)
            val exception = failure.get()
            assertTrue(exception is EngineException)
            assertEquals(EngineErrorCode.INVALID_GRAPH, exception.error.code)
        } finally {
            releaseFactory.countDown()
            service.close()
            deleteTree(workspace)
        }
    }

    @Test
    fun fullProjectionIncludesAggregationEdgesAndNeighbourClosure() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app.b\n\nfun b(): Int = 1\n"
            val cSource = "package app.c\n\nfun c(): Int = 1\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val cPath = fixture.write("C.kt", cSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            val cUri = cPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "b", "fun b()", "fun b"))
            fixture.lsp.symbols[cUri] = listOf(functionSymbol(cSource, "c", "fun c()", "fun c"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val bPosition = positionAt(bSource, bSource.indexOf("b", bSource.indexOf("fun ")))
            val cPosition = positionAt(cSource, cSource.indexOf("c", cSource.indexOf("fun ")))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(
                    locationAt(bUri, bSource, bSource.indexOf("b", bSource.indexOf("fun ")), "b"),
                    locationAt(cUri, cSource, cSource.indexOf("c", cSource.indexOf("fun ")), "c"),
                )
            fixture.lsp.referencesByCall[LspCall(bUri, bPosition)] =
                listOf(locationAt(cUri, cSource, cSource.indexOf("c", cSource.indexOf("fun ")), "c"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertTrue(first.edges.any { it.kind == dev.codenb.api.EdgeKind.FILE_REFERENCES })
            assertTrue(first.edges.any { it.kind == dev.codenb.api.EdgeKind.GROUP_REFERENCES })
            val graphId = fixture.service.info().graphId
            val b = first.neighbours.single { it.qualifiedName == "app.b.b" }
            fixture.service.expand(graphId, b.id, first.revision)
            val stored = fixture.service.node(graphId, first.node.id)
            val expanded = fixture.service.expand(graphId, first.node.id, first.revision)
            val reopened = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertEquals(stored.edges.toSet(), expanded.edges.toSet())
            assertEquals(stored.edges.toSet(), reopened.edges.toSet())
            val fileA = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == aUri }
            val fileB = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == bUri }
            val fileC = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == cUri }
            val groupA = stored.neighbours.single { it.id == fileA.parent }
            val groupB = stored.neighbours.single { it.id == fileB.parent }
            val groupC = stored.neighbours.single { it.id == fileC.parent }
            val seed = stored.node
            val c = stored.neighbours.single { it.qualifiedName == "app.c.c" }
            listOf(
                edgeBetween(stored, dev.codenb.api.EdgeKind.REFERENCES, seed.id, b.id),
                edgeBetween(stored, dev.codenb.api.EdgeKind.REFERENCES, seed.id, c.id),
                edgeBetween(stored, dev.codenb.api.EdgeKind.REFERENCES, b.id, c.id),
                edgeBetween(stored, dev.codenb.api.EdgeKind.FILE_REFERENCES, fileA.id, fileB.id),
                edgeBetween(stored, dev.codenb.api.EdgeKind.FILE_REFERENCES, fileA.id, fileC.id),
                edgeBetween(stored, dev.codenb.api.EdgeKind.FILE_REFERENCES, fileB.id, fileC.id),
                edgeBetween(stored, dev.codenb.api.EdgeKind.GROUP_REFERENCES, groupA.id, groupB.id),
                edgeBetween(stored, dev.codenb.api.EdgeKind.GROUP_REFERENCES, groupA.id, groupC.id),
                edgeBetween(stored, dev.codenb.api.EdgeKind.GROUP_REFERENCES, groupB.id, groupC.id),
            )
            val dot = fixture.service.dot(graphId)
            assertDotMatches(dot, stored)
            assertTrue(dot.contains("kind=\"FILE_REFERENCES\""))
            assertTrue(dot.contains("kind=\"GROUP_REFERENCES\""))
        }
    }

    @Test
    fun firstAndChangedRevisionOpenMatchProjectionAndDotEdges() {
        withFixture { fixture ->
            val aSource = "package app.a\n\nfun seed(): Int = 1\n"
            val bSource = "package app.b\n\nfun b(): Int = 1\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            fixture.write("D.kt", "package app\n\nfun unrelated(): Int = 1\n")
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "b", "fun b()", "fun b"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, bSource.indexOf("b", bSource.indexOf("fun ")), "b"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            fixture.write("D.kt", "package app\n\nfun unrelated(): Int = 2\n")
            val changed = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            val graphId = fixture.service.info().graphId
            val stored = fixture.service.node(graphId, changed.node.id)
            val expanded = fixture.service.expand(graphId, changed.node.id, changed.revision)
            val reopened = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            assertEquals(first.edges.toSet(), changed.edges.toSet())
            assertEquals(changed.edges.toSet(), stored.edges.toSet())
            assertEquals(changed.edges.toSet(), expanded.edges.toSet())
            assertEquals(changed.edges.toSet(), reopened.edges.toSet())
            assertDotMatches(fixture.service.dot(graphId), changed)
        }
    }

    @Test
    fun fileAndGroupAggregationSumsCollidingEntityReferences() {
        withFixture { fixture ->
            val aSource =
                """
                package app.a

                fun seed(): Int = 1

                fun sibling(): Int = 2
                """.trimIndent()
            val bSource =
                """
                package app.b

                fun one(): Int = 1

                fun two(): Int = 2
                """.trimIndent()
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            val siblingStart = aSource.indexOf("fun sibling")
            fixture.lsp.symbols[aUri] =
                listOf(
                    functionSymbol(aSource, "seed", "fun seed()", "fun seed"),
                    functionSymbol(aSource, "sibling", "fun sibling()", "fun sibling", siblingStart),
                )
            fixture.lsp.symbols[bUri] =
                listOf(
                    functionSymbol(bSource, "one", "fun one()", "fun one"),
                    functionSymbol(bSource, "two", "fun two()", "fun two", bSource.indexOf("fun two")),
                )
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val siblingPosition = positionAt(aSource, aSource.indexOf("sibling", siblingStart))
            val onePosition = bSource.indexOf("one", bSource.indexOf("fun "))
            val twoPosition = bSource.indexOf("two", bSource.indexOf("fun two"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(
                    locationAt(bUri, bSource, onePosition, "one"),
                    locationAt(bUri, bSource, twoPosition, "two"),
                    locationAt(aUri, aSource, aSource.indexOf("sibling", siblingStart), "sibling"),
                )
            fixture.lsp.referencesByCall[LspCall(aUri, siblingPosition)] =
                listOf(locationAt(bUri, bSource, onePosition, "one"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            val sibling = first.neighbours.single { it.qualifiedName == "app.a.sibling" }
            val graphId = fixture.service.info().graphId
            fixture.service.expand(graphId, sibling.id, first.revision)
            val stored = fixture.service.node(graphId, first.node.id)
            val expanded = fixture.service.expand(graphId, first.node.id, first.revision)
            val reopened = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            assertEquals(stored.edges.toSet(), expanded.edges.toSet())
            assertEquals(stored.edges.toSet(), reopened.edges.toSet())
            val fileA = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == aUri }
            val fileB = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == bUri }
            val groupA = stored.neighbours.single { it.id == fileA.parent }
            val groupB = stored.neighbours.single { it.id == fileB.parent }
            val fileEdge = edgeBetween(stored, EdgeKind.FILE_REFERENCES, fileA.id, fileB.id)
            assertEquals(3, fileEdge.count)
            assertEquals(setOf(EdgeOrigin(fileB.id, fileA.id, 3)), fileEdge.origins.toSet())
            val groupEdge = edgeBetween(stored, EdgeKind.GROUP_REFERENCES, groupA.id, groupB.id)
            assertEquals(3, groupEdge.count)
            assertEquals(setOf(EdgeOrigin(groupB.id, groupA.id, 3)), groupEdge.origins.toSet())
            val dot = fixture.service.dot(graphId)
            assertDotMatches(dot, stored)
            val dotEdges = parseDotEdges(dot)
            val fileDotEdge =
                dotEdges.single { edge ->
                    edge.kind == EdgeKind.FILE_REFERENCES && setOf(edge.from, edge.to) == setOf(fileA.id, fileB.id)
                }
            assertEquals(3, fileDotEdge.count)
            val groupDotEdge =
                dotEdges.single { edge ->
                    edge.kind == EdgeKind.GROUP_REFERENCES && setOf(edge.from, edge.to) == setOf(groupA.id, groupB.id)
                }
            assertEquals(3, groupDotEdge.count)
            assertTrue(stored.edges.none { it.from == it.to })
        }
    }

    @Test
    fun projectionAggregatesReferencesFromIncludedFiles() {
        withFixture { fixture ->
            val aSource = "package app.a\n\nfun seed(): Int = 1\n"
            val bSource =
                """
                package app.b

                fun b1(): Int = 1

                fun b2(): Int = 2
                """.trimIndent()
            val cSource =
                """
                package app.c

                fun c1(): Int = 1

                fun c2(): Int = 2
                """.trimIndent()
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val cPath = fixture.write("C.kt", cSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            val cUri = cPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] =
                listOf(
                    functionSymbol(bSource, "b1", "fun b1()", "fun b1"),
                    functionSymbol(bSource, "b2", "fun b2()", "fun b2", bSource.indexOf("fun b2")),
                )
            fixture.lsp.symbols[cUri] =
                listOf(
                    functionSymbol(cSource, "c1", "fun c1()", "fun c1"),
                    functionSymbol(cSource, "c2", "fun c2()", "fun c2", cSource.indexOf("fun c2")),
                )
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val b2Position = positionAt(bSource, bSource.indexOf("b2", bSource.indexOf("fun b2")))
            val b1Index = bSource.indexOf("b1", bSource.indexOf("fun "))
            val c1Index = cSource.indexOf("c1", cSource.indexOf("fun "))
            val c2Index = cSource.indexOf("c2", cSource.indexOf("fun c2"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(
                    locationAt(bUri, bSource, b1Index, "b1"),
                    locationAt(cUri, cSource, c1Index, "c1"),
                )
            fixture.lsp.referencesByCall[LspCall(bUri, b2Position)] =
                listOf(locationAt(cUri, cSource, c2Index, "c2"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            val graphId = fixture.service.info().graphId
            val b2Expansion = fixture.service.open(Seed.Selector("B.kt", "app.b.b2"))
            val c2 = b2Expansion.neighbours.single { it.qualifiedName == "app.c.c2" }
            val stored = fixture.service.node(graphId, first.node.id)
            val expanded = fixture.service.expand(graphId, first.node.id, first.revision)
            val reopened = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            val storedAggregation = aggregationEdges(stored)
            assertEquals(storedAggregation, aggregationEdges(expanded))
            assertEquals(storedAggregation, aggregationEdges(reopened))
            assertFalse(
                stored.edges.any { edge ->
                    edge.kind == EdgeKind.REFERENCES &&
                        setOf(edge.from, edge.to) == setOf(b2Expansion.node.id, c2.id)
                },
            )
            val fileB = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == bUri }
            val fileC = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == cUri }
            val groupB = stored.neighbours.single { it.id == fileB.parent }
            val groupC = stored.neighbours.single { it.id == fileC.parent }
            val fileEdge = edgeBetween(stored, EdgeKind.FILE_REFERENCES, fileB.id, fileC.id)
            assertEquals(1, fileEdge.count)
            assertEquals(setOf(EdgeOrigin(fileC.id, fileB.id, 1)), fileEdge.origins.toSet())
            val groupEdge = edgeBetween(stored, EdgeKind.GROUP_REFERENCES, groupB.id, groupC.id)
            assertEquals(1, groupEdge.count)
            assertEquals(setOf(EdgeOrigin(groupC.id, groupB.id, 1)), groupEdge.origins.toSet())
            val dotAggregation =
                parseDotEdges(fixture.service.dot(graphId))
                    .filter { it.kind == EdgeKind.FILE_REFERENCES || it.kind == EdgeKind.GROUP_REFERENCES }
                    .map { dotEdge -> Triple(dotEdge.kind, setOf(dotEdge.from, dotEdge.to), dotEdge.count) }
                    .toSet()
            val storedDotForm =
                storedAggregation
                    .map { edge -> Triple(edge.kind, setOf(edge.from, edge.to), edge.count) }
                    .toSet()
            assertEquals(storedDotForm, dotAggregation)
        }
    }

    @Test
    fun projectionDoesNotAggregateOutsideProjectedFiles() {
        withFixture { fixture ->
            val aSource = "package app.a\n\nfun seed(): Int = 1\n"
            val a2Source = "package app.a\n\nfun helper(): Int = 1\n"
            val bSource = "package app.b\n\nfun b1(): Int = 1\n"
            val b2Source = "package app.b\n\nfun helper2(): Int = 1\n"
            val aPath = fixture.write("A.kt", aSource)
            val a2Path = fixture.write("A2.kt", a2Source)
            val bPath = fixture.write("B.kt", bSource)
            val b2Path = fixture.write("B2.kt", b2Source)
            val aUri = aPath.toUri().toString()
            val a2Uri = a2Path.toUri().toString()
            val bUri = bPath.toUri().toString()
            val b2Uri = b2Path.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[a2Uri] = listOf(functionSymbol(a2Source, "helper", "fun helper()", "fun helper"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "b1", "fun b1()", "fun b1"))
            fixture.lsp.symbols[b2Uri] = listOf(functionSymbol(b2Source, "helper2", "fun helper2()", "fun helper2"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val helperPosition = positionAt(a2Source, a2Source.indexOf("helper", a2Source.indexOf("fun ")))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, bSource.indexOf("b1", bSource.indexOf("fun ")), "b1"))
            fixture.lsp.referencesByCall[LspCall(a2Uri, helperPosition)] =
                listOf(locationAt(b2Uri, b2Source, b2Source.indexOf("helper2", b2Source.indexOf("fun ")), "helper2"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            val graphId = fixture.service.info().graphId
            fixture.service.open(Seed.Selector("A2.kt", "app.a.helper"))
            val stored = fixture.service.node(graphId, first.node.id)
            val expanded = fixture.service.expand(graphId, first.node.id, first.revision)
            val reopened = fixture.service.open(Seed.Selector("A.kt", "app.a.seed"))
            assertEquals(aggregationEdges(stored), aggregationEdges(expanded))
            assertEquals(aggregationEdges(stored), aggregationEdges(reopened))
            assertTrue(stored.neighbours.none { it.fileUri == a2Uri || it.fileUri == b2Uri })
            val fileA = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == aUri }
            val fileB = stored.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == bUri }
            val groupA = stored.neighbours.single { it.id == fileA.parent }
            val groupB = stored.neighbours.single { it.id == fileB.parent }
            val fileEdge = edgeBetween(stored, EdgeKind.FILE_REFERENCES, fileA.id, fileB.id)
            assertEquals(1, fileEdge.count)
            val groupEdge = edgeBetween(stored, EdgeKind.GROUP_REFERENCES, groupA.id, groupB.id)
            assertEquals(1, groupEdge.count)
            assertEquals(setOf(EdgeOrigin(groupB.id, groupA.id, 1)), groupEdge.origins.toSet())
            assertGroupAggregationDerivable(stored)
            val dotEdges = parseDotEdges(fixture.service.dot(graphId))
            val dotGroup =
                dotEdges.single { edge ->
                    edge.kind == EdgeKind.GROUP_REFERENCES && setOf(edge.from, edge.to) == setOf(groupA.id, groupB.id)
                }
            assertEquals(2, dotGroup.count)
            assertTrue(
                dotEdges.any { edge ->
                    edge.kind == EdgeKind.FILE_REFERENCES &&
                        setOf(edge.from, edge.to) != setOf(fileA.id, fileB.id) &&
                        edge.count == 1
                },
            )
        }
    }

    private fun assertGroupAggregationDerivable(expansion: Expansion) {
        val filesById = expansion.neighbours.filter { it.kind == NodeKind.FILE }.associateBy { it.id }
        val fileEdges = expansion.edges.filter { it.kind == EdgeKind.FILE_REFERENCES }
        val groupEdges = expansion.edges.filter { it.kind == EdgeKind.GROUP_REFERENCES }
        if (groupEdges.isEmpty()) {
            return
        }
        assertTrue(fileEdges.isNotEmpty())
        groupEdges.forEach { groupEdge ->
            val expected =
                fileEdges
                    .filter { fileEdge ->
                        filesById[fileEdge.from]?.parent in setOf(groupEdge.from, groupEdge.to) &&
                            filesById[fileEdge.to]?.parent in setOf(groupEdge.from, groupEdge.to) &&
                            setOf(filesById[fileEdge.from]?.parent, filesById[fileEdge.to]?.parent) ==
                            setOf(groupEdge.from, groupEdge.to)
                    }.sumOf { it.count }
            assertEquals(expected, groupEdge.count)
        }
    }

    private fun aggregationEdges(expansion: Expansion): Set<dev.codenb.api.Edge> =
        expansion.edges
            .filter { it.kind == EdgeKind.FILE_REFERENCES || it.kind == EdgeKind.GROUP_REFERENCES }
            .toSet()

    private fun edgeBetween(
        expansion: Expansion,
        kind: EdgeKind,
        a: NodeId,
        b: NodeId,
    ): dev.codenb.api.Edge =
        expansion.edges.single { edge ->
            edge.kind == kind && setOf(edge.from, edge.to) == setOf(a, b)
        }

    private data class DotEdge(
        val from: NodeId,
        val to: NodeId,
        val kind: EdgeKind,
        val count: Int,
    )

    private fun parseDotEdges(dot: String): List<DotEdge> =
        DOT_EDGE
            .findAll(dot)
            .map { match ->
                DotEdge(
                    from = NodeId(match.groupValues[1]),
                    to = NodeId(match.groupValues[2]),
                    kind = EdgeKind.valueOf(match.groupValues[3]),
                    count = match.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 1,
                )
            }.toList()

    private fun assertDotMatches(
        dot: String,
        expansion: Expansion,
    ) {
        val dotEdges = parseDotEdges(dot)
        assertEquals(expansion.edges.size, dotEdges.size)
        assertEquals(dotEdges.size, dotEdges.map { listOf(it.kind, it.from, it.to) }.toSet().size)
        assertTrue(dotEdges.none { it.from == it.to })
        expansion.edges.forEach { edge ->
            val matches =
                dotEdges.filter { dotEdge ->
                    dotEdge.kind == edge.kind &&
                        setOf(dotEdge.from, dotEdge.to) == setOf(edge.from, edge.to)
                }
            assertEquals(1, matches.size)
            assertEquals(edge.count, matches.single().count)
        }
    }

    private companion object {
        val DOT_EDGE =
            Regex("""(?m)^\s+"([^"]+)" -- "([^"]+)" \[kind="([^"]+)"(?:, label="([^"]*)")?\];\s*$""")
    }

    private fun referenceEdge(
        expansion: Expansion,
        a: NodeId,
        b: NodeId,
    ): dev.codenb.api.Edge =
        expansion.edges.single { edge ->
            edge.kind == dev.codenb.api.EdgeKind.REFERENCES &&
                ((edge.from == a && edge.to == b) || (edge.from == b && edge.to == a))
        }

    @Test
    fun reopenByNodeIdUsesTheCacheWithoutQueryingLsp() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun target(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "target", "fun target()", "fun target"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val targetReference = bSource.indexOf("seed", bSource.indexOf("fun target"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, targetReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val queries = fixture.lsp.queryCount.get()
            fixture.lsp.queryFailure = IllegalStateException("LSP must not be queried")
            val second = fixture.service.open(Seed.ByNodeId(first.node.id))
            assertEquals(first.node.id, second.node.id)
            assertEquals(queries, fixture.lsp.queryCount.get())
        }
    }

    @Test
    fun fileAndGroupExpansionIsExplicitlyUnsupported() {
        withFixture { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            val path = fixture.write("A.kt", source)
            fixture.lsp.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
            val service = fixture.service
            val expansion = service.open(Seed.Selector("A.kt", "app.seed"))
            val file = expansion.neighbours.single { it.kind == NodeKind.FILE }
            val group = expansion.neighbours.single { it.kind == NodeKind.GROUP }
            assertEquals(listOf(NodeKind.ENTITY), service.expandableNodeKinds())
            assertEquals(NodeKind.entries, service.info().nodeKinds)
            assertEquals(
                EngineErrorCode.UNSUPPORTED_CAPABILITY,
                code { service.expand(service.info().graphId, file.id, expansion.revision) },
            )
            assertEquals(
                EngineErrorCode.UNSUPPORTED_CAPABILITY,
                code { service.expand(service.info().graphId, group.id, expansion.revision) },
            )
            assertEquals(
                EngineErrorCode.UNSUPPORTED_CAPABILITY,
                code { service.open(Seed.ByNodeId(file.id)) },
            )
        }
    }

    @Test
    fun serializesLspCallsAcrossConcurrentExpansions() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun first(): Int = seed()\n\nfun second(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] =
                listOf(
                    functionSymbol(bSource, "first", "fun first()", "fun first"),
                    functionSymbol(bSource, "second", "fun second()", "fun second", bSource.indexOf("fun second")),
                )
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val firstReference = bSource.indexOf("seed", bSource.indexOf("fun first"))
            val secondReference = bSource.indexOf("seed", bSource.indexOf("fun second"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(
                    locationAt(bUri, bSource, firstReference, "seed"),
                    locationAt(bUri, bSource, secondReference, "seed"),
                )
            val service = fixture.service
            val expansion = service.open(Seed.Selector("A.kt", "app.seed"))
            val first = expansion.neighbours.single { it.qualifiedName == "app.first" }
            val second = expansion.neighbours.single { it.qualifiedName == "app.second" }
            fixture.lsp.callDelayMillis = 15
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf(first, second).map { node ->
                        executor.submit(
                            Callable {
                                start.await()
                                service.expand(service.info().graphId, node.id, expansion.revision)
                                true
                            },
                        )
                    }
                start.countDown()
                futures.forEach { future -> assertTrue(future.get(20, TimeUnit.SECONDS)) }
            } finally {
                executor.shutdownNow()
            }
            assertEquals(1, fixture.lsp.maxConcurrentCalls.get())
        }
    }

    @Test
    fun invariantFailureMapsToInternal() {
        withFixture { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            val path = fixture.write("A.kt", source)
            fixture.lsp.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
            fixture.lsp.documentSymbolFailure = IllegalStateException("broken invariant")
            val exception = assertFailsWith<EngineException> { fixture.service.open(Seed.Selector("A.kt", "app.seed")) }
            assertEquals(EngineErrorCode.INTERNAL, exception.error.code)
        }
    }

    @Test
    fun statusAndGraphChecksAreConsistentAfterClose() {
        withFixture { fixture ->
            val service = fixture.service
            val graphId = service.info().graphId
            assertEquals(GraphStatus(false, null), service.status())
            assertEquals(EngineErrorCode.INVALID_GRAPH, code { service.dot(graphId) })
            assertEquals(EngineErrorCode.INVALID_GRAPH, code { service.node(graphId, NodeId("node")) })
            assertEquals(EngineErrorCode.INVALID_GRAPH, code { service.expand(graphId, NodeId("node"), "rev") })
            service.close()
            service.close()
            assertEquals(GraphStatus(false, null), service.status())
        }
    }

    @Test
    fun closeIsInterruptibleWhileExpansionHoldsTheServiceLock() {
        withFixture { fixture ->
            val source = "package app\n\nfun seed(): Int = 1\n"
            val path = fixture.write("A.kt", source)
            fixture.lsp.symbols[path.toUri().toString()] = listOf(functionSymbol(source, "seed", "fun seed()", "fun seed"))
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.lsp.referencesBlock = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val openThread = Thread({ runCatching { fixture.service.open(Seed.Selector("A.kt", "app.seed")) } }, "codenb-open")
            openThread.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val closeThread = Thread({ fixture.service.close() }, "codenb-close")
            closeThread.start()
            Thread.sleep(100)
            closeThread.interrupt()
            closeThread.join(3000)
            assertFalse(closeThread.isAlive)
            assertTrue(fixture.lsp.closed)
            release.countDown()
            openThread.join(3000)
        }
    }

    @Test
    fun expandAfterEditBeforeReopenIsRejectedWithoutMutatingTheGraph() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun oldTarget(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "oldTarget", "fun oldTarget()", "fun oldTarget"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val oldReference = bSource.indexOf("seed", bSource.indexOf("fun oldTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, oldReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val graphId = fixture.service.info().graphId
            val oldTarget = first.neighbours.single { it.qualifiedName == "app.oldTarget" }
            val before = fixture.service.dot(graphId)
            fixture.write("B.kt", "package app\n\nfun newTarget(): Int = seed()\n")
            val seedFailure =
                assertFailsWith<EngineException> {
                    fixture.service.expand(graphId, first.node.id, first.revision)
                }
            assertEquals(EngineErrorCode.INVALID_GRAPH, seedFailure.error.code)
            assertEquals(first.revision, seedFailure.error.details.getValue("revision"))
            assertNotEquals(first.revision, seedFailure.error.details.getValue("currentRevision"))
            val neighbourFailure =
                assertFailsWith<EngineException> {
                    fixture.service.expand(graphId, oldTarget.id, first.revision)
                }
            assertEquals(EngineErrorCode.INVALID_GRAPH, neighbourFailure.error.code)
            assertEquals(before, fixture.service.dot(graphId))
            assertEquals(
                EngineErrorCode.INVALID_GRAPH,
                code { fixture.service.node(graphId, oldTarget.id) },
            )
        }
    }

    @Test
    fun unchangedRevisionSelectorOpenUsesTheStoreExpansion() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun oldTarget(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "oldTarget", "fun oldTarget()", "fun oldTarget"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val oldReference = bSource.indexOf("seed", bSource.indexOf("fun oldTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, oldReference, "seed"))
            val first = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            val queries = fixture.lsp.queryCount.get()
            val newTargetSource = "package app\n\nfun newTarget(): Int = seed()\n"
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(newTargetSource, "newTarget", "fun newTarget()", "fun newTarget"))
            val reopened = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertEquals(queries, fixture.lsp.queryCount.get())
            assertFalse(reopened.neighbours.any { it.qualifiedName == "app.newTarget" })
            val stored = fixture.service.node(fixture.service.info().graphId, first.node.id)
            assertEquals(reopened, stored)
            val expanded = fixture.service.expand(fixture.service.info().graphId, first.node.id, first.revision)
            assertEquals(reopened, expanded)
            fixture.lsp.queryFailure = IllegalStateException("LSP must not be queried")
            val cached = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertEquals(first.revision, cached.revision)
            assertEquals(queries, fixture.lsp.queryCount.get())
        }
    }

    @Test
    fun openRetriesWhenWorkspaceChangesDuringExpansion() {
        withFixture { fixture ->
            val aSource = "package app\n\nfun seed(): Int = 1\n"
            val bSource = "package app\n\nfun oldTarget(): Int = seed()\n"
            val aPath = fixture.write("A.kt", aSource)
            val bPath = fixture.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            fixture.lsp.symbols[aUri] = listOf(functionSymbol(aSource, "seed", "fun seed()", "fun seed"))
            fixture.lsp.symbols[bUri] = listOf(functionSymbol(bSource, "oldTarget", "fun oldTarget()", "fun oldTarget"))
            val seedPosition = positionAt(aSource, aSource.indexOf("seed", aSource.indexOf("fun ")))
            val oldReference = bSource.indexOf("seed", bSource.indexOf("fun oldTarget"))
            fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                listOf(locationAt(bUri, bSource, oldReference, "seed"))
            var firstCall = true
            fixture.lsp.referencesBlock = {
                if (firstCall) {
                    firstCall = false
                    val newSource = "package app\n\nfun newTarget(): Int = seed()\n"
                    fixture.write("B.kt", newSource)
                    fixture.lsp.symbols[bUri] = listOf(functionSymbol(newSource, "newTarget", "fun newTarget()", "fun newTarget"))
                    val newReference = newSource.indexOf("seed", newSource.indexOf("fun newTarget"))
                    fixture.lsp.referencesByCall[LspCall(aUri, seedPosition)] =
                        listOf(locationAt(bUri, newSource, newReference, "seed"))
                }
            }
            val expansion = fixture.service.open(Seed.Selector("A.kt", "app.seed"))
            assertFalse(expansion.neighbours.any { it.qualifiedName == "app.oldTarget" })
            assertTrue(expansion.neighbours.any { it.qualifiedName == "app.newTarget" })
            assertEquals(expansion.revision, fixture.service.status().revision)
            assertTrue(fixture.service.status().open)
        }
    }

    private fun code(block: () -> Unit): EngineErrorCode {
        val exception = assertFailsWith<EngineException> { block() }
        return exception.error.code
    }

    private fun withFixture(
        config: EngineConfig = EngineConfigLoader.defaults(),
        revisionProvider: ContentRevisionProvider =
            ContentRevisionProvider { base, paths -> ContentRevisions.hash(base, paths) },
        commitGate: CommitGate = CommitGate {},
        block: (Fixture) -> Unit,
    ) {
        val fixture = Fixture(config, revisionProvider, commitGate)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        config: EngineConfig,
        revisionProvider: ContentRevisionProvider,
        commitGate: CommitGate,
    ) : AutoCloseable {
        val workspace: Path = Files.createTempDirectory(Path.of("/tmp"), "codenb-ws")
        val lsp = FakeGraphLsp()
        val service =
            RealGraphService(
                root = workspace,
                frontend = KotlinFrontend.descriptor,
                config = config,
                clientFactory = { _, _ ->
                    lsp.factoryCalls.incrementAndGet()
                    lsp
                },
                revisionProvider = revisionProvider,
                commitGate = commitGate,
            )

        fun write(
            relative: String,
            text: String,
        ): Path {
            val path = workspace.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            return path
        }

        override fun close() {
            service.close()
            deleteTree(workspace)
        }
    }
}
