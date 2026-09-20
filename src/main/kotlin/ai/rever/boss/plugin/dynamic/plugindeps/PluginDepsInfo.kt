package ai.rever.boss.plugin.dynamic.plugindeps

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.GitMerge

/**
 * Sidebar entry for the Plugin Deps panel.
 *
 * Priority 72 in the left bottom slot puts it after git-status (14) and
 * bookmarks (a similar-range number), so it lands at the bottom of the
 * left bar without crowding the more-used panels at the top.
 */
object PluginDepsInfo : PanelInfo {
    override val id = PanelId("plugin-deps", 72)
    override val displayName = "Plugin Deps"
    override val icon = FeatherIcons.GitMerge
    override val defaultSlotPosition: Panel = left.bottom
}
