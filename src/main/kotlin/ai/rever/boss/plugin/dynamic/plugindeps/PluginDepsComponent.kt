package ai.rever.boss.plugin.dynamic.plugindeps

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * Panel component for Plugin Deps.
 *
 * Hosts a single [PluginDepsViewModel] for its lifetime and delegates
 * [Content] to [PluginDepsContent]. The viewmodel is constructed once, so
 * the StateFlow inside it is preserved across recompositions.
 */
class PluginDepsComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    context: PluginContext,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = PluginDepsViewModel(context)

    @Composable
    override fun Content() {
        PluginDepsContent(viewModel = viewModel)
    }
}
