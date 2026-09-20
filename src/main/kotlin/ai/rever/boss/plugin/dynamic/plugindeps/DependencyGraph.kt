package ai.rever.boss.plugin.dynamic.plugindeps

import ai.rever.boss.plugin.api.PluginDependency

/**
 * The status of a single dependency edge, as the panel renders it.
 *
 * Three states that mean very different things and need very different colors:
 *  - RESOLVED     edge points at a loaded, enabled plugin - the green "fine" case
 *  - MISSING      edge points at a pluginId no loaded plugin claims (the orange "warning")
 *  - DISABLED     edge points at a loaded but currently disabled plugin (the gray "won't work")
 */
enum class EdgeStatus { RESOLVED, MISSING, DISABLED }

/**
 * One directed edge in the dependency graph, drawn as a row under a node.
 *
 * Required edges contribute to a plugin's "blockers if I were uninstalled"
 * count and turn red on cycle detection. Optional edges are reported but do
 * not block an uninstall on the host side, so the panel treats them as
 * informational only.
 */
data class DependencyEdge(
    /** The plugin the dependent declares a need for. */
    val targetPluginId: String,
    /** Whether the dependent marked this as optional. */
    val optional: Boolean,
    /** Resolved status of this edge against the current loaded state. */
    val status: EdgeStatus,
)

/**
 * One node in the dependency graph: a loaded plugin and what it points at.
 *
 * The requiredCount / optionalCount are precomputed so the row renderer
 * does not have to walk the edge list twice, and blockingDependentsCount
 * is what the "what would break" drill-down consumes. Both are recomputed
 * by DependencyGraph.build - the per-node fields are a cache, not a source.
 */
data class PluginNode(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val type: String,
    val isSystemPlugin: Boolean,
    val enabled: Boolean,
    val healthy: Boolean,
    val jarPath: String,
    val dependencies: List<DependencyEdge>,
    /** Required deps that resolved cleanly; same length as dependencies minus optionals. */
    val requiredCount: Int,
    /** Optional deps regardless of status. */
    val optionalCount: Int,
    /** Number of OTHER loaded plugins whose required dependency points at this one. */
    val blockingDependentsCount: Int,
)

/**
 * A cycle the walker found: the same plugin appears in the walk more than once,
 * so two plugins are each depending on each other (possibly through a chain).
 *
 * Each entry is the plugin id of one node in the cycle, in walk order. The
 * list wraps: the first id also depends on the last id. Length is at least 2;
 * self-cycles are reported as length-1.
 */
data class CycleReport(val nodes: List<String>)

/**
 * A single "I declared a dep that no loaded plugin claims" finding.
 *
 * Repeated edges (same declarer -> same missing id) are deduped by the
 * DependencyGraph builder, so the list never carries two rows for the
 * same pair.
 */
data class MissingDependency(val declarerId: String, val missingId: String)

/**
 * The minimal view of a loaded plugin the graph builder needs.
 *
 * Lifted out of the API LoadedPluginInfo so the unit test
 * surface for DependencyGraph does not have to fabricate the whole API
 * data class. The host's loader view is mapped into this once, in
 * PluginProbe.
 */
data class LoaderPluginView(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val type: String,
    val isSystemPlugin: Boolean,
    val enabled: Boolean,
    val healthy: Boolean,
    val jarPath: String,
)

/**
 * The full graph snapshot the panel and the MCP tools both render.
 *
 * Built once per refresh; immutable so both consumers can hold a reference
 * without seeing each other's mutations. `nodes` is keyed by pluginId for
 * O(1) lookup from any walker.
 */
