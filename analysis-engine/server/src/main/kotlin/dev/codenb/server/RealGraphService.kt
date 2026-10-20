package dev.codenb.server

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKind
import dev.codenb.api.EngineError
import dev.codenb.api.EngineErrorCode
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.GraphInfo
import dev.codenb.api.GroupingKind
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.api.PROTOCOL_VERSION
import dev.codenb.core.CacheKey
import dev.codenb.core.DotWriter
import dev.codenb.core.GraphSnapshot
import dev.codenb.core.GraphStore
import dev.codenb.core.GroupingPolicy
import dev.codenb.core.ReferenceAggregation
import dev.codenb.frontend.ContainmentPipeline
import dev.codenb.frontend.KotlinContainmentProvider
import dev.codenb.frontend.KotlinEntityDiscovery
import dev.codenb.frontend.LanguageFrontend
import dev.codenb.frontend.NeighbourExpander
import dev.codenb.lsp.LspClient
import dev.codenb.lsp.LspException
import dev.codenb.lsp.LspSession
import dev.codenb.lsp.LspUnsupportedCapabilityException
import dev.codenb.lsp.WorkspaceException
import dev.codenb.lsp.WorkspaceFiles
import org.eclipse.lsp4j.DocumentSymbol
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface GraphLsp :
    LspSession,
    AutoCloseable {
    fun initialize(root: Path)
}

private class LspClientSession(
    private val client: LspClient,
) : GraphLsp {
    override val semanticTokensLegend get() = client.semanticTokensLegend

    override fun initialize(root: Path) {
        client.initialize(root)
    }

    override fun didOpen(
        uri: String,
        languageId: String,
        text: String,
        version: Int,
    ) {
        client.didOpen(uri, languageId, text, version)
    }

    override fun didClose(uri: String) {
        client.didClose(uri)
    }

    override fun openDocument(uri: String) = client.openDocument(uri)

    override fun documentSymbol(uri: String) = client.documentSymbol(uri)

    override fun references(
        uri: String,
        position: org.eclipse.lsp4j.Position,
        includeDeclaration: Boolean,
    ) = client.references(uri, position, includeDeclaration)

    override fun definition(
        uri: String,
        position: org.eclipse.lsp4j.Position,
    ) = client.definition(uri, position)

    override fun semanticTokensFull(uri: String) = client.semanticTokensFull(uri)

    override fun close() {
        client.close()
    }
}

