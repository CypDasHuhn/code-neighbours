package dev.codenb.lsp

import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import java.time.Duration

sealed class LspException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class LspTimeoutException(
    val method: String,
    val timeout: Duration,
) : LspException("Request $method timed out after $timeout")

class LspTransportException(
    message: String,
    cause: Throwable? = null,
) : LspException(message, cause)

class LspResponseException(
    val method: String,
    val error: ResponseError,
) : LspException("Request $method failed: ${error.message}")

class LspUnsupportedCapabilityException(
    val capability: String,
) : LspException("Server does not support $capability")
