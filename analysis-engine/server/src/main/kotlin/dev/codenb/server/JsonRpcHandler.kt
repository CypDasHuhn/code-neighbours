package dev.codenb.server

import dev.codenb.api.EngineError
import dev.codenb.api.EngineErrorCode
import dev.codenb.api.GraphId
import dev.codenb.api.GraphJson
import dev.codenb.api.NodeId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class JsonRpcHandler(
    private val service: GraphService,
    private val socketPath: String,
    private val shutdown: () -> Unit = {},
) {
    fun handle(line: String): List<JsonElement> {
        val element =
            try {
                Json.parseToJsonElement(line)
            } catch (exception: SerializationException) {
                return listOf(parseError())
            } catch (exception: IllegalArgumentException) {
                return listOf(parseError())
            }
        if (element !is JsonObject) {
            return protocolFailure(JsonNull, true, INVALID_REQUEST, "Request must be a JSON object")
        }
        val id = element["id"]
        val hasId = element.containsKey("id")
        if (hasId && !validId(id!!)) {
            return protocolFailure(JsonNull, true, INVALID_REQUEST, "Request id must be a string, number, or null")
        }
        val jsonrpc = element["jsonrpc"]
        if (jsonrpc !is JsonPrimitive || !jsonrpc.isString || jsonrpc.content != "2.0") {
            return protocolFailure(id, true, INVALID_REQUEST, "jsonrpc must be \"2.0\"")
        }
        val method = element["method"]
        if (method !is JsonPrimitive || !method.isString || method.content.isBlank()) {
            return protocolFailure(id, true, INVALID_REQUEST, "method must be a non-empty string")
        }
        val paramsElement = element["params"]
        val params =
            when {
                paramsElement == null || paramsElement is JsonNull -> JsonObject(emptyMap())
                paramsElement is JsonObject -> paramsElement
                else -> return protocolFailure(id, hasId, INVALID_PARAMS, "params must be an object")
            }
        return dispatch(id, hasId, method.content, params)
    }

    private fun dispatch(
        id: JsonElement?,
        hasId: Boolean,
        method: String,
        params: JsonObject,
    ): List<JsonElement> =
        try {
            val result =
                when (method) {
                    "initialize" -> initialize()
                    "graph/open" -> openGraph(params)
                    "node/expand" -> expandNode(params)
                    "node/get" -> encode(service.node(graphIdParam(params), nodeIdParam(params)))
                    "graph/dot" -> JsonPrimitive(service.dot(graphIdParam(params)))
                    "shutdown" -> {
                        shutdown()
                        JsonObject(emptyMap())
                    }
                    else -> throw RpcException(METHOD_NOT_FOUND, "Method not found: $method")
                }
            if (hasId) listOf(response(id ?: JsonNull, result)) else emptyList()
        } catch (exception: RpcException) {
            if (hasId) {
                listOf(errorResponse(id ?: JsonNull, exception.code, exception.message ?: "Request failed"))
            } else {
                emptyList()
            }
        } catch (exception: EngineException) {
            applicationFailure(id, hasId, exception.error)
        } catch (exception: Throwable) {
            val error = EngineError(EngineErrorCode.INTERNAL, exception.message ?: "Internal error")
            applicationFailure(id, hasId, error)
        }

    private fun initialize(): JsonElement {
        val status = service.status()
        return buildJsonObject {
            put("graph", encode(service.info()))
            put("socketPath", JsonPrimitive(socketPath))
            put("open", status.open)
            put("revision", status.revision?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "expandableNodeKinds",
                JsonArray(service.expandableNodeKinds().map { kind -> JsonPrimitive(kind.name) }),
            )
        }
    }

    private fun openGraph(params: JsonObject): JsonElement {
        val expansion = service.open(parseSeed(params))
        return buildJsonObject {
            put("graph", encode(service.info()))
            put("expansion", encode(expansion))
        }
    }

    private fun expandNode(params: JsonObject): JsonElement {
        val graphId = graphIdParam(params)
        val nodeId = nodeIdParam(params)
        val revision = stringParam(params, "revision")
        return encode(service.expand(graphId, nodeId, revision))
    }

    private fun parseSeed(params: JsonObject): Seed {
        val nodeElement = params["nodeId"]
        if (nodeElement != null) {
            if (params.keys != setOf("nodeId")) {
                throw invalidParams("Seed accepts either {nodeId} or {file, qualifiedName, signatureFingerprint?}")
            }
            return Seed.ByNodeId(nodeId(nodeElement, "nodeId"))
        }
        val allowed = setOf("file", "qualifiedName", "signatureFingerprint")
        val unknown = params.keys - allowed
        if (unknown.isNotEmpty()) {
            throw invalidParams("Unknown seed field(s): ${unknown.sorted().joinToString(",")}")
        }
        val file = stringParam(params, "file")
        val qualifiedName = stringParam(params, "qualifiedName")
        val signature =
            params["signatureFingerprint"]
                ?.takeUnless { it is JsonNull }
                ?.let { stringValue(it, "signatureFingerprint") }
        return Seed.Selector(file, qualifiedName, signature)
    }

    private fun graphIdParam(params: JsonObject): GraphId {
        val value = stringParam(params, "graphId")
        return try {
            GraphId(value)
        } catch (exception: IllegalArgumentException) {
            throw invalidParams("Invalid graphId: $value")
        }
    }

    private fun nodeIdParam(params: JsonObject): NodeId = nodeId(stringParam(params, "nodeId"), "nodeId")

    private fun nodeId(
        element: JsonElement,
        field: String,
    ): NodeId = nodeId(stringValue(element, field), field)

    private fun nodeId(
        value: String,
        field: String,
    ): NodeId =
        try {
            NodeId(value)
        } catch (exception: IllegalArgumentException) {
            throw invalidParams("Invalid $field: $value")
        }

    private fun stringParam(
        params: JsonObject,
        name: String,
    ): String {
        val value = params[name] ?: throw invalidParams("Missing parameter '$name'")
        return stringValue(value, name)
    }

    private fun stringValue(
        value: JsonElement,
        name: String,
    ): String {
        if (value !is JsonPrimitive || !value.isString) {
            throw invalidParams("Parameter '$name' must be a string")
        }
        return value.content
    }

    private fun response(
        id: JsonElement,
        result: JsonElement,
    ): JsonElement =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    private fun applicationFailure(
        id: JsonElement?,
        hasId: Boolean,
        error: EngineError,
    ): List<JsonElement> =
        if (hasId) {
            listOf(
                errorResponse(
                    id ?: JsonNull,
                    APPLICATION_ERROR,
                    error.message,
                    encode(error),
                ),
            )
        } else {
            listOf(cintError(error))
        }

    private fun protocolFailure(
        id: JsonElement?,
        hasId: Boolean,
        code: Int,
        message: String,
    ): List<JsonElement> =
        if (hasId) {
            listOf(errorResponse(id ?: JsonNull, code, message))
        } else {
            emptyList()
        }

    private fun errorResponse(
        id: JsonElement,
        code: Int,
        message: String,
        data: JsonElement? = null,
    ): JsonElement =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    if (data != null) {
                        put("data", data)
                    }
                },
            )
        }

    private fun cintError(error: EngineError): JsonElement =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "cint/error")
            put(
                "params",
                buildJsonObject {
                    put("error", encode(error))
                },
            )
        }

    private fun parseError(): JsonElement = errorResponse(JsonNull, PARSE_ERROR, "Parse error")

    private fun invalidParams(message: String): RpcException = RpcException(INVALID_PARAMS, message)

    private fun validId(id: JsonElement): Boolean =
        id is JsonNull ||
            (id is JsonPrimitive && (id.isString || id.content.toDoubleOrNull() != null))

    private inline fun <reified T> encode(value: T): JsonElement = GraphJson.json.encodeToJsonElement(value)

    private class RpcException(
        val code: Int,
        message: String,
    ) : RuntimeException(message)

    private companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val APPLICATION_ERROR = -32000
    }
}