class RealGraphService(
    root: Path,
    private val frontend: LanguageFrontend,
    private val config: EngineConfig,
    private val clientFactory: (List<String>, Path) -> GraphLsp = { command, directory ->
        LspClientSession(LspClient.start(command, directory))
    },
    private val revisionProvider: ContentRevisionProvider =
        ContentRevisionProvider { base, paths ->
            ContentRevisions.hash(base, paths)
        },
    private val commitGate: CommitGate = CommitGate {},
) : GraphService {
    private val root: Path = root.toAbsolutePath().normalize()
    private val graphId = GraphId("graph/${frontend.id}/${sha256(this.root.toString()).take(16)}")
    private val groupingKind: GroupingKind = config.graph.grouping
    private val lock = ReentrantLock(true)
    private var policy = GroupingPolicy()
    private var store = GraphStore()

    @Volatile private var client: GraphLsp? = null

    private var graphRevision: String? = null
    private var workspacePaths: Set<Path>? = null
    private val discoveredFiles = linkedMapOf<String, MutableMap<String, List<Node>>>()

    @Volatile private var closed = false

    private val clientLock = Any()

    private val graphInfo =
        GraphInfo(
            graphId = graphId,
            root = this.root.toString(),
            lspId = frontend.id,
            groupingKind = groupingKind,
            nodeKinds = NodeKind.entries,
            edgeKinds = EdgeKind.entries,
            protocolVersion = PROTOCOL_VERSION,
        )

    override fun info(): GraphInfo = graphInfo

    override fun status(): GraphStatus =
        lock.withLock {
            val revision = graphRevision
            if (closed || revision == null) {
                GraphStatus(false, null)
            } else {
                val current = runCatching { contentRevision(discoverWorkspace()) }.getOrNull()
                synchronized(clientLock) {
                    when {
                        closed -> GraphStatus(false, null)
                        current == revision -> GraphStatus(true, revision)
                        else -> GraphStatus(false, revision)
                    }
                }
            }
        }

    override fun expandableNodeKinds(): List<NodeKind> = listOf(NodeKind.ENTITY)

    internal fun snapshotForTest(): GraphSnapshot = store.snapshot(graphId)

    internal fun cachedExpansionForTest(
        nodeId: NodeId,
        revision: String,
    ): Expansion? = store.cache.get(CacheKey(graphId, nodeId, revision))

    internal fun discoveredForTest(
        revision: String,
        uri: String,
    ): List<Node>? = peekDiscovery(revision, uri)

    override fun open(seed: Seed): Expansion =
        guarded {
            lock.withLock {
                checkOpen()
                when (seed) {
                    is Seed.ByNodeId -> openNode(seed.nodeId)
                    is Seed.Selector -> openSelector(seed)
                }
            }
        }

    override fun expand(
        graphId: GraphId,
        nodeId: NodeId,
        revision: String,
    ): Expansion =
        guarded {
            lock.withLock {
                checkOpen()
                if (graphId != this.graphId) {
                    throw unknownGraph(graphId)
                }
                val current = requireOpen()
                val paths = discoverWorkspace()
                val actual = contentRevision(paths)
                if (actual != current) {
                    throw staleGraph(current, actual)
                }
                if (revision != current) {
                    throw engineException(
                        EngineErrorCode.INVALID_GRAPH,
                        "Revision $revision is no longer current; reopen the graph",
                        "revision" to revision,
                        "currentRevision" to current,
                    )
                }
                val snapshot = store.snapshot(this.graphId)
                val node = snapshot.nodes.firstOrNull { it.id == nodeId } ?: throw unknownNode(nodeId)
                if (node.kind != NodeKind.ENTITY) {
                    throw unsupportedNodeKind(node.kind)
                }
                var stagedDiscoveries: Map<String, List<Node>> = emptyMap()
                val expansion =
                    expandThroughCache(nodeId, current) {
                        val computed = computeEntityExpansion(ensureClient(), paths, node, current, policy)
                        stagedDiscoveries = computed.discoveries
                        requireRevisionUnchanged(current)
                        checkOpen()
                        computed.expansion
                    }
                checkOpen()
                requireRevisionUnchanged(current)
                commitDiscoveries(current, stagedDiscoveries)
                currentExpansion(nodeId, current, expansion)
            }
        }

    override fun node(
        graphId: GraphId,
        nodeId: NodeId,
    ): Expansion =
        guarded {
            lock.withLock {
                checkOpen()
                if (graphId != this.graphId) {
                    throw unknownGraph(graphId)
                }
                val current = requireOpen()
                val paths = discoverWorkspace()
                val actual = contentRevision(paths)
                if (actual != current) {
                    throw staleGraph(current, actual)
                }
                checkOpen()
                val snapshot = store.snapshot(this.graphId)
                val node = snapshot.nodes.firstOrNull { it.id == nodeId } ?: throw unknownNode(nodeId)
                val cached = store.cache.get(CacheKey(this.graphId, nodeId, current))
                val result =
                    when {
                        cached != null -> currentExpansion(nodeId, current, cached)
                        node.kind == NodeKind.ENTITY -> fullEntityExpansion(snapshot, node, current)
                        else -> storedExpansion(snapshot, node, current)
                    }
                synchronized(clientLock) { checkOpen() }
                result
            }
        }

    override fun dot(graphId: GraphId): String =
        guarded {
            lock.withLock {
                checkOpen()
                if (graphId != this.graphId) {
                    throw unknownGraph(graphId)
                }
                requireOpen()
                DotWriter.write(store.snapshot(this.graphId))
            }
        }

    override fun close() {
        var locked = false
        try {
            lock.lockInterruptibly()
            locked = true
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            val current =
                synchronized(clientLock) {
                    if (closed) {
                        return
                    }
                    closed = true
                    client.also { client = null }
                }
            runCatching { current?.close() }
        } finally {
            if (locked) {
                lock.unlock()
            }
        }
    }

    private fun openNode(nodeId: NodeId): Expansion {
        val current = requireOpen()
        val paths = discoverWorkspace()
        val actual = contentRevision(paths)
        if (actual != current) {
            throw staleGraph(current, actual)
        }
        val snapshot = store.snapshot(graphId)
        val node = snapshot.nodes.firstOrNull { it.id == nodeId } ?: throw unknownNode(nodeId)
        if (node.kind != NodeKind.ENTITY) {
            throw unsupportedNodeKind(node.kind)
        }
        var stagedDiscoveries: Map<String, List<Node>> = emptyMap()
        val expansion =
            expandThroughCache(node.id, current) {
                val computed = computeEntityExpansion(ensureClient(), paths, node, current, policy)
                stagedDiscoveries = computed.discoveries
                requireRevisionUnchanged(current)
                checkOpen()
                computed.expansion
            }
        checkOpen()
        requireRevisionUnchanged(current)
        commitDiscoveries(current, stagedDiscoveries)
        return currentExpansion(node.id, current, expansion)
    }

    private fun openSelector(seed: Seed.Selector): Expansion {
        validateSelector(seed)
        var lastRevision: String? = null
        var lastActual: String? = null
        repeat(MAX_OPEN_ATTEMPTS) {
            val paths = discoverWorkspace()
            val revision = contentRevision(paths)
            try {
                return if (graphRevision == revision) {
                    openWithCurrentRevision(seed, paths, revision)
                } else {
                    openChangedRevision(seed, paths, revision)
                }
            } catch (exception: ContentChanged) {
                lastRevision = revision
                lastActual = exception.actual
            }
        }
        val revision = graphRevision ?: lastRevision ?: contentRevision(discoverWorkspace())
        val actual = lastActual ?: contentRevision(discoverWorkspace())
        throw staleGraph(revision, actual)
    }

    private fun openChangedRevision(
        seed: Seed.Selector,
        paths: Set<Path>,
        revision: String,
    ): Expansion {
        val candidatePolicy = GroupingPolicy()
        val node = resolveFileSeed(seed, paths, revision)
        val computed = computeEntityExpansion(ensureClient(), paths, node, revision, candidatePolicy)
        val actual = changedRevision(revision)
        if (actual != null) {
            throw ContentChanged(actual)
        }
        checkOpen()
        commitGate.beforeCommit()
        synchronized(clientLock) {
            checkOpen()
            commitDiscoveriesLocked(revision, computed.discoveries)
            val candidateStore = GraphStore()
            candidateStore.merge(computed.expansion)
            candidateStore.cache.put(CacheKey(graphId, node.id, revision), computed.expansion)
            store = candidateStore
            policy = candidatePolicy
            workspacePaths = paths
            graphRevision = revision
        }
        return computed.expansion
    }

    private fun openWithCurrentRevision(
        seed: Seed.Selector,
        paths: Set<Path>,
        revision: String,
    ): Expansion {
        val node = seedForCurrentRevision(seed, paths, revision)
        var stagedDiscoveries: Map<String, List<Node>> = emptyMap()
        val expansion =
            expandThroughCache(node.id, revision) {
                val computed = computeEntityExpansion(ensureClient(), paths, node, revision, policy)
                stagedDiscoveries = computed.discoveries
                val actual = changedRevision(revision)
                if (actual != null) {
                    throw ContentChanged(actual)
                }
                checkOpen()
                computed.expansion
            }
        val actual = changedRevision(revision)
        if (actual != null) {
            throw ContentChanged(actual)
        }
        checkOpen()
        commitDiscoveries(revision, stagedDiscoveries)
        workspacePaths = paths
        return currentExpansion(node.id, revision, expansion)
    }

    private fun validateSelector(seed: Seed.Selector) {
        if (seed.file.isBlank()) {
            throw engineException(EngineErrorCode.INVALID_REQUEST, "Seed file must not be blank")
        }
        if (seed.qualifiedName.isBlank()) {
            throw engineException(EngineErrorCode.INVALID_REQUEST, "Seed qualifiedName must not be blank")
        }
        if (seed.signatureFingerprint != null && seed.signatureFingerprint.isBlank()) {
            throw engineException(EngineErrorCode.INVALID_REQUEST, "Seed signatureFingerprint must not be blank")
        }
    }

    private fun selectorPath(
        seed: Seed.Selector,
        paths: Set<Path>,
    ): Path {
        val requested =
            try {
                Path.of(seed.file)
            } catch (exception: IllegalArgumentException) {
                throw engineException(
                    EngineErrorCode.INVALID_REQUEST,
                    "Seed file is not a valid path: ${seed.file}",
                    "file" to seed.file,
                )
            }
        val path = (if (requested.isAbsolute) requested else root.resolve(requested)).toAbsolutePath().normalize()
        if (!path.startsWith(root) || !paths.contains(path)) {
            throw engineException(
                EngineErrorCode.WORKSPACE_ERROR,
                "Seed file is outside the configured workspace: $path",
                "file" to path.toString(),
            )
        }
        if (!Files.isRegularFile(path)) {
            throw engineException(
                EngineErrorCode.WORKSPACE_ERROR,
                "Seed file does not exist: $path",
                "file" to path.toString(),
            )
        }
        if (!frontend.handles(path.fileName.toString())) {
            throw engineException(
                EngineErrorCode.WORKSPACE_ERROR,
                "Seed file is not handled by ${frontend.id}: $path",
                "file" to path.toString(),
            )
        }
        return path
    }

    private fun resolveFileSeed(
        seed: Seed.Selector,
        paths: Set<Path>,
        revision: String,
    ): Node = resolveFileSeedAtPath(seed, selectorPath(seed, paths), revision)

    private fun resolveFileSeedAtPath(
        seed: Seed.Selector,
        path: Path,
        revision: String,
    ): Node {
        val uri = path.toUri().toString()
        cachedDiscovery(revision, uri)?.let { cached ->
            return selectSeed(cached, seed, path)
        }
        val session = ensureClient()
        val open = session.openDocument(uri)
        val text = open?.text ?: Files.readString(path, StandardCharsets.UTF_8)
        var opened = false
        val discovered =
            try {
                if (open == null) {
                    session.didOpen(uri, frontend.languageId, text)
                    opened = true
                }
                val symbols = session.documentSymbol(uri).mapNotNull { it.right }
                discover(uri, text, symbols)
            } finally {
                if (opened) {
                    runCatching { session.didClose(uri) }
                }
            }
        val nodes = discovered.map { it.node }
        val actual = changedRevision(revision)
        if (actual != null) {
            throw ContentChanged(actual)
        }
        commitDiscoveries(revision, mapOf(uri to nodes))
        return selectSeed(nodes, seed, path)
    }

    private fun seedForCurrentRevision(
        seed: Seed.Selector,
        paths: Set<Path>,
        revision: String,
    ): Node {
        val path = selectorPath(seed, paths)
        if (seed.signatureFingerprint != null) {
            seedFromStore(seed, path)?.let { return it }
        }
        cachedDiscovery(revision, path.toUri().toString())?.let { cached ->
            return selectSeed(cached, seed, path)
        }
        return resolveFileSeedAtPath(seed, path, revision)
    }

    private fun selectSeed(
        discovered: List<Node>,
        seed: Seed.Selector,
        path: Path,
    ): Node {
        val matches =
            discovered
                .filter { it.qualifiedName == seed.qualifiedName }
                .filter { seed.signatureFingerprint == null || it.signatureFingerprint == seed.signatureFingerprint }
        return when {
            matches.isEmpty() ->
                throw engineException(
                    EngineErrorCode.UNKNOWN_NODE,
                    "No entity '${seed.qualifiedName}' in ${path.fileName}",
                    "file" to path.toString(),
                    "qualifiedName" to seed.qualifiedName,
                )
            matches.size > 1 -> throw ambiguousSeed(matches)
            else -> matches.single()
        }
    }

    private fun ambiguousSeed(matches: List<Node>): EngineException {
        val details = mutableMapOf("candidateCount" to matches.size.toString())
        matches.forEachIndexed { index, candidate ->
            details["candidates.$index.id"] = candidate.id.value
            details["candidates.$index.fingerprint"] = candidate.signatureFingerprint.orEmpty()
        }
        return EngineException(
            EngineError(
                EngineErrorCode.INVALID_REQUEST,
                "Seed selector is ambiguous; provide signatureFingerprint",
                details,
            ),
        )
    }

    private fun cacheDiscovery(
        revision: String,
        uri: String,
        discovered: List<Node>,
    ) {
        val existing = discoveredFiles.remove(revision)
        if (existing == null) {
            while (discoveredFiles.size >= MAX_DISCOVERY_REVISIONS) {
                val victim = discoveredFiles.keys.firstOrNull { it != graphRevision } ?: break
                discoveredFiles.remove(victim)
            }
        }
        val entries = existing ?: mutableMapOf()
        entries[uri] = discovered
        discoveredFiles[revision] = entries
    }

    private fun cachedDiscovery(
        revision: String,
        uri: String,
    ): List<Node>? {
        val entries = discoveredFiles[revision] ?: return null
        discoveredFiles.remove(revision)
        discoveredFiles[revision] = entries
        return entries[uri]
    }

    private fun peekDiscovery(
        revision: String,
        uri: String,
    ): List<Node>? = discoveredFiles[revision]?.get(uri)

    private fun discover(
        uri: String,
        text: String,
        symbols: List<DocumentSymbol>,
    ): List<KotlinEntityDiscovery.DiscoveredEntity> {
        if (frontend.id != KOTLIN) {
            throw engineException(
                EngineErrorCode.UNSUPPORTED_CAPABILITY,
                "Entity discovery is not implemented for ${frontend.id}",
            )
        }
        return KotlinEntityDiscovery.discoverDetailed(graphId, uri, text, symbols)
    }

    private fun ensureClient(): GraphLsp {
        val existing =
            synchronized(clientLock) {
                if (closed) {
                    throw engineException(EngineErrorCode.INVALID_GRAPH, "Graph service is closed")
                }
                client
            }
        existing?.let { return it }
        if (!Files.isDirectory(root)) {
            throw engineException(EngineErrorCode.WORKSPACE_ERROR, "Workspace root is not a directory: $root")
        }
        val command = config.lsp.command.ifEmpty { frontend.defaultCommand }
        val started = clientFactory(command, root)
        if (closed) {
            runCatching { started.close() }
            throw engineException(EngineErrorCode.INVALID_GRAPH, "Graph service is closed")
        }
        try {
            started.initialize(root)
            synchronized(clientLock) {
                if (closed) {
                    runCatching { started.close() }
                    throw engineException(EngineErrorCode.INVALID_GRAPH, "Graph service is closed")
                }
                client = started
            }
            return started
        } catch (exception: Throwable) {
            runCatching { started.close() }
            throw exception
        }
    }

    private fun discoverWorkspace(): Set<Path> {
        if (!Files.isDirectory(root)) {
            throw engineException(EngineErrorCode.WORKSPACE_ERROR, "Workspace root is not a directory: $root")
        }
        return WorkspaceFiles
            .discover(
                root,
                include = config.workspace.include,
                exclude = config.workspace.exclude,
                followSymlinks = config.graph.followSymlinks,
            ).map { it.absolutePath.toAbsolutePath().normalize() }
            .toSet()
    }

    private fun ensureWorkspace(): Set<Path> = workspacePaths ?: discoverWorkspace()

    private fun contentRevision(paths: Set<Path>): String = revisionProvider.revision(root, paths)

    private fun changedRevision(revision: String): String? {
        val actual = contentRevision(discoverWorkspace())
        return if (actual == revision) null else actual
    }

    private fun requireRevisionUnchanged(revision: String) {
        changedRevision(revision)?.let { actual ->
            throw staleGraph(revision, actual)
        }
    }

    private fun seedFromStore(
        seed: Seed.Selector,
        path: Path,
    ): Node? {
        val uri = path.toUri().toString()
        return store
            .snapshot(graphId)
            .nodes
            .filter { node ->
                node.kind == NodeKind.ENTITY &&
                    node.fileUri == uri &&
                    node.qualifiedName == seed.qualifiedName &&
                    node.signatureFingerprint == seed.signatureFingerprint
            }.singleOrNull()
    }

    private class ContentChanged(
        val actual: String,
    ) : RuntimeException()

    private data class ComputedExpansion(
        val expansion: Expansion,
        val discoveries: Map<String, List<Node>>,
    )

    private fun computeEntityExpansion(
        session: LspSession,
        paths: Set<Path>,
        node: Node,
        revision: String,
        groupingPolicy: GroupingPolicy,
    ): ComputedExpansion {
        if (frontend.id != KOTLIN) {
            throw engineException(
                EngineErrorCode.UNSUPPORTED_CAPABILITY,
                "Expansion is not implemented for ${frontend.id}",
            )
        }
        val withFiles = NeighbourExpander(session, paths).expandWithFiles(graphId, node, root, frontend, revision)
        val expansion =
            ContainmentPipeline.apply(
                withFiles,
                root,
                groupingKind,
                groupingPolicy,
                KotlinContainmentProvider,
            )
        return ComputedExpansion(expansion, withFiles.fileEntities)
    }

    private fun expandThroughCache(
        nodeId: NodeId,
        revision: String,
        compute: () -> Expansion,
    ): Expansion {
        val key = CacheKey(graphId, nodeId, revision)
        store.cache.get(key)?.let { return it }
        val computed = compute()
        commitGate.beforeCommit()
        synchronized(clientLock) {
            checkOpen()
            store.merge(computed)
            store.cache.put(key, computed)
        }
        return computed
    }

    private fun commitDiscoveries(
        revision: String,
        discoveries: Map<String, List<Node>>,
    ) {
        if (discoveries.isEmpty()) {
            return
        }
        synchronized(clientLock) {
            checkOpen()
            commitDiscoveriesLocked(revision, discoveries)
        }
    }

    private fun commitDiscoveriesLocked(
        revision: String,
        discoveries: Map<String, List<Node>>,
    ) {
        discoveries.forEach { (uri, nodes) -> cacheDiscovery(revision, uri, nodes) }
    }

    private fun currentExpansion(
        nodeId: NodeId,
        revision: String,
        expansion: Expansion,
    ): Expansion {
        checkOpen()
        val snapshot = store.snapshot(graphId)
        val node = snapshot.nodes.firstOrNull { it.id == nodeId } ?: throw unknownNode(nodeId)
        val current =
            if (node.kind == NodeKind.ENTITY) {
                fullEntityExpansion(snapshot, node, revision)
            } else {
                storedExpansion(snapshot, node, revision)
            }
        if (expansion == current) {
            synchronized(clientLock) { checkOpen() }
            return expansion
        }
        commitGate.beforeCommit()
        synchronized(clientLock) {
            checkOpen()
            if (node.kind == NodeKind.ENTITY) {
                store.merge(current)
                store.cache.put(CacheKey(graphId, nodeId, revision), current)
            }
        }
        return current
    }

    private fun fullEntityExpansion(
        snapshot: GraphSnapshot,
        node: Node,
        revision: String,
    ): Expansion {
        val nodesById = snapshot.nodes.associateBy { it.id }
        val incidentReferences =
            snapshot.edges.filter { edge ->
                edge.kind == EdgeKind.REFERENCES && (edge.from == node.id || edge.to == node.id)
            }
        val entityIds =
            (listOf(node.id) + incidentReferences.flatMap { edge -> listOf(edge.from, edge.to) }).toSet()
        val entities = linkedMapOf<NodeId, Node>()
        entities[node.id] = node
        entityIds
            .filter { it != node.id }
            .mapNotNull { id -> nodesById[id]?.takeIf { it.kind == NodeKind.ENTITY } }
            .forEach { entity -> entities[entity.id] = entity }
        val files = linkedMapOf<NodeId, Node>()
        val groups = linkedMapOf<NodeId, Node>()
        val entityFile = mutableMapOf<NodeId, NodeId>()
        val fileGroup = mutableMapOf<NodeId, NodeId>()
        entities.values.forEach { entity ->
            val file =
                entity.parent
                    ?.let { parent -> nodesById[parent] }
                    ?.takeIf { parent -> parent.kind == NodeKind.FILE }
                    ?: snapshot.nodes.firstOrNull { candidate ->
                        candidate.kind == NodeKind.FILE && candidate.fileUri == entity.fileUri
                    }
            if (file != null) {
                files[file.id] = file
                entityFile[entity.id] = file.id
                val group =
                    file.parent
                        ?.let { parent -> nodesById[parent] }
                        ?.takeIf { parent -> parent.kind == NodeKind.GROUP }
                if (group != null) {
                    groups[group.id] = group
                    fileGroup[file.id] = group.id
                }
            }
        }
        val referenceEdges =
            snapshot.edges.filter { edge ->
                edge.kind == EdgeKind.REFERENCES && edge.from in entityIds && edge.to in entityIds
            }
        val allAggregationEdges =
            ReferenceAggregation.edges(
                snapshot.nodes,
                snapshot.edges.filter { it.kind == EdgeKind.REFERENCES },
                files.keys,
            )
        val aggregationEdges =
            allAggregationEdges.filter { edge ->
                when (edge.kind) {
                    EdgeKind.FILE_REFERENCES -> edge.from in files.keys && edge.to in files.keys
                    EdgeKind.GROUP_REFERENCES -> edge.from in groups.keys && edge.to in groups.keys
                    else -> false
                }
            }
        val containmentEdges =
            entityFile.map { (entityId, fileId) -> Edge.of(EdgeKind.CONTAINS, entityId, fileId, 1) } +
                fileGroup.map { (fileId, groupId) -> Edge.of(EdgeKind.CONTAINS, fileId, groupId, 1) }
        val neighbours =
            (entities.values + files.values + groups.values)
                .filter { candidate -> candidate.id != node.id }
                .sortedBy { candidate -> candidate.id.value }
        val edges = (referenceEdges + containmentEdges + aggregationEdges).sortedWith(EDGE_ORDER)
        return Expansion(graphId, revision, node, neighbours, edges)
    }

    private fun storedExpansion(
        snapshot: GraphSnapshot,
        node: Node,
        revision: String,
    ): Expansion {
        val edges = snapshot.edges.filter { it.from == node.id || it.to == node.id }
        return Expansion(
            graphId = graphId,
            revision = revision,
            node = node,
            neighbours = neighbours(snapshot, node.id, edges),
            edges = edges,
        )
    }

    private fun neighbours(
        snapshot: GraphSnapshot,
        nodeId: NodeId,
        edges: List<Edge>,
    ): List<Node> {
        val ids =
            edges
                .flatMap { edge -> listOf(edge.from, edge.to) }
                .filter { it != nodeId }
                .toSet()
        return snapshot.nodes
            .filter { it.id in ids }
            .sortedBy { it.id.value }
    }

    private fun checkOpen() {
        if (closed) {
            throw engineException(EngineErrorCode.INVALID_GRAPH, "Graph service is closed")
        }
    }

    private fun requireOpen(): String =
        graphRevision
            ?: throw engineException(EngineErrorCode.INVALID_GRAPH, "Graph is not open")

    private fun unknownGraph(graphId: GraphId): EngineException =
        engineException(
            EngineErrorCode.INVALID_GRAPH,
            "Unknown graph ${graphId.value}",
            "graphId" to graphId.value,
        )

    private fun unknownNode(nodeId: NodeId): EngineException =
        engineException(
            EngineErrorCode.UNKNOWN_NODE,
            "Unknown node ${nodeId.value}",
            "nodeId" to nodeId.value,
        )

    private fun staleGraph(
        graphRevision: String,
        contentRevision: String,
    ): EngineException =
        engineException(
            EngineErrorCode.INVALID_GRAPH,
            "Workspace content changed; reopen the graph with a selector",
            "revision" to graphRevision,
            "currentRevision" to contentRevision,
        )

    private fun unsupportedNodeKind(kind: NodeKind): EngineException =
        engineException(
            EngineErrorCode.UNSUPPORTED_CAPABILITY,
            "Expansion of $kind nodes is not implemented",
            "kind" to kind.name,
        )

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (exception: EngineException) {
            throw exception
        } catch (exception: LspUnsupportedCapabilityException) {
            throw EngineException(
                EngineError(
                    EngineErrorCode.UNSUPPORTED_CAPABILITY,
                    exception.message ?: "Unsupported LSP capability",
                    mapOf("capability" to exception.capability),
                ),
                exception,
            )
        } catch (exception: LspException) {
            throw EngineException(
                EngineError(EngineErrorCode.LSP_FAILURE, exception.message ?: "LSP request failed"),
                exception,
            )
        } catch (exception: WorkspaceException) {
            throw EngineException(
                EngineError(EngineErrorCode.WORKSPACE_ERROR, exception.message ?: "Workspace error"),
                exception,
            )
        } catch (exception: IOException) {
            throw EngineException(
                EngineError(EngineErrorCode.WORKSPACE_ERROR, exception.message ?: "Workspace error"),
                exception,
            )
        } catch (exception: IllegalArgumentException) {
            throw EngineException(
                EngineError(EngineErrorCode.INTERNAL, exception.message ?: "Internal invariant failed"),
                exception,
            )
        } catch (exception: IllegalStateException) {
            throw EngineException(
                EngineError(EngineErrorCode.INTERNAL, exception.message ?: "Internal invariant failed"),
                exception,
            )
        } catch (exception: Throwable) {
            throw EngineException(
                EngineError(EngineErrorCode.INTERNAL, exception.message ?: "Internal error"),
                exception,
            )
        }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private companion object {
        const val KOTLIN = "kotlin"
        const val MAX_OPEN_ATTEMPTS = 2
        const val MAX_DISCOVERY_REVISIONS = 16

        val EDGE_ORDER: Comparator<Edge> =
            compareBy<Edge> { it.kind.name }
                .thenBy { it.from.value }
                .thenBy { it.to.value }
    }
}