data class DependencyGraph(
    val nodes: Map<String, PluginNode>,
    val cycles: List<CycleReport>,
    val missingDependencies: List<MissingDependency>,
    val isDegraded: Boolean,
    val degradedReason: String?,
) {
    /** Number of plugins in the snapshot. */
    val pluginCount: Int get() = nodes.size

    /** All nodes ordered by displayName for stable rendering. */
    val sortedNodes: List<PluginNode> get() = nodes.values.sortedBy { it.displayName.lowercase() }

    /**
     * The transitive set of plugins that would break if pluginId went away.
     *
     * The result is the closure of loaded plugins whose dependency closure
     * passes through `pluginId` - i.e. those that, directly or via a chain,
     * declare a required edge to a plugin that declares a required edge to
     * ... `pluginId`. Optional edges are not blockers on the host side, so
     * they are ignored here.
     *
     * Returns null if pluginId is not in the graph - callers should
     * surface "unknown plugin" rather than an empty list, since the user
     * clearly typed something and an empty answer would be misleading.
     */
    fun blockingDependentsOf(pluginId: String): List<String>? {
        if (pluginId !in nodes) return null
        val visited = LinkedHashSet<String>()
        val stack = ArrayDeque<String>()
        // Seed the stack with every loaded plugin that depends (directly or
        // indirectly) on the target. nodeHasDependencyOn runs the DFS from
        // each candidate; we add it to the result only when that walk reaches
        // the target. Visited guards against redoing work for the same plugin
        // and against cycles turning the closure walk into infinite recursion.
        for (candidateId in nodes.keys) {
            if (candidateId == pluginId) continue
            if (candidateId in visited) continue
            if (nodeHasDependencyOn(pluginId, candidateId)) {
                visited.add(candidateId)
                stack.addLast(candidateId)
            }
        }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (otherId in nodes.keys) {
                if (otherId == pluginId) continue
                if (otherId in visited) continue
                if (otherId == current) continue
                val node = nodes[otherId] ?: continue
                val needsCurrent = node.dependencies.any { dep ->
                    !dep.optional && dep.targetPluginId == current
                }
                if (needsCurrent) {
                    visited.add(otherId)
                    stack.addLast(otherId)
                }
            }
        }
        return visited.toList()
    }

    /**
     * Whether dependentId has a direct or indirect required path to targetId.
     *
     * Iterative DFS with a visited set, so cycles (which the panel already
     * red-flags separately) do not become infinite loops here. Optional edges
     * are again ignored.
     */
    private fun nodeHasDependencyOn(targetId: String, dependentId: String): Boolean {
        val visited = HashSet<String>()
        val stack = ArrayDeque<String>().also { it.addLast(dependentId) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current == targetId) return true
            if (current in visited) continue
            visited.add(current)
            val node = nodes[current] ?: continue
            for (dep in node.dependencies) {
                if (dep.optional) continue
                if (dep.targetPluginId !in visited) stack.addLast(dep.targetPluginId)
            }
        }
        return false
    }

    companion object {
        /**
         * Build a graph snapshot from the loader's view of the loaded plugins
         * and the dependency declarations carried by each loaded manifest.
         *
         * loadedPlugins is the loader's getLoadedPlugins() output; each
         * entry carries a pluginId and an enabled flag. declaredDependencies
         * maps pluginId -> the manifest's dependency list. The two maps do not
         * have to cover the same ids: a plugin in the loaded set with no
         * entry in declaredDependencies is treated as having no deps.
         *
         * isDegraded / degradedReason flow through to the snapshot so the
         * panel can show a banner explaining that the graph is partial.
         */
        fun build(
            loadedPlugins: List<LoaderPluginView>,
            declaredDependencies: Map<String, List<PluginDependency>>,
            isDegraded: Boolean = false,
            degradedReason: String? = null,
        ): DependencyGraph {
            val loadedIds: Set<String> = loadedPlugins.map { it.pluginId }.toSet()
            val enabledIds: Set<String> = loadedPlugins.filter { it.enabled }.map { it.pluginId }.toSet()

            // First pass: nodes with edges, before counting blockingDependents.
            val nodes = linkedMapOf<String, PluginNode>()
            for (loaded in loadedPlugins) {
                val declared = declaredDependencies[loaded.pluginId].orEmpty()
                val edges = declared.map { dep ->
                    val status = when {
                        dep.pluginId !in loadedIds -> EdgeStatus.MISSING
                        dep.pluginId !in enabledIds -> EdgeStatus.DISABLED
                        else -> EdgeStatus.RESOLVED
                    }
                    DependencyEdge(
                        targetPluginId = dep.pluginId,
                        optional = dep.optional,
                        status = status,
                    )
                }
                nodes[loaded.pluginId] = PluginNode(
                    pluginId = loaded.pluginId,
                    displayName = loaded.displayName,
                    version = loaded.version,
                    type = loaded.type,
                    isSystemPlugin = loaded.isSystemPlugin,
                    enabled = loaded.enabled,
                    healthy = loaded.healthy,
                    jarPath = loaded.jarPath,
                    dependencies = edges,
                    requiredCount = edges.count { !it.optional },
                    optionalCount = edges.count { it.optional },
                    blockingDependentsCount = 0,
                )
            }

            // Second pass: count required incoming edges per node.
            val blocking = HashMap<String, Int>()
            for (node in nodes.values) {
                for (dep in node.dependencies) {
                    if (dep.optional) continue
                    if (dep.status != EdgeStatus.RESOLVED) continue
                    blocking[dep.targetPluginId] = (blocking[dep.targetPluginId] ?: 0) + 1
                }
            }
            val withCounts = nodes.mapValues { (id, node) ->
                node.copy(blockingDependentsCount = blocking[id] ?: 0)
            }

            // Cycles: walk each node, follow required edges, and flag any node
            // visited twice in the same walk.
            val cycles = detectCycles(withCounts)

            // Missing: required edges whose target is not loaded.
            val missing = mutableListOf<MissingDependency>()
            for (node in withCounts.values) {
                for (dep in node.dependencies) {
                    if (dep.status == EdgeStatus.MISSING && !dep.optional) {
                        missing.add(MissingDependency(declarerId = node.pluginId, missingId = dep.targetPluginId))
                    }
                }
            }
            missing.sortBy { it.declarerId + "|" + it.missingId }

            return DependencyGraph(
                nodes = withCounts,
                cycles = cycles,
                missingDependencies = missing,
                isDegraded = isDegraded,
                degradedReason = degradedReason,
            )
        }

        private fun detectCycles(nodes: Map<String, PluginNode>): List<CycleReport> {
            val cycles = mutableListOf<CycleReport>()
            val reported = HashSet<String>()
            for (startId in nodes.keys) {
                // DFS over required edges; record any node we revisit while
                // still on the active path as the closure of a cycle.
                val path = ArrayList<String>()
                val onPath = HashSet<String>()
                val stack = ArrayDeque<Pair<String, Int>>()
                stack.addLast(startId to 0)
                while (stack.isNotEmpty()) {
                    val (current, idx) = stack.removeLast()
                    while (path.size > idx) {
                        val popped = path.removeAt(path.size - 1)
                        onPath.remove(popped)
                    }
                    path.add(current)
                    onPath.add(current)
                    val node = nodes[current] ?: continue
                    val nextEdge = node.dependencies
                        .asSequence()
                        .filter { !it.optional && it.status == EdgeStatus.RESOLVED }
                        .map { it.targetPluginId }
                        .filter { it in nodes }
                        .firstOrNull() ?: continue
                    if (nextEdge in onPath) {
                        val cycleStart = path.indexOf(nextEdge)
                        if (cycleStart >= 0) {
                            val cycleNodes = path.subList(cycleStart, path.size).toList()
                            val key = cycleNodes.sortedBy { it }.joinToString("|")
                            if (reported.add(key)) {
                                cycles.add(CycleReport(cycleNodes))
                            }
                        }
                    }
                    stack.addLast(nextEdge to path.size)
                }
            }
            return cycles
        }
    }
}
