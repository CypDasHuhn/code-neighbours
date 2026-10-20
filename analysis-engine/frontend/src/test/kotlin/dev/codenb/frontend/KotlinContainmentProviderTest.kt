package dev.codenb.frontend

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.GroupingKind
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.core.GroupingPolicy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KotlinContainmentProviderTest {
    private val graphId = GraphId("graph")

    @Test
    fun extractsTheKotlinPackageAndNormalizedRelativePath() {
        withWorkspace { workspace ->
            val source =
                """
                @file:Suppress("unused")

                package app.order

                class Order
                """.trimIndent()
            val path = workspace.write("src/main/kotlin/app/Order.kt", source)
            val uri = path.toUri().toString()

            val location = KotlinContainmentProvider.location(workspace.root, uri, source)

            assertEquals(uri, location.fileUri)
            assertEquals("src/main/kotlin/app/Order.kt", location.relativePath)
            assertEquals("app.order", location.namespace)
            assertEquals("src/main/kotlin/app/Order.kt", KotlinContainmentProvider.relativePath(workspace.root, uri))
        }
    }

    @Test
    fun normalizesRedundantPathSegments() {
        withWorkspace { workspace ->
            val source = "package app\n\nclass App"
            workspace.write("src/App.kt", source)
            val uri =
                workspace.root
                    .resolve("src")
                    .resolve("..")
                    .resolve("src")
                    .resolve("App.kt")
                    .toUri()
                    .toString()

            val location = KotlinContainmentProvider.location(workspace.root, uri, source)

            assertEquals("src/App.kt", location.relativePath)
            assertTrue(!location.relativePath.contains('\\'))
        }
    }

    @Test
    fun rejectsPathsOutsideTheWorkspaceRoot() {
        withWorkspace { workspace ->
            val outside = Files.createTempDirectory("containment-outside")
            try {
                val path = outside.resolve("Outside.kt")
                Files.writeString(path, "package outside", StandardCharsets.UTF_8)
                val uri = path.toUri().toString()

                assertFailsWith<IllegalArgumentException> {
                    KotlinContainmentProvider.location(workspace.root, uri, "package outside")
                }
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun rejectsNonFileUris() {
        withWorkspace { workspace ->
            assertFailsWith<IllegalArgumentException> {
                KotlinContainmentProvider.location(workspace.root, "https://example.com/App.kt", "package app")
            }
            assertFailsWith<IllegalArgumentException> {
                KotlinContainmentProvider.location(workspace.root, "untitled:App.kt", "package app")
            }
        }
    }

    @Test
    fun mapsGroupingModesToTheApiGroupingKinds() {
        assertEquals(GroupingKind.NAMESPACE, KotlinContainmentProvider.groupingKind(GroupingMode.NAMESPACE))
        assertEquals(GroupingKind.DIRECTORY, KotlinContainmentProvider.groupingKind(GroupingMode.DIRECTORY))
        assertEquals(GroupingKind.NAMESPACE, GroupingMode.NAMESPACE.toGroupingKind())
        assertEquals(GroupingKind.DIRECTORY, GroupingMode.DIRECTORY.toGroupingKind())
    }

    @Test
    fun pipelineAppliesContainmentToAnEntityExpansion() {
        withWorkspace { workspace ->
            val firstSource = "package app.alpha\n\nfun first(): Int = 1"
            val secondSource = "package app.beta\n\nfun second(): Int = 2"
            val firstPath = workspace.write("src/a/First.kt", firstSource)
            val secondPath = workspace.write("src/b/Second.kt", secondSource)
            val firstUri = firstPath.toUri().toString()
            val secondUri = secondPath.toUri().toString()
            val first = entity(firstUri, "app.alpha.first")
            val second = entity(secondUri, "app.beta.second")
            val reference =
                Edge.of(
                    EdgeKind.REFERENCES,
                    first.id,
                    second.id,
                    2,
                    listOf(EdgeOrigin(first.id, second.id, 2)),
                )
            val expansion = Expansion(graphId, "revision", first, listOf(second), listOf(reference))
            val expansionWithFiles =
                ExpansionWithFiles(
                    expansion,
                    mapOf(firstUri to firstSource, secondUri to secondSource),
                )

            val result =
                ContainmentPipeline.apply(
                    expansionWithFiles,
                    workspace.root,
                    GroupingMode.NAMESPACE,
                    GroupingPolicy(),
                    KotlinContainmentProvider,
                )

            val firstFile = result.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == firstUri }
            val secondFile = result.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == secondUri }
            val firstGroup = result.neighbours.single { it.kind == NodeKind.GROUP && it.label == "app.alpha" }
            val secondGroup = result.neighbours.single { it.kind == NodeKind.GROUP && it.label == "app.beta" }
            val fileReference = result.edges.single { it.kind == EdgeKind.FILE_REFERENCES }
            val groupReference = result.edges.single { it.kind == EdgeKind.GROUP_REFERENCES }

            assertEquals(2, fileReference.count)
            assertEquals(2, groupReference.count)
            assertEquals(firstFile.id, result.node.parent)
            assertEquals(secondFile.id, result.neighbours.single { it.id == second.id }.parent)
            assertEquals(firstGroup.id, firstFile.parent)
            assertEquals(secondGroup.id, secondFile.parent)
            assertEquals(mapOf((firstFile.id to secondFile.id) to 2), fileReference.origins.associate { (it.from to it.to) to it.count })
            assertEquals(mapOf((firstGroup.id to secondGroup.id) to 2), groupReference.origins.associate { (it.from to it.to) to it.count })
        }
    }

    @Test
    fun directoryGroupingNeedsNoTextsAndDoesNotCallTheNamespaceProvider() {
        withWorkspace { workspace ->
            val first =
                entity(
                    workspace.root
                        .resolve("src/a/First.kt")
                        .toUri()
                        .toString(),
                    "First",
                )
            val second =
                entity(
                    workspace.root
                        .resolve("src/b/Second.kt")
                        .toUri()
                        .toString(),
                    "Second",
                )
            val expansion = Expansion(graphId, "revision", first, listOf(second), emptyList())
            val provider = NamespaceProvider { error("Namespace provider must not be called") }

            val result =
                ContainmentPipeline.apply(
                    ExpansionWithFiles(expansion, emptyMap()),
                    workspace.root,
                    GroupingKind.DIRECTORY,
                    GroupingPolicy(),
                    provider,
                )

            val firstFile = result.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == first.fileUri }
            val secondFile = result.neighbours.single { it.kind == NodeKind.FILE && it.fileUri == second.fileUri }
            assertEquals("a", result.neighbours.single { it.id == firstFile.parent }.label)
            assertEquals("b", result.neighbours.single { it.id == secondFile.parent }.label)
        }
    }

    @Test
    fun namespaceGroupingUsesProvidedTextInsteadOfDisk() {
        withWorkspace { workspace ->
            val firstPath = workspace.write("src/a/First.kt", "package disk.alpha\n\nfun first(): Int = 1")
            val secondPath = workspace.write("src/b/Second.kt", "package disk.beta\n\nfun second(): Int = 2")
            val firstUri = firstPath.toUri().toString()
            val secondUri = secondPath.toUri().toString()
            val first = entity(firstUri, "provided.alpha.first")
            val second = entity(secondUri, "provided.beta.second")
            val expansion = Expansion(graphId, "revision", first, listOf(second), emptyList())
            val expansionWithFiles =
                ExpansionWithFiles(
                    expansion,
                    mapOf(
                        firstUri to "package provided.alpha\n\nfun first(): Int = 1",
                        secondUri to "package provided.beta\n\nfun second(): Int = 2",
                    ),
                )

            val result =
                ContainmentPipeline.apply(
                    expansionWithFiles,
                    workspace.root,
                    GroupingKind.NAMESPACE,
                    GroupingPolicy(),
                    KotlinContainmentProvider,
                )

            val groupLabels =
                result.neighbours
                    .filter { it.kind == NodeKind.GROUP }
                    .map { it.label }
                    .toSet()
            assertEquals(setOf("provided.alpha", "provided.beta"), groupLabels)
            assertTrue(result.neighbours.none { it.kind == NodeKind.GROUP && it.label.startsWith("disk.") })
        }
    }

    @Test
    fun namespaceGroupingRejectsMissingDocumentText() {
        withWorkspace { workspace ->
            val first =
                entity(
                    workspace.root
                        .resolve("src/a/First.kt")
                        .toUri()
                        .toString(),
                    "first",
                )
            val second =
                entity(
                    workspace.root
                        .resolve("src/b/Second.kt")
                        .toUri()
                        .toString(),
                    "second",
                )
            val expansion = Expansion(graphId, "revision", first, listOf(second), emptyList())
            val expansionWithFiles =
                ExpansionWithFiles(
                    expansion,
                    mapOf(first.fileUri!! to "package app\n\nfun first(): Int = 1"),
                )

            assertFailsWith<IllegalArgumentException> {
                ContainmentPipeline.apply(
                    expansionWithFiles,
                    workspace.root,
                    GroupingKind.NAMESPACE,
                    GroupingPolicy(),
                    KotlinContainmentProvider,
                )
            }
        }
    }

    @Test
    fun kotlinContainmentProviderImplementsNamespaceProvider() {
        val provider: NamespaceProvider = KotlinContainmentProvider

        assertEquals("app.order", provider.namespace("package app.order\n\nclass Order"))
        assertEquals("", provider.namespace("class Root"))
    }

    private fun entity(
        fileUri: String,
        qualifiedName: String,
    ): Node {
        val label = qualifiedName.substringAfterLast('.')
        return Node(
            id = NodeId("${graphId.value}/$fileUri/$qualifiedName/FUNCTION/fun $label()"),
            kind = NodeKind.ENTITY,
            label = label,
            qualifiedName = qualifiedName,
            fileUri = fileUri,
            signatureFingerprint = "fun $label()",
        )
    }

    private fun withWorkspace(block: (TestWorkspace) -> Unit) {
        val workspace = TestWorkspace()
        try {
            block(workspace)
        } finally {
            workspace.close()
        }
    }

    private class TestWorkspace {
        val root: Path = Files.createTempDirectory("containment-provider")

        fun write(
            relativePath: String,
            source: String,
        ): Path {
            val path = root.resolve(relativePath)
            Files.createDirectories(path.parent)
            Files.writeString(path, source, StandardCharsets.UTF_8)
            return path
        }

        fun close() {
            root.toFile().deleteRecursively()
        }
    }
}
