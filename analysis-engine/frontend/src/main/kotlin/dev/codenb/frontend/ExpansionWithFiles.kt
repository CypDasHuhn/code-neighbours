package dev.codenb.frontend

import dev.codenb.api.Expansion
import dev.codenb.api.Node

data class ExpansionWithFiles(
    val expansion: Expansion,
    val fileTexts: Map<String, String>,
    val fileEntities: Map<String, List<Node>> = emptyMap(),
)
