package ai.rever.boss.plugin.dynamic.plugindeps

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tools contributed by the Plugin Deps plugin.
 *
 * Four tools, each a focused slice of the same graph:
 *  - `plugin_deps_graph`         - everything as one JSON document.
 *  - `plugin_deps_blocking_for`  - transitive blocking list for one plugin.
 *  - `plugin_deps_cycles`        - just the cycles, as arrays of plugin ids.
 *  - `plugin_deps_missing`       - declared-but-unloaded deps.
 *
 * The provider re-probes on every call rather than caching, so the result
 * matches the loader state at the moment the agent called. The probe is
 * cheap (one loader call + one I/O read per loaded JAR), and caching would
 * mean the tool lying to the caller when the graph changed mid-session.
 */
internal class PluginDepsMcpToolProvider(
    override val providerId: String,
    private val context: PluginContext,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "plugin_deps_graph",
            description = "Return the full plugin dependency graph as JSON: every loaded plugin as a node, " +
                "edges with required/optional + status, cycles, missing-dependency findings, and a " +
                "degraded-mode banner when the host could not be probed completely.",
            handler = McpToolHandler { graph() },
        ),
        McpToolDefinition(
            name = "plugin_deps_blocking_for",
            description = "Return the transitive list of loaded plugins that would break if the given plugin " +
                "were uninstalled. Optional edges are ignored - this is the host-side blocker view.",
            inputSchema = PLUGIN_ID_SCHEMA,
            handler = McpToolHandler { args -> blockingFor(args) },
        ),
        McpToolDefinition(
            name = "plugin_deps_cycles",
            description = "Return every dependency cycle in the current graph as an array of plugin-id arrays. " +
                "Each entry is one cycle, in walk order; the last node depends on the first.",
            handler = McpToolHandler { cycles() },
        ),
        McpToolDefinition(
            name = "plugin_deps_missing",
            description = "Return every required dependency that no loaded plugin claims, as an array of " +
                "{declarer_id, missing_id} pairs.",
            handler = McpToolHandler { missing() },
        ),
    )

    private suspend fun graph(): McpToolResult {
        val graph = currentGraph()
        val payload = buildJsonObject {
            put("isDegraded", graph.isDegraded)
            if (graph.degradedReason != null) put("degradedReason", graph.degradedReason)
            put("pluginCount", graph.pluginCount)
            put("cycleCount", graph.cycles.size)
            put("missingCount", graph.missingDependencies.size)
            put(
                "plugins",
                buildJsonArray {
                    for (node in graph.sortedNodes) {
                        add(nodeJson(node))
                    }
                },
            )
            put(
                "cycles",
                buildJsonArray {
                    for (cycle in graph.cycles) {
                        add(buildJsonArray {
                            for (id in cycle.nodes) add(JsonPrimitive(id))
                        })
                    }
                },
            )
            put(
                "missing",
                buildJsonArray {
                    for (m in graph.missingDependencies) {
                        add(buildJsonObject {
                            put("declarer_id", m.declarerId)
                            put("missing_id", m.missingId)
                        })
                    }
                },
            )
        }
        return McpToolResult(json.encodeToString(JsonObject.serializer(), payload))
    }

    private suspend fun blockingFor(args: McpToolArgs): McpToolResult {
        val pluginId = args.string("plugin_id")
            ?: return McpToolResult("Missing required argument: plugin_id", isError = true)
        val graph = currentGraph()
        val blockers = graph.blockingDependentsOf(pluginId)
            ?: return McpToolResult(
                "Plugin '$pluginId' is not in the loaded set. Use plugin_deps_graph to list known plugin ids.",
                isError = true,
            )
        val payload = buildJsonObject {
            put("plugin_id", pluginId)
            put("blocking_count", blockers.size)
            put("blocking", buildJsonArray {
                for (id in blockers) add(JsonPrimitive(id))
            })
        }
        return McpToolResult(json.encodeToString(JsonObject.serializer(), payload))
    }

    private suspend fun cycles(): McpToolResult {
        val graph = currentGraph()
        val payload = buildJsonObject {
            put("cycle_count", graph.cycles.size)
            put("cycles", buildJsonArray {
                for (cycle in graph.cycles) {
                    add(buildJsonArray {
                        for (id in cycle.nodes) add(JsonPrimitive(id))
                    })
                }
            })
        }
        return McpToolResult(json.encodeToString(JsonObject.serializer(), payload))
    }

    private suspend fun missing(): McpToolResult {
        val graph = currentGraph()
        val payload = buildJsonObject {
            put("missing_count", graph.missingDependencies.size)
            put("missing", buildJsonArray {
                for (m in graph.missingDependencies) {
                    add(buildJsonObject {
                        put("declarer_id", m.declarerId)
                        put("missing_id", m.missingId)
                    })
                }
            })
        }
        return McpToolResult(json.encodeToString(JsonObject.serializer(), payload))
    }

    /**
     * Project a [PluginNode] into the JSON shape the agent sees.
     *
     * The shape is flat rather than nested; agents consuming this through
     * a typical JSON path benefit from a small, predictable surface.
     */
    private fun nodeJson(node: PluginNode): JsonObject = buildJsonObject {
        put("plugin_id", node.pluginId)
        put("display_name", node.displayName)
        put("version", node.version)
        put("type", node.type)
        put("is_system_plugin", node.isSystemPlugin)
        put("enabled", node.enabled)
        put("healthy", node.healthy)
        put("jar_path", node.jarPath)
        put("required_count", node.requiredCount)
        put("optional_count", node.optionalCount)
        put("blocking_dependents_count", node.blockingDependentsCount)
        put(
            "dependencies",
            buildJsonArray {
                for (dep in node.dependencies) {
                    add(buildJsonObject {
                        put("target_plugin_id", dep.targetPluginId)
                        put("optional", dep.optional)
                        put("status", dep.status.name)
                    })
                }
            },
        )
    }

    /**
     * Re-probe and rebuild the graph snapshot for a single tool call.
     *
     * Reads always go through the same path the panel uses, so an MCP
     * caller sees exactly what a human would see on the panel.
     */
    private suspend fun currentGraph(): DependencyGraph {
        val snapshot = PluginProbe(context).snapshot()
        return DependencyGraph.build(
            loadedPlugins = snapshot.views,
            declaredDependencies = snapshot.dependencies,
            isDegraded = snapshot.isDegraded,
            degradedReason = snapshot.degradedReason,
        )
    }

    private companion object {
        const val PLUGIN_ID_SCHEMA =
            """{"type":"object","properties":{"plugin_id":{"type":"string","description":"Plugin id to compute the blocking list for."}},"required":["plugin_id"]}"""

        val json = kotlinx.serialization.json.Json {
            isLenient = true
            ignoreUnknownKeys = true
        }
    }
}
