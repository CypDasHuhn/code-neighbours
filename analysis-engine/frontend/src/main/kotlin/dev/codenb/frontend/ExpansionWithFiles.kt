package dev.codenb.frontend

import dev.codenb.api.Expansion

data class ExpansionWithFiles(
    val expansion: Expansion,
    val fileTexts: Map<String, String>,
)
