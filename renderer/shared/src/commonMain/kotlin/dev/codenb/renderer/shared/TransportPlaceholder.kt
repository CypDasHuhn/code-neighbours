package dev.codenb.renderer.shared

import kotlinx.serialization.Serializable

@Serializable
data class TransportPlaceholder(
    val protocolVersion: Int,
)
