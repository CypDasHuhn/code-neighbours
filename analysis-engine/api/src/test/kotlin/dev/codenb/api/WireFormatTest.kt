package dev.codenb.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WireFormatTest {
    private val graphId = GraphId("graph-1")
    private val fileId = NodeId("graph-1/files/app/Main.kt")
    private val entityId = NodeId("graph-1/entities/app.Main")
    private val otherId = NodeId("graph-1/entities/app.Other")

    private val node =
        Node(
            id = entityId,
            kind = NodeKind.ENTITY,
            label = "Main",
            qualifiedName = "app.Main",
            fileUri = "app/Main.kt",
            signatureFingerprint = "fun Main()",
            span = SourceSpan(1, 2, 3, 4),
            parent = fileId,
            unstable = false,
        )
    private val otherNode =
        Node(
            id = otherId,
            kind = NodeKind.ENTITY,
            label = "Other",
            qualifiedName = "app.Other",
            fileUri = "app/Other.kt",
            signatureFingerprint = "fun Other()",
            span = SourceSpan(5, 6, 7, 8),
            parent = fileId,
        )
    private val origin = EdgeOrigin(entityId, otherId, 2)
    private val edge = Edge.of(EdgeKind.REFERENCES, otherId, entityId, 2, listOf(origin))
    private val expansion =
        Expansion(
            graphId = graphId,
            revision = "revision-1",
            node = node,
            neighbours = listOf(otherNode),
            edges = listOf(edge),
        )
    private val graphInfo =
        GraphInfo(
            graphId = graphId,
            root = "/workspace",
            lspId = "kotlin",
            groupingKind = GroupingKind.NAMESPACE,
            nodeKinds = listOf(NodeKind.ENTITY, NodeKind.FILE, NodeKind.GROUP),
            edgeKinds =
                listOf(
                    EdgeKind.CONTAINS,
                    EdgeKind.REFERENCES,
                    EdgeKind.FILE_REFERENCES,
                    EdgeKind.GROUP_REFERENCES,
                ),
            protocolVersion = PROTOCOL_VERSION,
        )
    private val engineError =
        EngineError(
            code = EngineErrorCode.UNSUPPORTED_CAPABILITY,
            message = "Semantic tokens are unavailable",
            details = mapOf("lspId" to "kotlin"),
        )

    @Test
    fun identifiersEncodeAsJsonStrings() {
        assertEquals("\"graph-1\"", GraphJson.encode(graphId))
        assertEquals("\"graph-1/entities/app.Main\"", GraphJson.encode(entityId))
    }

    @Test
    fun nodeWireKeysAndValues() {
        val json = encodedObject(node)

        assertEquals(
            setOf(
                "id",
                "kind",
                "label",
                "qualifiedName",
                "fileUri",
                "signatureFingerprint",
                "span",
                "parent",
                "unstable",
            ),
            json.keys,
        )
        assertEquals("graph-1/entities/app.Main", json["id"]!!.jsonPrimitive.content)
        assertEquals("ENTITY", json["kind"]!!.jsonPrimitive.content)
        assertEquals("Main", json["label"]!!.jsonPrimitive.content)
        assertEquals("app.Main", json["qualifiedName"]!!.jsonPrimitive.content)
        assertEquals("app/Main.kt", json["fileUri"]!!.jsonPrimitive.content)
        assertEquals("fun Main()", json["signatureFingerprint"]!!.jsonPrimitive.content)
        assertEquals("graph-1/files/app/Main.kt", json["parent"]!!.jsonPrimitive.content)
        assertEquals(false, json["unstable"]!!.jsonPrimitive.boolean)
        assertEquals(
            setOf("startLine", "startCharacter", "endLine", "endCharacter"),
            json["span"]!!.jsonObject.keys,
        )
    }

    @Test
    fun edgeOriginWireKeysAndValues() {
        val json = encodedObject(origin)

        assertEquals(setOf("from", "to", "count"), json.keys)
        assertEquals("graph-1/entities/app.Main", json["from"]!!.jsonPrimitive.content)
        assertEquals("graph-1/entities/app.Other", json["to"]!!.jsonPrimitive.content)
        assertEquals(2, json["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun edgeWireKeysAndEndpointNames() {
        val json = encodedObject(edge)

        assertEquals(setOf("kind", "from", "to", "count", "origins"), json.keys)
        assertEquals("REFERENCES", json["kind"]!!.jsonPrimitive.content)
        assertEquals("graph-1/entities/app.Main", json["from"]!!.jsonPrimitive.content)
        assertEquals("graph-1/entities/app.Other", json["to"]!!.jsonPrimitive.content)
        assertEquals(2, json["count"]!!.jsonPrimitive.int)
        val origins = json["origins"]!!.jsonArray
        assertEquals(1, origins.size)
        assertEquals(setOf("from", "to", "count"), origins[0].jsonObject.keys)
    }

    @Test
    fun edgeKeyWireKeysAndValues() {
        val json = encodedObject(EdgeKey.of(EdgeKind.REFERENCES, otherId, entityId))

        assertEquals(setOf("kind", "from", "to"), json.keys)
        assertEquals("REFERENCES", json["kind"]!!.jsonPrimitive.content)
        assertEquals("graph-1/entities/app.Main", json["from"]!!.jsonPrimitive.content)
        assertEquals("graph-1/entities/app.Other", json["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun expansionWireKeysAndValues() {
        val json = encodedObject(expansion)

        assertEquals(setOf("graphId", "revision", "node", "neighbours", "edges"), json.keys)
        assertEquals("graph-1", json["graphId"]!!.jsonPrimitive.content)
        assertEquals("revision-1", json["revision"]!!.jsonPrimitive.content)
        assertEquals("graph-1/entities/app.Main", json["node"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, json["neighbours"]!!.jsonArray.size)
        assertEquals(1, json["edges"]!!.jsonArray.size)
    }

    @Test
    fun graphInfoWireKeysAndKindOrder() {
        val json = encodedObject(graphInfo)

        assertEquals(
            setOf("graphId", "root", "lspId", "groupingKind", "nodeKinds", "edgeKinds", "protocolVersion"),
            json.keys,
        )
        assertEquals("graph-1", json["graphId"]!!.jsonPrimitive.content)
        assertEquals("/workspace", json["root"]!!.jsonPrimitive.content)
        assertEquals("kotlin", json["lspId"]!!.jsonPrimitive.content)
        assertEquals("NAMESPACE", json["groupingKind"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("ENTITY", "FILE", "GROUP"),
            json["nodeKinds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("CONTAINS", "REFERENCES", "FILE_REFERENCES", "GROUP_REFERENCES"),
            json["edgeKinds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(1, json["protocolVersion"]!!.jsonPrimitive.int)
    }

    @Test
    fun engineErrorWireKeysAndCodeName() {
        val json = encodedObject(engineError)

        assertEquals(setOf("code", "message", "details"), json.keys)
        assertEquals("unsupported-capability", json["code"]!!.jsonPrimitive.content)
        assertEquals("Semantic tokens are unavailable", json["message"]!!.jsonPrimitive.content)
        assertEquals(setOf("lspId"), json["details"]!!.jsonObject.keys)
        assertEquals("kotlin", json["details"]!!.jsonObject["lspId"]!!.jsonPrimitive.content)
    }

    @Test
    fun engineErrorCodesUseStableSerialNames() {
        assertEquals("\"unsupported-capability\"", GraphJson.encode(EngineErrorCode.UNSUPPORTED_CAPABILITY))
        assertEquals("\"lsp-failure\"", GraphJson.encode(EngineErrorCode.LSP_FAILURE))
        assertEquals("\"workspace-error\"", GraphJson.encode(EngineErrorCode.WORKSPACE_ERROR))
        assertEquals("\"invalid-request\"", GraphJson.encode(EngineErrorCode.INVALID_REQUEST))
        assertEquals("\"unknown-node\"", GraphJson.encode(EngineErrorCode.UNKNOWN_NODE))
        assertEquals("\"invalid-graph\"", GraphJson.encode(EngineErrorCode.INVALID_GRAPH))
        assertEquals("\"internal\"", GraphJson.encode(EngineErrorCode.INTERNAL))
    }

    private inline fun <reified T> encodedObject(value: T): JsonObject =
        GraphJson.json.parseToJsonElement(GraphJson.encode(value)).jsonObject
}
