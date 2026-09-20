package ai.rever.boss.plugin.dynamic.plugindeps

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Plugin Deps dynamic plugin - Loaded from external JAR.
 *
 * Surfaces a visual dependency graph of every loaded plugin in BOSS. The
 * panel renders the graph for humans; the MCP tools render the same data
 * as JSON for in-terminal agents. Both consume the same [PluginProbe] /
 * [DependencyGraph] pipeline, so the two surfaces cannot drift.
 *
 * Plugin-to-plugin reach is not provided - the panel only needs the loader
 * delegate, which is fetched lazily through `context.getPluginAPI(...)`
 * and may be null on older host builds. The probe handles that gracefully.
 */
class PluginDepsDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.plugindeps"
    override val displayName: String = "Plugin Deps"
    override val version: String = manifestVersion()
    override val description: String =
        "Visual dependency graph of every loaded BOSS plugin - shows required/optional edges, " +
            "detects cycles, surfaces missing dependencies, and answers " +
            "\"what would break if I uninstall X\"."
    override val author: String = "Choksi"
    override val url: String = "https://github.com/choksi2212/boss-plugin-plugin-deps"

    private var mcpProvider: PluginDepsMcpToolProvider? = null

    override fun register(context: PluginContext) {
        // The sidebar panel for human use. The probe reads the loader delegate
        // lazily on the first refresh, so an older host that does not expose
        // it does not throw at register time.
        context.panelRegistry.registerPanel(PluginDepsInfo) { ctx, panelInfo ->
            PluginDepsComponent(ctx, panelInfo, context)
        }

        // The MCP tool surface for in-terminal agents. Registered with the
        // same probe context the panel uses; the provider re-reads on every
        // tool call so the snapshot is fresh.
        val provider = PluginDepsMcpToolProvider(providerId = pluginId, context = context)
        mcpProvider = provider
        context.registerMcpToolProvider(provider)
    }

    override fun dispose() {
        mcpProvider = null
    }

    /**
     * The version from this plugin's own manifest.
     *
     * The plugin ships `/META-INF/boss-plugin/plugin.json` at the same
     * resource path every BOSS plugin uses; reading the first hit could
     * pick up someone else's manifest if the host ever loads plugins
     * through a parent-first classloader. Only the entry that names this
     * plugin id is accepted.
     */
    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}
