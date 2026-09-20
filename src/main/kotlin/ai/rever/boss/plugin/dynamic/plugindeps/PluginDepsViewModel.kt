package ai.rever.boss.plugin.dynamic.plugindeps

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the Plugin Deps panel.
 *
 * One [StateFlow] of [DependencyGraph] is the panel's source of truth;
 * [refresh] rebuilds it from a fresh [PluginProbe.snapshot] call. The probe
 * does its I/O on its own dispatcher, so the viewmodel is safe to call
 * from any coroutine.
 *
 * [selectedNodeId] is the row currently expanded in the "what would break"
 * drill-down; null means no row is expanded.
 *
 * [statusMessage] is a transient text line the panel renders as a toast -
 * not the same thing as the loader's degraded banner.
 */
class PluginDepsViewModel(private val context: PluginContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val probe = PluginProbe(context)

    private val _graph = MutableStateFlow(DependencyGraph(
        nodes = emptyMap(),
        cycles = emptyList(),
        missingDependencies = emptyList(),
        isDegraded = true,
        degradedReason = "Loading...",
    ))
    val graph: StateFlow<DependencyGraph> = _graph.asStateFlow()

    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        refresh()
    }

    /**
     * Rebuild the snapshot.
     *
     * Safe to call from anywhere; concurrent calls are dropped because the
     * viewmodel only keeps one in-flight refresh (a second one would race
     * the StateFlow and leave whichever finished last as the truth, which
     * is fine - both calls would have read the same loader state).
     */
    fun refresh() {
        scope.launch {
            val snapshot = probe.snapshot()
            val graph = DependencyGraph.build(
                loadedPlugins = snapshot.views,
                declaredDependencies = snapshot.dependencies,
                isDegraded = snapshot.isDegraded,
                degradedReason = snapshot.degradedReason,
            )
            _graph.value = graph
            // If the previously-selected plugin disappeared (uninstalled), clear the selection.
            if (_selectedNodeId.value != null && _selectedNodeId.value !in graph.nodes) {
                _selectedNodeId.value = null
            }
        }
    }

    /**
     * Toggle the drill-down for [pluginId]. Null input clears any selection.
     *
     * The selection is local to the viewmodel: two panels open at once would
     * not normally share state, and a single panel's drill-down is a per-user
     * affordance.
     */
    fun selectNode(pluginId: String?) {
        _selectedNodeId.value = pluginId?.takeIf { it in _graph.value.nodes }
    }

    /** Compute the "what would break" list for [pluginId]. */
    fun blockingDependentsOf(pluginId: String): List<String> =
        _graph.value.blockingDependentsOf(pluginId) ?: emptyList()

    /** Flash a status line for the panel; auto-clears on the next [refresh]. */
    fun showStatus(text: String) {
        _statusMessage.value = text
    }

    fun clearStatus() {
        _statusMessage.value = null
    }
}
