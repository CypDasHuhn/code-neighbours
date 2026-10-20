package dev.codenb.frontend

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKey
import dev.codenb.api.EdgeKind
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.api.SourceSpan
import dev.codenb.lsp.LspUnsupportedCapabilityException
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SymbolKind
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeighbourExpanderTest {
    private val graphId = GraphId("graph")

    @Test
    fun expandsIncomingReferencesFromAnotherFileAndAnotherEntity() {
        withWorkspace { workspace ->
            val aSource =
                """
                package app

                fun seed(): Int = 1

                fun caller(): Int = seed()
                """.trimIndent()
            val bSource =
                """
                package app

                fun external(): Int = seed()
                """.trimIndent()
            val aPath = workspace.write("A.kt", aSource)
            val bPath = workspace.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            val aSymbols =
                listOf(
                    documentSymbol(aSource, "seed", SymbolKind.Function, "fun seed"),
                    documentSymbol(aSource, "caller", SymbolKind.Function, "fun caller"),
                )
            val bSymbols = listOf(documentSymbol(bSource, "external", SymbolKind.Function, "fun external"))
            workspace.session.addSymbols(aUri, aSymbols)
            workspace.session.addSymbols(bUri, bSymbols)
            val seed = discover(aPath, aSource, aSymbols).single { it.qualifiedName == "app.seed" }
            val callerReference = indexAfter(aSource, "seed()", aSource.indexOf("fun caller"))
            val externalReference = indexAfter(bSource, "seed()", bSource.indexOf("fun external"))
            workspace.session.addReferences(
                aUri,
                aSymbols.first { it.name == "seed" }.selectionRange.start,
                listOf(
                    Location(aUri, rangeAt(aSource, callerReference, "seed".length)),
                    Location(bUri, rangeAt(bSource, externalReference, "seed".length)),
                ),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf("app.caller", "app.external"), expansion.neighbours.map { it.qualifiedName })
            assertEquals(2, expansion.edges.size)
            val caller = expansion.neighbours.single { it.qualifiedName == "app.caller" }
            val external = expansion.neighbours.single { it.qualifiedName == "app.external" }
            assertEdge(expansion, caller.id, seed.id, mapOf((caller.id to seed.id) to 1))
            assertEdge(expansion, external.id, seed.id, mapOf((external.id to seed.id) to 1))
            assertOriginAndCountConsistency(expansion)
        }
    }

    @Test
    fun expandsOutgoingReferencesThroughSemanticTokensAndDefinitions() {
        withWorkspace { workspace ->
            val aSource =
                """
                package app

                fun seed(): Int = helper() + target()

                fun helper(): Int = 1
                """.trimIndent()
            val bSource =
                """
                package app

                fun target(): Int = 1
                """.trimIndent()
            val aPath = workspace.write("A.kt", aSource)
            val bPath = workspace.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            val aSymbols =
                listOf(
                    documentSymbol(aSource, "seed", SymbolKind.Function, "fun seed"),
                    documentSymbol(aSource, "helper", SymbolKind.Function, "fun helper"),
                )
            val bSymbols = listOf(documentSymbol(bSource, "target", SymbolKind.Function, "fun target"))
            workspace.session.addSymbols(aUri, aSymbols)
            workspace.session.addSymbols(bUri, bSymbols)
            val seed = discover(aPath, aSource, aSymbols).single { it.qualifiedName == "app.seed" }
            val seedStart = aSource.indexOf("fun seed")
            val declaration = seedStart + "fun ".length
            val helperCall = aSource.indexOf("helper", seedStart)
            val targetCall = aSource.indexOf("target", seedStart)
            val helperDeclaration = aSource.indexOf("helper", aSource.indexOf("fun helper"))
            val targetDeclaration = bSource.indexOf("target", bSource.indexOf("fun target"))
            workspace.session.addTokens(
                aUri,
                tokens(
                    TokenSpec(positionAt(aSource, seedStart), "fun".length, "keyword"),
                    TokenSpec(positionAt(aSource, declaration), "seed".length, "function", setOf("declaration")),
                    TokenSpec(positionAt(aSource, helperCall), "helper".length, "function"),
                    TokenSpec(positionAt(aSource, targetCall), "target".length, "function"),
                ),
            )
            workspace.session.addDefinition(
                aUri,
                positionAt(aSource, helperCall),
                listOf(Location(aUri, rangeAt(aSource, helperDeclaration, "helper".length))),
            )
            workspace.session.addDefinition(
                aUri,
                positionAt(aSource, targetCall),
                listOf(Location(bUri, rangeAt(bSource, targetDeclaration, "target".length))),
            )
            workspace.session.addDefinition(
                aUri,
                positionAt(aSource, seedStart),
                listOf(Location(bUri, rangeAt(bSource, targetDeclaration, "target".length))),
            )
            workspace.session.addDefinition(
                aUri,
                positionAt(aSource, declaration),
                listOf(Location(bUri, rangeAt(bSource, targetDeclaration, "target".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf("app.helper", "app.target"), expansion.neighbours.map { it.qualifiedName })
            val helper = expansion.neighbours.single { it.qualifiedName == "app.helper" }
            val target = expansion.neighbours.single { it.qualifiedName == "app.target" }
            assertEdge(expansion, seed.id, helper.id, mapOf((seed.id to helper.id) to 1))
            assertEdge(expansion, seed.id, target.id, mapOf((seed.id to target.id) to 1))
            assertOriginAndCountConsistency(expansion)
        }
    }

    @Test
    fun mergesBothDirectionsAndKeepsDuplicateReferenceCounts() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int = helper()

                fun helper(): Int = seed()
                """.trimIndent()
            val aPath = workspace.write("A.kt", source)
            val uri = aPath.toUri().toString()
            val symbols =
                listOf(
                    documentSymbol(source, "seed", SymbolKind.Function, "fun seed"),
                    documentSymbol(source, "helper", SymbolKind.Function, "fun helper"),
                )
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(aPath, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.seed" }
            val helper = nodes.single { it.qualifiedName == "app.helper" }
            val seedStart = source.indexOf("fun seed")
            val helperStart = source.indexOf("fun helper")
            val helperCall = source.indexOf("helper", seedStart)
            val seedCall = source.indexOf("seed", helperStart)
            val helperDeclaration = helperStart + "fun ".length
            val seedDeclaration = seedStart + "fun ".length
            val seedReference = Location(uri, rangeAt(source, seedCall, "seed".length))
            workspace.session.addReferences(uri, positionAt(source, seedDeclaration), listOf(seedReference, seedReference))
            workspace.session.addTokens(
                uri,
                tokens(
                    TokenSpec(positionAt(source, seedDeclaration), "seed".length, "function", setOf("declaration")),
                    TokenSpec(positionAt(source, helperCall), "helper".length, "function"),
                ),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, helperCall),
                listOf(Location(uri, rangeAt(source, helperDeclaration, "helper".length))),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, seedDeclaration),
                listOf(Location(uri, rangeAt(source, seedDeclaration, "seed".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(helper.id), expansion.neighbours.map { it.id })
            assertEquals(1, expansion.edges.size)
            val edge = expansion.edges.single()
            assertEquals(EdgeKey.of(EdgeKind.REFERENCES, seed.id, helper.id), edge.key)
            val origins = edge.origins.associate { (it.from to it.to) to it.count }
            assertEquals(mapOf((helper.id to seed.id) to 2, (seed.id to helper.id) to 1), origins)
            assertEquals(3, edge.count)
            assertOriginAndCountConsistency(expansion)
        }
    }

    @Test
    fun ignoresExternalUnresolvedNonFileAndUnknownTargets() {
        withWorkspace { workspace ->
            val outside = Files.createTempDirectory("neighbour-expander-outside")
            try {
                val aSource =
                    """
                    package app

                    fun seed(): Int = outside() + missing() + stranger() + remote()
                    """.trimIndent()
                val bSource =
                    """
                    package app

                    fun target(): Int = 1
                    """.trimIndent()
                val outsideSource =
                    """
                    package outside

                    fun external(): Int = 1
                    """.trimIndent()
                val aPath = workspace.write("A.kt", aSource)
                val bPath = workspace.write("B.kt", bSource)
                val outsidePath = outside.resolve("Outside.kt")
                Files.writeString(outsidePath, outsideSource, StandardCharsets.UTF_8)
                val aUri = aPath.toUri().toString()
                val bUri = bPath.toUri().toString()
                val outsideUri = outsidePath.toUri().toString()
                val aSymbols = listOf(documentSymbol(aSource, "seed", SymbolKind.Function, "fun seed"))
                val bSymbols = listOf(documentSymbol(bSource, "target", SymbolKind.Function, "fun target"))
                workspace.session.addSymbols(aUri, aSymbols)
                workspace.session.addSymbols(bUri, bSymbols)
                val seed = discover(aPath, aSource, aSymbols).single()
                val seedStart = aSource.indexOf("fun seed")
                val outsideCall = aSource.indexOf("outside", seedStart)
                val missingCall = aSource.indexOf("missing", seedStart)
                val strangerCall = aSource.indexOf("stranger", seedStart)
                val remoteCall = aSource.indexOf("remote", seedStart)
                workspace.session.addReferences(
                    aUri,
                    aSymbols.single().selectionRange.start,
                    listOf(
                        Location(outsideUri, rangeAt(outsideSource, outsideSource.indexOf("external"), "external".length)),
                        Location(aUri, rangeAt(aSource, aSource.indexOf("package"), "package".length)),
                        Location("jdt://incoming/app/Foo.class", rangeAt(aSource, aSource.indexOf("package"), "package".length)),
                    ),
                )
                workspace.session.addTokens(
                    aUri,
                    tokens(
                        TokenSpec(positionAt(aSource, outsideCall), "outside".length, "function"),
                        TokenSpec(positionAt(aSource, missingCall), "missing".length, "function"),
                        TokenSpec(positionAt(aSource, strangerCall), "stranger".length, "function"),
                        TokenSpec(positionAt(aSource, remoteCall), "remote".length, "function"),
                    ),
                )
                workspace.session.addDefinition(
                    aUri,
                    positionAt(aSource, outsideCall),
                    listOf(Location(outsideUri, rangeAt(outsideSource, outsideSource.indexOf("external"), "external".length))),
                )
                workspace.session.addDefinition(
                    aUri,
                    positionAt(aSource, strangerCall),
                    listOf(Location(bUri, rangeAt(bSource, bSource.indexOf("package"), "package".length))),
                )
                workspace.session.addDefinition(
                    aUri,
                    positionAt(aSource, remoteCall),
                    listOf(Location("jdt://contents/app/Foo.class", rangeAt(aSource, seedStart, "fun".length))),
                )

                val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

                assertTrue(expansion.neighbours.isEmpty())
                assertTrue(expansion.edges.isEmpty())
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun ignoresLocalAndPrivateTargets() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int {
                    val local = 1
                    return local + secret()
                }

                private fun secret(): Int = 1
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val seedStart = source.indexOf("fun seed")
            val localStart = source.indexOf("val local")
            val localName = source.indexOf("local", localStart)
            val localUse = source.indexOf("local", source.indexOf("return local"))
            val secretStart = source.indexOf("private fun secret")
            val secretName = source.indexOf("secret", secretStart)
            val secretCall = source.indexOf("secret()", seedStart)
            val symbols =
                listOf(
                    documentSymbol(
                        source,
                        "seed",
                        SymbolKind.Function,
                        "fun seed",
                        rangeEnd = "return local + secret()\n}",
                        children =
                            listOf(
                                documentSymbol(
                                    source,
                                    "local",
                                    SymbolKind.Variable,
                                    "val local",
                                    rangeEnd = "val local = 1",
                                    fromIndex = localStart,
                                ),
                            ),
                    ),
                    documentSymbol(source, "secret", SymbolKind.Function, "private fun secret"),
                )
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single()
            workspace.session.addTokens(
                uri,
                tokens(
                    TokenSpec(positionAt(source, localName), "local".length, "variable", setOf("declaration")),
                    TokenSpec(positionAt(source, localUse), "local".length, "variable"),
                    TokenSpec(positionAt(source, secretCall), "secret".length, "function"),
                ),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, localUse),
                listOf(Location(uri, rangeAt(source, localName, "local".length))),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, secretCall),
                listOf(Location(uri, rangeAt(source, secretName, "secret".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertTrue(expansion.neighbours.isEmpty())
            assertTrue(expansion.edges.isEmpty())
        }
    }

    @Test
    fun ignoresSelfReferencesInBothDirections() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int = seed()
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val symbols = listOf(documentSymbol(source, "seed", SymbolKind.Function, "fun seed"))
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single()
            val seedStart = source.indexOf("fun seed")
            val declaration = seedStart + "fun ".length
            val call = source.indexOf("seed", declaration + "seed".length)
            workspace.session.addReferences(
                uri,
                positionAt(source, declaration),
                listOf(Location(uri, rangeAt(source, call, "seed".length))),
            )
            workspace.session.addTokens(
                uri,
                tokens(
                    TokenSpec(positionAt(source, declaration), "seed".length, "function", setOf("declaration")),
                    TokenSpec(positionAt(source, call), "seed".length, "function"),
                ),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                listOf(Location(uri, rangeAt(source, declaration, "seed".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertTrue(expansion.neighbours.isEmpty())
            assertTrue(expansion.edges.isEmpty())
        }
    }

    @Test
    fun returnsNoNeighboursAndTheSeedContentRevision() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int = 1
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val symbols = listOf(documentSymbol(source, "seed", SymbolKind.Function, "fun seed"))
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single()

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(graphId, expansion.graphId)
            assertEquals(seed, expansion.node)
            assertEquals(sha256(sha256(source)), expansion.revision)
            assertTrue(expansion.neighbours.isEmpty())
            assertTrue(expansion.edges.isEmpty())
        }
    }

    @Test
    fun propagatesUnsupportedSemanticTokensCapability() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int = 1
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val symbols = listOf(documentSymbol(source, "seed", SymbolKind.Function, "fun seed"))
            workspace.session.addSymbols(uri, symbols)
            workspace.session.semanticTokensFailure = LspUnsupportedCapabilityException("textDocument/semanticTokens/full")
            val seed = discover(path, source, symbols).single()

            assertFailsWith<LspUnsupportedCapabilityException> {
                NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)
            }
            assertNull(workspace.session.openDocument(uri))
            assertEquals(listOf(uri), workspace.session.closed)
        }
    }

    @Test
    fun closesOnlyDocumentsOpenedByTheExpansion() {
        withWorkspace { workspace ->
            val aSource =
                """
                package app

                fun seed(): Int = 1
                """.trimIndent()
            val bSource =
                """
                package app

                fun caller(): Int = seed()
                """.trimIndent()
            val aPath = workspace.write("A.kt", aSource)
            val bPath = workspace.write("B.kt", bSource)
            val aUri = aPath.toUri().toString()
            val bUri = bPath.toUri().toString()
            val aSymbols = listOf(documentSymbol(aSource, "seed", SymbolKind.Function, "fun seed"))
            val bSymbols = listOf(documentSymbol(bSource, "caller", SymbolKind.Function, "fun caller"))
            workspace.session.addSymbols(aUri, aSymbols)
            workspace.session.addSymbols(bUri, bSymbols)
            workspace.session.didOpen(aUri, "kotlin", aSource)
            workspace.session.opened.clear()
            val seed = discover(aPath, aSource, aSymbols).single()
            val callerReference = indexAfter(bSource, "seed()", bSource.indexOf("fun caller"))
            workspace.session.addReferences(
                aUri,
                aSymbols.single().selectionRange.start,
                listOf(Location(bUri, rangeAt(bSource, callerReference, "seed".length))),
            )

            NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertTrue(workspace.session.openDocument(aUri) != null)
            assertNull(workspace.session.openDocument(bUri))
            assertEquals(listOf(bUri), workspace.session.opened)
            assertEquals(listOf(bUri), workspace.session.closed)
        }
    }

    @Test
    fun expandsBodilessPrimaryConstructorSeedAtTheClassName() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget(val value: Int)

                fun caller(): Widget = Widget(1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "class Widget",
                    "class Widget(val value: Int)",
                )
            val clazz =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    "class Widget(val value: Int)",
                    children = listOf(constructor),
                )
            val caller = documentSymbol(source, "caller", SymbolKind.Function, "fun caller")
            val symbols = listOf(clazz, caller)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.id.value.contains("/CONSTRUCTOR/") }
            val className = source.indexOf("Widget", source.indexOf("class Widget"))
            val call = source.indexOf("Widget", source.indexOf("fun caller"))
            workspace.session.addReferences(
                uri,
                positionAt(source, className),
                listOf(Location(uri, rangeAt(source, call, "Widget".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            val callerNode = expansion.neighbours.single()
            assertEquals("app.caller", callerNode.qualifiedName)
            assertEdge(expansion, callerNode.id, seed.id, mapOf((callerNode.id to seed.id) to 1))
        }
    }

    @Test
    fun resolvesLocationLinkDefinitionToConstructor() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget(val value: Int)

                fun make(): Widget = Widget(1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "class Widget",
                    "class Widget(val value: Int)",
                )
            val clazz =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    "class Widget(val value: Int)",
                    children = listOf(constructor),
                )
            val make = documentSymbol(source, "make", SymbolKind.Function, "fun make")
            val symbols = listOf(clazz, make)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.make" }
            val constructorNode = nodes.single { it.id.value.contains("/CONSTRUCTOR/") }
            val call = source.indexOf("Widget", source.indexOf("fun make"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, call), "Widget".length, "class")),
            )
            val parametersStart = source.indexOf("(", source.indexOf("class Widget"))
            val parametersEnd = source.indexOf(")", parametersStart) + 1
            val constructorSelection = rangeAt(source, parametersStart, parametersEnd - parametersStart)
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                emptyList(),
                listOf(LocationLink(uri, clazz.range, constructorSelection)),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(constructorNode.id), expansion.neighbours.map { it.id })
            assertEdge(expansion, seed.id, constructorNode.id, mapOf((seed.id to constructorNode.id) to 1))
        }
    }

    @Test
    fun ignoresMemberDeclarationTokensWithoutDeclarationModifiers() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget {
                    fun member(): Int = 1

                    val value: Int = 2
                }
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val member = documentSymbol(source, "member", SymbolKind.Function, "fun member")
            val value = documentSymbol(source, "value", SymbolKind.Property, "val value")
            val clazz =
                documentSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    rangeEnd = "}",
                    children = listOf(member, value),
                )
            val symbols = listOf(clazz)
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single { it.qualifiedName == "app.Widget" }
            val legend =
                SemanticTokensLegend(
                    listOf("keyword", "function", "property", "class"),
                    listOf("definition"),
                )
            workspace.session.semanticTokensLegend = legend
            val className = source.indexOf("Widget", source.indexOf("class Widget"))
            val memberName = source.indexOf("member", source.indexOf("fun member"))
            val valueName = source.indexOf("value", source.indexOf("val value"))
            workspace.session.addTokens(
                uri,
                semanticTokens(
                    legend,
                    TokenSpec(positionAt(source, className), "Widget".length, "class"),
                    TokenSpec(positionAt(source, memberName), "member".length, "function"),
                    TokenSpec(positionAt(source, valueName), "value".length, "property"),
                ),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, memberName),
                listOf(Location(uri, rangeAt(source, memberName, "member".length))),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, valueName),
                listOf(Location(uri, rangeAt(source, valueName, "value".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertTrue(expansion.neighbours.isEmpty())
            assertTrue(expansion.edges.isEmpty())
        }
    }

    @Test
    fun expandsUserDefinedOperatorReferences() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                data class Amount(val value: Int) {
                    operator fun plus(other: Amount): Amount = Amount(value + other.value)
                }

                fun seed(a: Amount, b: Amount): Amount = a + b
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val plus = documentSymbol(source, "plus", SymbolKind.Operator, "operator fun plus")
            val amount =
                documentSymbol(
                    source,
                    "Amount",
                    SymbolKind.Class,
                    "data class Amount",
                    rangeEnd = "}",
                    children = listOf(plus),
                )
            val seedFunction = documentSymbol(source, "seed", SymbolKind.Function, "fun seed")
            val symbols = listOf(amount, seedFunction)
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single { it.qualifiedName == "app.seed" }
            val plusNode = discover(path, source, symbols).single { it.qualifiedName == "app.Amount.plus" }
            val operatorCall = source.indexOf("+", source.indexOf("fun seed"))
            val plusDeclaration = source.indexOf("plus", source.indexOf("operator fun plus"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, operatorCall), 1, "operator")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, operatorCall),
                listOf(Location(uri, rangeAt(source, plusDeclaration, "plus".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(plusNode.id), expansion.neighbours.map { it.id })
            assertEdge(expansion, seed.id, plusNode.id, mapOf((seed.id to plusNode.id) to 1))
        }
    }

    @Test
    fun failsWhenSemanticTokensAreNullDespiteIncomingReferences() {
        withWorkspace { workspace ->
            val seedSource =
                """
                package app

                fun seed(): Int = 1
                """.trimIndent()
            val callerSource =
                """
                package app

                fun caller(): Int = seed()
                """.trimIndent()
            val seedPath = workspace.write("A.kt", seedSource)
            val callerPath = workspace.write("B.kt", callerSource)
            val seedUri = seedPath.toUri().toString()
            val callerUri = callerPath.toUri().toString()
            val seedSymbols = listOf(documentSymbol(seedSource, "seed", SymbolKind.Function, "fun seed"))
            val callerSymbols = listOf(documentSymbol(callerSource, "caller", SymbolKind.Function, "fun caller"))
            workspace.session.addSymbols(seedUri, seedSymbols)
            workspace.session.addSymbols(callerUri, callerSymbols)
            val seed = discover(seedPath, seedSource, seedSymbols).single()
            val call = callerSource.indexOf("seed()", callerSource.indexOf("fun caller"))
            workspace.session.addReferences(
                seedUri,
                seedSymbols.single().selectionRange.start,
                listOf(Location(callerUri, rangeAt(callerSource, call, "seed".length))),
            )
            workspace.session.addTokens(seedUri, null)

            assertFailsWith<LspUnsupportedCapabilityException> {
                NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)
            }
        }
    }

    @Test
    fun revisionChangesWhenAnotherContributingFileChanges() {
        withWorkspace { workspace ->
            val seedSource =
                """
                package app

                fun seed(): Int = 1
                """.trimIndent()
            val firstCaller =
                """
                package app

                fun caller(): Int = seed()
                """.trimIndent()
            val secondCaller =
                """
                package app

                fun caller(): Int = seed()

                fun other(): Int = 1
                """.trimIndent()
            val seedPath = workspace.write("A.kt", seedSource)
            val callerPath = workspace.write("B.kt", firstCaller)
            val seedUri = seedPath.toUri().toString()
            val callerUri = callerPath.toUri().toString()
            val seedSymbols = listOf(documentSymbol(seedSource, "seed", SymbolKind.Function, "fun seed"))
            workspace.session.addSymbols(seedUri, seedSymbols)
            val seed = discover(seedPath, seedSource, seedSymbols).single()
            val firstCallerSymbols = listOf(documentSymbol(firstCaller, "caller", SymbolKind.Function, "fun caller"))
            workspace.session.addSymbols(callerUri, firstCallerSymbols)
            val call = firstCaller.indexOf("seed()", firstCaller.indexOf("fun caller"))
            workspace.session.addReferences(
                seedUri,
                seedSymbols.single().selectionRange.start,
                listOf(Location(callerUri, rangeAt(firstCaller, call, "seed".length))),
            )
            val firstRevision =
                NeighbourExpander(workspace.session)
                    .expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)
                    .revision
            workspace.write("B.kt", secondCaller)
            workspace.session.addSymbols(
                callerUri,
                listOf(documentSymbol(secondCaller, "caller", SymbolKind.Function, "fun caller")),
            )
            val secondCall = secondCaller.indexOf("seed()", secondCaller.indexOf("fun caller"))
            workspace.session.addReferences(
                seedUri,
                seedSymbols.single().selectionRange.start,
                listOf(Location(callerUri, rangeAt(secondCaller, secondCall, "seed".length))),
            )
            val secondRevision =
                NeighbourExpander(workspace.session)
                    .expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)
                    .revision

            assertTrue(firstRevision != secondRevision)
        }
    }

    @Test
    fun ignoresTokensStraddlingTheSeedSpan() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int = helper()

                fun helper(): Int = 1
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val symbols =
                listOf(
                    documentSymbol(source, "seed", SymbolKind.Function, "fun seed"),
                    documentSymbol(source, "helper", SymbolKind.Function, "fun helper"),
                )
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single { it.qualifiedName == "app.seed" }
            val call = source.indexOf("helper", source.indexOf("fun seed"))
            val declaration = source.indexOf("helper", source.indexOf("fun helper"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, call), 100, "function")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                listOf(Location(uri, rangeAt(source, declaration, "helper".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertTrue(expansion.neighbours.isEmpty())
            assertTrue(expansion.edges.isEmpty())
        }
    }

    @Test
    fun incomingReferencesTargetOnlyTheSelectedOverload() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun render(value: Int): Int = value

                fun render(value: String): String = value

                fun caller(): String = render("x")
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val first = documentSymbol(source, "render", SymbolKind.Function, "fun render")
            val second = documentSymbol(source, "render", SymbolKind.Function, "fun render", fromIndex = source.indexOf("fun render") + 1)
            val caller = documentSymbol(source, "caller", SymbolKind.Function, "fun caller")
            val symbols = listOf(first, second, caller)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.render" && it.signatureFingerprint?.contains("String") == true }
            val other = nodes.single { it.qualifiedName == "app.render" && it.signatureFingerprint?.contains("Int") == true }
            val call = source.indexOf("render", source.indexOf("fun caller"))
            workspace.session.addReferences(
                uri,
                second.selectionRange.start,
                listOf(Location(uri, rangeAt(source, call, "render".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf("app.caller"), expansion.neighbours.map { it.qualifiedName })
            assertTrue(expansion.neighbours.none { it.id == other.id })
            val callerNode = expansion.neighbours.single()
            assertEdge(expansion, callerNode.id, seed.id, mapOf((callerNode.id to seed.id) to 1))
        }
    }

    @Test
    fun expandsDefinitionResultsWithMultipleOverloadLocations() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun render(value: Int): Int = value

                fun render(value: String): String = value

                fun seed(): Int = render(1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val first = documentSymbol(source, "render", SymbolKind.Function, "fun render")
            val second = documentSymbol(source, "render", SymbolKind.Function, "fun render", fromIndex = source.indexOf("fun render") + 1)
            val seedFunction = documentSymbol(source, "seed", SymbolKind.Function, "fun seed")
            val symbols = listOf(first, second, seedFunction)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.seed" }
            val intOverload = nodes.single { it.qualifiedName == "app.render" && it.signatureFingerprint?.contains("Int") == true }
            val stringOverload = nodes.single { it.qualifiedName == "app.render" && it.signatureFingerprint?.contains("String") == true }
            val call = source.indexOf("render", source.indexOf("fun seed"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, call), "render".length, "function")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                listOf(
                    Location(uri, first.selectionRange),
                    Location(uri, second.selectionRange),
                ),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(intOverload.id, stringOverload.id).sortedBy { it.value }, expansion.neighbours.map { it.id })
            assertEdge(expansion, seed.id, intOverload.id, mapOf((seed.id to intOverload.id) to 1))
            assertEdge(expansion, seed.id, stringOverload.id, mapOf((seed.id to stringOverload.id) to 1))
        }
    }

    @Test
    fun decodesMultiLineTokensWithAServerSpecificLegend() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int {
                    val first = helper()
                    return first + target()
                }

                fun helper(): Int = 1

                fun target(): Int = 2
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val seedFunction =
                documentSymbol(
                    source,
                    "seed",
                    SymbolKind.Function,
                    "fun seed",
                    rangeEnd = "return first + target()\n}",
                )
            val helper = documentSymbol(source, "helper", SymbolKind.Function, "fun helper")
            val target = documentSymbol(source, "target", SymbolKind.Function, "fun target")
            val symbols = listOf(seedFunction, helper, target)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.seed" }
            val helperNode = nodes.single { it.qualifiedName == "app.helper" }
            val targetNode = nodes.single { it.qualifiedName == "app.target" }
            val legend =
                SemanticTokensLegend(
                    listOf("keyword", "variable", "function", "class"),
                    listOf("definition", "readonly", "declaration", "custom"),
                )
            workspace.session.semanticTokensLegend = legend
            val helperCall = source.indexOf("helper", source.indexOf("val first"))
            val targetCall = source.indexOf("target", source.indexOf("return first"))
            workspace.session.addTokens(
                uri,
                semanticTokens(
                    legend,
                    TokenSpec(positionAt(source, helperCall), "helper".length, "function", setOf("custom")),
                    TokenSpec(positionAt(source, targetCall), "target".length, "function", setOf("readonly")),
                ),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, helperCall),
                listOf(Location(uri, helper.selectionRange)),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, targetCall),
                listOf(Location(uri, target.selectionRange)),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(helperNode.id, targetNode.id).sortedBy { it.value }, expansion.neighbours.map { it.id })
            assertEdge(expansion, seed.id, helperNode.id, mapOf((seed.id to helperNode.id) to 1))
            assertEdge(expansion, seed.id, targetNode.id, mapOf((seed.id to targetNode.id) to 1))
        }
    }

    @Test
    fun mapsReferencesOnFirstAndLastLines() {
        withWorkspace { workspace ->
            val seedSource =
                """
                package app

                fun seed(): Int = 1
                """.trimIndent()
            val callerSource =
                """
                fun first(): Int = seed()

                fun last(): Int = seed()
                """.trimIndent()
            val seedPath = workspace.write("A.kt", seedSource)
            val callerPath = workspace.write("B.kt", callerSource)
            val seedUri = seedPath.toUri().toString()
            val callerUri = callerPath.toUri().toString()
            val seedSymbols = listOf(documentSymbol(seedSource, "seed", SymbolKind.Function, "fun seed"))
            val callerSymbols =
                listOf(
                    documentSymbol(callerSource, "first", SymbolKind.Function, "fun first"),
                    documentSymbol(callerSource, "last", SymbolKind.Function, "fun last"),
                )
            workspace.session.addSymbols(seedUri, seedSymbols)
            workspace.session.addSymbols(callerUri, callerSymbols)
            val seed = discover(seedPath, seedSource, seedSymbols).single()
            val firstCall = callerSource.indexOf("seed()", callerSource.indexOf("fun first"))
            val lastCall = callerSource.indexOf("seed()", callerSource.indexOf("fun last"))
            workspace.session.addReferences(
                seedUri,
                seedSymbols.single().selectionRange.start,
                listOf(
                    Location(callerUri, rangeAt(callerSource, firstCall, "seed".length)),
                    Location(callerUri, rangeAt(callerSource, lastCall, "seed".length)),
                ),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf("first", "last"), expansion.neighbours.map { it.qualifiedName })
        }
    }

    @Test
    fun ignoresDefinitionToAMissingWorkspaceFile() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int = missing()
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val symbols = listOf(documentSymbol(source, "seed", SymbolKind.Function, "fun seed"))
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single()
            val call = source.indexOf("missing", source.indexOf("fun seed"))
            val missingUri =
                workspace.root
                    .resolve("Missing.kt")
                    .toUri()
                    .toString()
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, call), "missing".length, "function")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                listOf(Location(missingUri, rangeAt(source, 0, 1))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertTrue(expansion.neighbours.isEmpty())
            assertTrue(expansion.edges.isEmpty())
        }
    }

    @Test
    fun rejectsASeedFileThatIsMissingFromTheWorkspace() {
        withWorkspace { workspace ->
            val missing = workspace.root.resolve("Missing.kt")
            val seed =
                Node(
                    id = NodeId("graph/${missing.toUri()}/app.Missing/FUNCTION/fun Missing()"),
                    kind = NodeKind.ENTITY,
                    label = "Missing",
                    qualifiedName = "app.Missing",
                    fileUri = missing.toUri().toString(),
                    signatureFingerprint = "fun Missing()",
                    span = SourceSpan(0, 0, 0, 10),
                )

            assertFailsWith<IllegalArgumentException> {
                NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)
            }
        }
    }

    @Test
    fun expandsBodilessSecondaryConstructorSeedAtTheConstructorKeyword() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget {
                    constructor(value: Int)
                }

                fun caller(): Widget = Widget(1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "constructor(value: Int)",
                    "constructor(value: Int)",
                )
            val clazz =
                documentSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    rangeEnd = "}",
                    children = listOf(constructor),
                )
            val caller = documentSymbol(source, "caller", SymbolKind.Function, "fun caller")
            val symbols = listOf(clazz, caller)
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single { it.id.value.contains("/CONSTRUCTOR/") }
            val constructorKeyword = source.indexOf("constructor", source.indexOf("class Widget"))
            val call = source.indexOf("Widget", source.indexOf("fun caller"))
            workspace.session.addReferences(
                uri,
                positionAt(source, constructorKeyword),
                listOf(Location(uri, rangeAt(source, call, "Widget".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            val callerNode = expansion.neighbours.single()
            assertEquals("app.caller", callerNode.qualifiedName)
            assertEdge(expansion, callerNode.id, seed.id, mapOf((callerNode.id to seed.id) to 1))
        }
    }

    @Test
    fun resolvesClassNameDefinitionTargetAndRejectsParameterTarget() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget(val value: Int)

                fun make(): Widget = Widget(value = 1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "class Widget",
                    "class Widget(val value: Int)",
                )
            val clazz =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    "class Widget(val value: Int)",
                    children = listOf(constructor),
                )
            val make = documentSymbol(source, "make", SymbolKind.Function, "fun make")
            val symbols = listOf(clazz, make)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.make" }
            val classNode = nodes.single { it.id.value.contains("/CLASS/") }
            val constructorNode = nodes.single { it.id.value.contains("/CONSTRUCTOR/") }
            val call = source.indexOf("Widget", source.indexOf("fun make"))
            val argument = source.indexOf("value", call)
            val className = source.indexOf("Widget", source.indexOf("class Widget"))
            val parameter = source.indexOf("value", source.indexOf("val value"))
            workspace.session.addTokens(
                uri,
                tokens(
                    TokenSpec(positionAt(source, call), "Widget".length, "class"),
                    TokenSpec(positionAt(source, argument), "value".length, "property"),
                ),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                listOf(Location(uri, rangeAt(source, className, "Widget".length))),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, argument),
                listOf(Location(uri, rangeAt(source, parameter, "value".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(classNode.id), expansion.neighbours.map { it.id })
            assertTrue(expansion.neighbours.none { it.id == constructorNode.id })
            assertEdge(expansion, seed.id, classNode.id, mapOf((seed.id to classNode.id) to 1))
        }
    }

    @Test
    fun usesLocationLinkTargetSelectionRangeForPosition() {
        withWorkspace { workspace ->
            val seedSource =
                """
                package app

                fun seed(): Int = member()
                """.trimIndent()
            val targetSource =
                """
                package app

                class Holder {
                    fun member(): Int = 1
                }
                """.trimIndent()
            val seedPath = workspace.write("A.kt", seedSource)
            val targetPath = workspace.write("B.kt", targetSource)
            val seedUri = seedPath.toUri().toString()
            val targetUri = targetPath.toUri().toString()
            val seedSymbols = listOf(documentSymbol(seedSource, "seed", SymbolKind.Function, "fun seed"))
            val member = documentSymbol(targetSource, "member", SymbolKind.Function, "fun member")
            val holder =
                documentSymbol(
                    targetSource,
                    "Holder",
                    SymbolKind.Class,
                    "class Holder",
                    rangeEnd = "}",
                    children = listOf(member),
                )
            workspace.session.addSymbols(seedUri, seedSymbols)
            workspace.session.addSymbols(targetUri, listOf(holder))
            val seed = discover(seedPath, seedSource, seedSymbols).single()
            val memberNode = discover(targetPath, targetSource, listOf(holder)).single { it.qualifiedName == "app.Holder.member" }
            val call = seedSource.indexOf("member", seedSource.indexOf("fun seed"))
            workspace.session.addTokens(
                seedUri,
                tokens(TokenSpec(positionAt(seedSource, call), "member".length, "function")),
            )
            workspace.session.addDefinition(
                seedUri,
                positionAt(seedSource, call),
                emptyList(),
                listOf(LocationLink(targetUri, holder.range, member.selectionRange)),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(memberNode.id), expansion.neighbours.map { it.id })
            assertEdge(expansion, seed.id, memberNode.id, mapOf((seed.id to memberNode.id) to 1))
        }
    }

    @Test
    fun resolvesClassLocationLinkToClassNotItsCoincidentConstructor() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget(val value: Int)

                fun make(): Widget = Widget(1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "class Widget",
                    "class Widget(val value: Int)",
                )
            val clazz =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    "class Widget(val value: Int)",
                    children = listOf(constructor),
                )
            val make = documentSymbol(source, "make", SymbolKind.Function, "fun make")
            val symbols = listOf(clazz, make)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.make" }
            val classNode = nodes.single { it.id.value.contains("/CLASS/") }
            val constructorNode = nodes.single { it.id.value.contains("/CONSTRUCTOR/") }
            val call = source.indexOf("Widget", source.indexOf("fun make"))
            val className = source.indexOf("Widget", source.indexOf("class Widget"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, call), "Widget".length, "class")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                emptyList(),
                listOf(LocationLink(uri, clazz.range, rangeAt(source, className, "Widget".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(classNode.id), expansion.neighbours.map { it.id })
            assertTrue(expansion.neighbours.none { it.id == constructorNode.id })
            assertEdge(expansion, seed.id, classNode.id, mapOf((seed.id to classNode.id) to 1))
        }
    }

    @Test
    fun findsPrimaryConstructorReferencePositionAfterAnnotationAndKdoc() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                /**
                 * Widget documentation mentions Widget.
                 */
                @Named("Widget")
                class Widget(val value: Int)

                fun caller(): Widget = Widget(1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "class Widget",
                    "class Widget(val value: Int)",
                )
            val clazz =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "/**",
                    "class Widget(val value: Int)",
                    children = listOf(constructor),
                )
            val caller = documentSymbol(source, "caller", SymbolKind.Function, "fun caller")
            val symbols = listOf(clazz, caller)
            workspace.session.addSymbols(uri, symbols)
            val detailed = KotlinEntityDiscovery.discoverDetailed(graphId, uri, source, symbols)
            val constructorEntity =
                detailed.single {
                    it.node.id.value
                        .contains("/CONSTRUCTOR/")
                }
            val className = source.indexOf("Widget", source.indexOf("class Widget"))
            assertEquals(positionAt(source, className), constructorEntity.referencePosition)
            val seed = constructorEntity.node
            val call = source.indexOf("Widget", source.indexOf("fun caller"))
            workspace.session.addReferences(
                uri,
                constructorEntity.referencePosition,
                listOf(Location(uri, rangeAt(source, call, "Widget".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf("app.caller"), expansion.neighbours.map { it.qualifiedName })
        }
    }

    @Test
    fun ignoresNonEntityParameterLocalAndTypeParameterDefinitionTargetsInClassSeed() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget {
                    fun <T> member(input: T): T {
                        val local = input
                        return local
                    }
                }
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val memberStart = source.indexOf("fun <T>")
            val typeParameter = documentSymbol(source, "T", SymbolKind.TypeParameter, "T>", rangeEnd = "T>", fromIndex = memberStart)
            val parameter = documentSymbol(source, "input", SymbolKind.Variable, "input: T", fromIndex = memberStart)
            val local = documentSymbol(source, "local", SymbolKind.Variable, "val local", fromIndex = memberStart)
            val member =
                documentSymbol(
                    source,
                    "member",
                    SymbolKind.Function,
                    "fun <T>",
                    rangeEnd = "return local\n    }",
                    fromIndex = memberStart,
                    children = listOf(typeParameter, parameter, local),
                )
            val clazz =
                documentSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    rangeEnd = "return local\n    }\n}",
                    children = listOf(member),
                )
            val symbols = listOf(clazz)
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single { it.qualifiedName == "app.Widget" }
            val typeParameterName = source.indexOf("T", memberStart)
            val parameterName = source.indexOf("input", memberStart)
            val localName = source.indexOf("local", source.indexOf("val local"))
            workspace.session.addTokens(
                uri,
                tokens(
                    TokenSpec(positionAt(source, typeParameterName), "T".length, "typeParameter"),
                    TokenSpec(positionAt(source, parameterName), "input".length, "variable"),
                    TokenSpec(positionAt(source, localName), "local".length, "variable"),
                ),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, typeParameterName),
                listOf(Location(uri, rangeAt(source, typeParameterName, "T".length))),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, parameterName),
                listOf(Location(uri, rangeAt(source, parameterName, "input".length))),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, localName),
                listOf(Location(uri, rangeAt(source, localName, "local".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertTrue(expansion.neighbours.isEmpty())
            assertTrue(expansion.edges.isEmpty())
        }
    }

    @Test
    fun revisionChangesWhenAConsultedFileYieldsNoEdge() {
        withWorkspace { workspace ->
            val seedSource =
                """
                package app

                fun seed(): Int = 1
                """.trimIndent()
            val firstConsulted =
                """
                package app

                val ignored = 1
                """.trimIndent()
            val secondConsulted =
                """
                package app

                val changed = 2
                """.trimIndent()
            val seedPath = workspace.write("A.kt", seedSource)
            val consultedPath = workspace.write("B.kt", firstConsulted)
            val seedUri = seedPath.toUri().toString()
            val consultedUri = consultedPath.toUri().toString()
            val seedSymbols = listOf(documentSymbol(seedSource, "seed", SymbolKind.Function, "fun seed"))
            workspace.session.addSymbols(seedUri, seedSymbols)
            val seed = discover(seedPath, seedSource, seedSymbols).single()
            workspace.session.addReferences(
                seedUri,
                seedSymbols.single().selectionRange.start,
                listOf(Location(consultedUri, rangeAt(firstConsulted, 0, 1))),
            )
            val firstRevision =
                NeighbourExpander(workspace.session)
                    .expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)
                    .revision
            workspace.write("B.kt", secondConsulted)
            workspace.session.addReferences(
                seedUri,
                seedSymbols.single().selectionRange.start,
                listOf(Location(consultedUri, rangeAt(secondConsulted, 0, 1))),
            )
            val secondRevision =
                NeighbourExpander(workspace.session)
                    .expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)
                    .revision

            assertTrue(firstRevision != secondRevision)
        }
    }

    @Test
    fun resolvesPlainFunctionLocationStartingAtFunctionKeyword() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                fun seed(): Int = helper()

                fun helper(): Int = 1
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val seedSymbol = documentSymbol(source, "seed", SymbolKind.Function, "fun seed")
            val helperSymbol = documentSymbol(source, "helper", SymbolKind.Function, "fun helper")
            val symbols = listOf(seedSymbol, helperSymbol)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.seed" }
            val helperNode = nodes.single { it.qualifiedName == "app.helper" }
            val call = source.indexOf("helper", source.indexOf("fun seed"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, call), "helper".length, "function")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                listOf(Location(uri, helperSymbol.range)),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(helperNode.id), expansion.neighbours.map { it.id })
            assertEdge(expansion, seed.id, helperNode.id, mapOf((seed.id to helperNode.id) to 1))
        }
    }

    @Test
    fun resolvesPlainClassLocationStartingAtClassKeywordBeforeConstructor() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget(val value: Int)

                fun make(): Widget = Widget(1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "class Widget",
                    "class Widget(val value: Int)",
                )
            val clazz =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    "class Widget(val value: Int)",
                    children = listOf(constructor),
                )
            val make = documentSymbol(source, "make", SymbolKind.Function, "fun make")
            val symbols = listOf(clazz, make)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.make" }
            val classNode = nodes.single { it.id.value.contains("/CLASS/") }
            val constructorNode = nodes.single { it.id.value.contains("/CONSTRUCTOR/") }
            val call = source.indexOf("Widget", source.indexOf("fun make"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, call), "Widget".length, "class")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                listOf(Location(uri, clazz.range)),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(classNode.id), expansion.neighbours.map { it.id })
            assertTrue(expansion.neighbours.none { it.id == constructorNode.id })
            assertEdge(expansion, seed.id, classNode.id, mapOf((seed.id to classNode.id) to 1))
        }
    }

    @Test
    fun ignoresLocationLinkToAnIndividualConstructorParameter() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget(val value: Int)

                fun make(): Widget = Widget(value = 1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "class Widget",
                    "class Widget(val value: Int)",
                )
            val clazz =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    "class Widget(val value: Int)",
                    children = listOf(constructor),
                )
            val make = documentSymbol(source, "make", SymbolKind.Function, "fun make")
            val symbols = listOf(clazz, make)
            workspace.session.addSymbols(uri, symbols)
            val seed = discover(path, source, symbols).single { it.qualifiedName == "app.make" }
            val call = source.indexOf("Widget", source.indexOf("fun make"))
            val argument = source.indexOf("value", call)
            val parameter = source.indexOf("value", source.indexOf("val value"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, argument), "value".length, "property")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, argument),
                emptyList(),
                listOf(LocationLink(uri, clazz.range, rangeAt(source, parameter, "value".length))),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertTrue(expansion.neighbours.isEmpty())
            assertTrue(expansion.edges.isEmpty())
        }
    }

    @Test
    fun resolvesPlainPrimaryConstructorHeaderLocationBeforeContainingClass() {
        withWorkspace { workspace ->
            val source =
                """
                package app

                class Widget(val value: Int) {
                    fun valueOf(): Int = value
                }

                fun make(): Widget = Widget(1)
                """.trimIndent()
            val path = workspace.write("A.kt", source)
            val uri = path.toUri().toString()
            val constructor =
                fullRangeSymbol(
                    source,
                    "Widget",
                    SymbolKind.Constructor,
                    "class Widget",
                    "class Widget(val value: Int)",
                )
            val valueOf = documentSymbol(source, "valueOf", SymbolKind.Function, "fun valueOf")
            val clazz =
                documentSymbol(
                    source,
                    "Widget",
                    SymbolKind.Class,
                    "class Widget",
                    rangeEnd = "}",
                    children = listOf(constructor, valueOf),
                )
            val make = documentSymbol(source, "make", SymbolKind.Function, "fun make")
            val symbols = listOf(clazz, make)
            workspace.session.addSymbols(uri, symbols)
            val nodes = discover(path, source, symbols)
            val seed = nodes.single { it.qualifiedName == "app.make" }
            val classNode = nodes.single { it.id.value.contains("/CLASS/") }
            val constructorNode = nodes.single { it.id.value.contains("/CONSTRUCTOR/") }
            val call = source.indexOf("Widget", source.indexOf("fun make"))
            workspace.session.addTokens(
                uri,
                tokens(TokenSpec(positionAt(source, call), "Widget".length, "class")),
            )
            workspace.session.addDefinition(
                uri,
                positionAt(source, call),
                listOf(Location(uri, constructor.range)),
            )

            val expansion = NeighbourExpander(workspace.session).expand(graphId, seed, workspace.root, KotlinFrontend.descriptor)

            assertEquals(listOf(constructorNode.id), expansion.neighbours.map { it.id })
            assertTrue(expansion.neighbours.none { it.id == classNode.id })
            assertEdge(expansion, seed.id, constructorNode.id, mapOf((seed.id to constructorNode.id) to 1))
        }
    }

    private fun assertEdge(
        expansion: Expansion,
        a: NodeId,
        b: NodeId,
        expected: Map<Pair<NodeId, NodeId>, Int>,
    ) {
        val edge: Edge = expansion.edges.single { it.key == EdgeKey.of(EdgeKind.REFERENCES, a, b) }
        val origins = edge.origins.associate { (it.from to it.to) to it.count }
        assertEquals(expected, origins)
        assertEquals(edge.origins.size, origins.size)
        assertEquals(expected.values.sum(), edge.count)
        assertTrue(
            edge.origins.all { origin ->
                (origin.from == edge.from && origin.to == edge.to) ||
                    (origin.from == edge.to && origin.to == edge.from)
            },
        )
    }

    private fun assertOriginAndCountConsistency(expansion: Expansion) {
        expansion.edges.forEach { edge ->
            assertEquals(edge.origins.sumOf { it.count }, edge.count)
            assertEquals(
                edge.origins.size,
                edge.origins
                    .map { it.from to it.to }
                    .distinct()
                    .size,
            )
            assertTrue(edge.origins.all { it.from != it.to })
        }
    }

    private fun discover(
        path: Path,
        source: String,
        symbols: List<DocumentSymbol>,
    ): List<Node> = KotlinEntityDiscovery.discover(graphId, path.toUri().toString(), source, symbols)

    private fun tokens(vararg specs: TokenSpec): SemanticTokens =
        semanticTokens(
            SemanticTokensLegend(FakeLspSession.TOKEN_TYPES, FakeLspSession.TOKEN_MODIFIERS),
            *specs,
        )

    private fun semanticTokens(
        legend: SemanticTokensLegend,
        vararg specs: TokenSpec,
    ): SemanticTokens {
        val data = mutableListOf<Int>()
        var previousLine = 0
        var previousStart = 0
        specs
            .sortedWith(compareBy({ it.start.line }, { it.start.character }))
            .forEach { spec ->
                val deltaLine = spec.start.line - previousLine
                val deltaStart = if (deltaLine == 0) spec.start.character - previousStart else spec.start.character
                val type = legend.tokenTypes.indexOf(spec.type)
                require(type >= 0) { "Unknown token type ${spec.type}" }
                val modifiers =
                    spec.modifiers.sumOf { modifier ->
                        val bit = legend.tokenModifiers.indexOf(modifier)
                        require(bit >= 0) { "Unknown token modifier $modifier" }
                        1 shl bit
                    }
                data.add(deltaLine)
                data.add(deltaStart)
                data.add(spec.length)
                data.add(type)
                data.add(modifiers)
                previousLine = spec.start.line
                previousStart = spec.start.character
            }
        return SemanticTokens(data)
    }

    private fun documentSymbol(
        source: String,
        name: String,
        kind: SymbolKind,
        rangeStart: String,
        rangeEnd: String? = null,
        fromIndex: Int = 0,
        children: List<DocumentSymbol> = emptyList(),
    ): DocumentSymbol {
        val start = source.indexOf(rangeStart, fromIndex)
        require(start >= 0) { "Missing range start $rangeStart" }
        val end =
            if (rangeEnd == null) {
                val newline = source.indexOf('\n', start)
                if (newline < 0) source.length else newline
            } else {
                val found = source.indexOf(rangeEnd, start)
                require(found >= 0) { "Missing range end $rangeEnd" }
                found + rangeEnd.length
            }
        val selectionStart = source.indexOf(name, start)
        require(selectionStart >= 0) { "Missing selection $name" }
        return DocumentSymbol(
            name,
            kind,
            rangeAt(source, start, end - start),
            rangeAt(source, selectionStart, name.length),
            null,
            children,
        )
    }

    private fun fullRangeSymbol(
        source: String,
        name: String,
        kind: SymbolKind,
        rangeStart: String,
        rangeEnd: String,
        fromIndex: Int = 0,
        children: List<DocumentSymbol> = emptyList(),
    ): DocumentSymbol {
        val start = source.indexOf(rangeStart, fromIndex)
        require(start >= 0) { "Missing range start $rangeStart" }
        val found = source.indexOf(rangeEnd, start)
        require(found >= 0) { "Missing range end $rangeEnd" }
        val range = rangeAt(source, start, found + rangeEnd.length - start)
        return DocumentSymbol(name, kind, range, range, null, children)
    }

    private fun rangeAt(
        source: String,
        index: Int,
        length: Int,
    ): Range = Range(positionAt(source, index), positionAt(source, index + length))

    private fun positionAt(
        source: String,
        index: Int,
    ): Position {
        val prefix = source.substring(0, index)
        val line = prefix.count { it == '\n' }
        val lineStart = prefix.lastIndexOf('\n') + 1
        return Position(line, index - lineStart)
    }

    private fun indexAfter(
        source: String,
        needle: String,
        fromIndex: Int,
    ): Int {
        val index = source.indexOf(needle, fromIndex)
        require(index >= 0) { "Missing $needle" }
        return index
    }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private fun withWorkspace(block: (TestWorkspace) -> Unit) {
        val workspace = TestWorkspace()
        try {
            block(workspace)
        } finally {
            workspace.close()
        }
    }

    private class TestWorkspace {
        val root: Path = Files.createTempDirectory("neighbour-expander")
        val session = FakeLspSession()

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

    private data class TokenSpec(
        val start: Position,
        val length: Int,
        val type: String,
        val modifiers: Set<String> = emptySet(),
    )
}
