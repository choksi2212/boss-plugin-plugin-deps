package ai.rever.boss.plugin.dynamic.plugindeps

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginDependency
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One snapshot of the host's plugin loader, packaged for the graph builder.
 *
 * Two fields and a status: the views (what is loaded, what each one declared
 * it needs) and a flag if we had to fall back to a degraded view because
 * one of those is unavailable. The panel reads all three off the same
 * object so it can both render and explain partial information.
 */
data class PluginSnapshot(
    val views: List<LoaderPluginView>,
    val dependencies: Map<String, List<PluginDependency>>,
    val isDegraded: Boolean,
    val degradedReason: String?,
)

/**
 * Reads the host's plugin loader and assembles a [PluginSnapshot] for the
 * graph builder.
 *
 * Two sources of truth have to agree for a non-degraded snapshot:
 *  1. `PluginLoaderDelegate.getLoadedPlugins()` - the set of pluginIds
 *     currently in memory, plus their enabled flag and basic metadata.
 *  2. The manifest of each loaded plugin - the declared dependency list,
 *     read out of the JAR's `META-INF/boss-plugin/plugin.json`.
 *
 * The API exposes (1) directly via the loader delegate. (2) is not on the
 * API surface today, so the probe falls back to a "discovered" view: every
 * loaded plugin id is a node, with an empty dependency list. The panel and
 * the MCP tools both still work - they just see a graph with no edges - and
 * the banner explains why.
 *
 * If the loader delegate itself is null (older host without
 * `getPluginAPI(PluginLoaderDelegate::class.java)`), the snapshot is empty
 * and the panel shows "host does not expose the loader API yet".
 */
class PluginProbe(private val context: PluginContext) {

    /** Pulls the loader delegate out of the plugin context, with a null check. */
    private fun loaderDelegate(): PluginLoaderDelegate? =
        runCatching { context.getPluginAPI(PluginLoaderDelegate::class.java) }.getOrNull()

    /**
     * Read the loader, attempt to read each loaded manifest, return a snapshot.
     *
     * Runs on [Dispatchers.IO] because reading a JAR's `plugin.json` is a
     * blocking filesystem hop and the caller is on a Compose / Decompose
     * coroutine that should never block the UI.
     */
    suspend fun snapshot(): PluginSnapshot = withContext(Dispatchers.IO) {
        val loader = loaderDelegate()
        if (loader == null) {
            return@withContext PluginSnapshot(
                views = emptyList(),
                dependencies = emptyMap(),
                isDegraded = true,
                degradedReason = "Plugin loader delegate is unavailable on this host build.",
            )
        }
        val loaded = runCatching { loader.getLoadedPlugins() }.getOrElse { error ->
            return@withContext PluginSnapshot(
                views = emptyList(),
                dependencies = emptyMap(),
                isDegraded = true,
                degradedReason = "Failed to enumerate loaded plugins: ${error.message ?: error::class.simpleName}",
            )
        }
        if (loaded.isEmpty()) {
            return@withContext PluginSnapshot(
                views = emptyList(),
                dependencies = emptyMap(),
                isDegraded = true,
                degradedReason = "Plugin loader returned an empty list.",
            )
        }

        val views = loaded.map { it.toLoaderView() }
        val dependencies = readDeclaredDependencies(loaded)
        val degradedReason = if (dependencies.isEmpty() && loaded.size > 1) {
            "Dependency edges could not be read from any loaded manifest. The graph shows nodes only."
        } else {
            null
        }

        PluginSnapshot(
            views = views,
            dependencies = dependencies,
            isDegraded = degradedReason != null,
            degradedReason = degradedReason,
        )
    }

    /**
     * Read the manifest out of each loaded plugin's JAR.
     *
     * The API does not expose manifest content today, so this opens each
     * JAR's `META-INF/boss-plugin/plugin.json` from its own classloader.
     * `LoadedPluginInfo.jarPath` is the absolute path; we open it as a
     * jar: URL and read the entry. Any single failure is dropped from the
     * map (the plugin still shows up as a node, just with no edges) rather
     * than failing the whole snapshot.
     */
    private fun readDeclaredDependencies(loaded: List<LoadedPluginInfo>): Map<String, List<PluginDependency>> {
        val out = HashMap<String, List<PluginDependency>>()
        for (info in loaded) {
            val jarPath = info.jarPath
            if (jarPath.isBlank()) continue
            val manifest = readManifestFromJar(jarPath) ?: continue
            // Guard against the wrong manifest being read for a given pluginId:
            // the host could in theory load several JARs that share a classloader
            // and hand us back someone else's plugin.json. The id has to match.
            if (manifest.pluginId != info.pluginId) continue
            out[info.pluginId] = manifest.dependencies
        }
        return out
    }

    /**
     * Open a JAR, find `META-INF/boss-plugin/plugin.json`, parse it as a
     * [PluginManifest] and return it.
     *
     * Returns null on any I/O or parse error. Reading one bad JAR must
     * never take the whole snapshot down - the panel still has all the
     * other nodes.
     */
    private fun readManifestFromJar(jarPath: String): PluginManifest? = runCatching {
        val jarUrl = java.net.URL("jar:file:$jarPath!/META-INF/boss-plugin/plugin.json")
        jarUrl.openStream().use { stream ->
            val text = stream.readBytes().toString(Charsets.UTF_8)
            PluginManifestJson.decodeFromString(PluginManifest.serializer(), text)
        }
    }.getOrNull()
}

/** Project-local JSON for the manifest reader; ignores unknown keys so a host-added
 *  field does not break the probe. */
private val PluginManifestJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Project an API [LoadedPluginInfo] down to the slim [LoaderPluginView] the
 * graph builder consumes.
 *
 * The builder only cares about identity and a handful of fields; carrying
 * the whole `LoadedPluginInfo` through would couple the graph to the API
 * shape and force every test to fabricate the full data class.
 */
private fun LoadedPluginInfo.toLoaderView(): LoaderPluginView = LoaderPluginView(
    pluginId = pluginId,
    displayName = displayName,
    version = version,
    type = type,
    isSystemPlugin = isSystemPlugin,
    enabled = isEnabled,
    healthy = healthy,
    jarPath = jarPath,
)
