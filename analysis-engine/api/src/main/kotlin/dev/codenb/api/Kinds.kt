package dev.codenb.api

import kotlinx.serialization.Serializable

@Serializable
enum class NodeKind {
    ENTITY,
    FILE,
    GROUP,
}

@Serializable
enum class GroupingKind {
    DIRECTORY,
    NAMESPACE,
}

@Serializable
enum class EdgeKind {
    CONTAINS,
    REFERENCES,
    FILE_REFERENCES,
    GROUP_REFERENCES,
}
