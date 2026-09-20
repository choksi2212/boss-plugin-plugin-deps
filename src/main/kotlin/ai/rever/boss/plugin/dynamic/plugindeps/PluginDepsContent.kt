package ai.rever.boss.plugin.dynamic.plugindeps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Color tokens for the dependency status indicators.
 *
 * Three states with three deliberately distinct colors - green/orange/gray
 * mirror the three states a BOSS user already sees in dependency prompts,
 * so the panel reads as the same vocabulary with the same meaning.
 */
private object DepColors {
    val Resolved = Color(0xFF4CAF50)     // green
    val Missing = Color(0xFFFF9800)      // orange
    val Disabled = Color(0xFF9E9E9E)     // gray
    val CycleRed = Color(0xFFEF5350)     // red for cycle rows
    val Surface = Color(0xFF2A2A2A)
    val OnSurfaceMuted = Color(0xFFB0B0B0)
}

/**
 * Top-level composable for the panel.
 *
 * Just sets the surface background and dispatches to the inner panel
 * content. Kept tiny so the actual panel can be recomposed in isolation
 * during development.
 */
@Composable
fun PluginDepsContent(viewModel: PluginDepsViewModel) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background,
    ) {
        PluginDepsPanel(viewModel)
    }
}

@Composable
private fun PluginDepsPanel(viewModel: PluginDepsViewModel) {
    val graph by viewModel.graph.collectAsState()
    val selectedNodeId by viewModel.selectedNodeId.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        PluginDepsToolbar(
            pluginCount = graph.pluginCount,
            cycleCount = graph.cycles.size,
            missingCount = graph.missingDependencies.size,
            isDegraded = graph.isDegraded,
            onRefresh = { viewModel.refresh() },
        )

        if (graph.isDegraded && !graph.degradedReason.isNullOrBlank()) {
            DegradedBanner(reason = graph.degradedReason!!)
        }

        AnimatedVisibility(
            visible = statusMessage != null,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
        ) {
            StatusToast(
                message = statusMessage ?: return@AnimatedVisibility,
                onDismiss = { viewModel.clearStatus() },
            )
        }

        Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))

        if (graph.nodes.isEmpty()) {
            EmptyState(degraded = graph.isDegraded)
        } else {
            PluginDepsList(
                graph = graph,
                selectedNodeId = selectedNodeId,
                onSelect = { id -> viewModel.selectNode(if (selectedNodeId == id) null else id) },
            )
        }
    }
}

@Composable
private fun PluginDepsToolbar(
    pluginCount: Int,
    cycleCount: Int,
    missingCount: Int,
    isDegraded: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isDegraded) "Plugin Deps (degraded)" else "Plugin Deps",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$pluginCount plugins",
            fontSize = 11.sp,
            color = DepColors.OnSurfaceMuted,
        )
        if (cycleCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$cycleCount cycles",
                fontSize = 11.sp,
                color = DepColors.CycleRed,
                fontWeight = FontWeight.Medium,
            )
        }
        if (missingCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$missingCount missing",
                fontSize = 11.sp,
                color = DepColors.Missing,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun DegradedBanner(reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DepColors.Missing.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = DepColors.Missing,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = reason,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusToast(message: String, onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(message) {
        delay(3000)
        onDismiss()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DepColors.Surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun EmptyState(degraded: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (degraded) "No plugin data available" else "No plugins loaded",
                fontSize = 13.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun PluginDepsList(
    graph: DependencyGraph,
    selectedNodeId: String?,
    onSelect: (String?) -> Unit,
) {
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Cycles first - they are the loudest finding and the user should
            // see them before scrolling into the per-node list.
            if (graph.cycles.isNotEmpty()) {
                item(key = "cycles-header") {
                    SectionHeader(title = "Cycles", count = graph.cycles.size, color = DepColors.CycleRed)
                }
                items(graph.cycles, key = { "cycle-" + it.nodes.joinToString(",") }) { cycle ->
                    CycleRow(cycle = cycle, graph = graph)
                }
            }

            // Missing-dep findings, grouped under one header.
            if (graph.missingDependencies.isNotEmpty()) {
                item(key = "missing-header") {
                    SectionHeader(
                        title = "Missing Dependencies",
                        count = graph.missingDependencies.size,
                        color = DepColors.Missing,
                    )
                }
                items(graph.missingDependencies, key = { "missing-" + it.declarerId + "|" + it.missingId }) { m ->
                    MissingRow(missing = m, graph = graph)
                }
            }

            // Per-node list - the body of the panel.
            item(key = "plugins-header") {
                SectionHeader(
                    title = "Loaded Plugins",
                    count = graph.sortedNodes.size,
                    color = MaterialTheme.colors.primary,
                )
            }
            items(graph.sortedNodes, key = { it.pluginId }) { node ->
                PluginNodeRow(
                    node = node,
                    expanded = selectedNodeId == node.pluginId,
                    blockingDependents = if (selectedNodeId == node.pluginId) {
                        graph.blockingDependentsOf(node.pluginId) ?: emptyList()
                    } else emptyList(),
                    onClick = { onSelect(node.pluginId) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "($count)",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun CycleRow(cycle: CycleReport, graph: DependencyGraph) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = cycle.nodes.joinToString(" -> ") { id ->
                graph.nodes[id]?.displayName ?: id
            } + " -> (back to start)",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = DepColors.CycleRed,
        )
    }
}

@Composable
private fun MissingRow(missing: MissingDependency, graph: DependencyGraph) {
    val declarer = graph.nodes[missing.declarerId]?.displayName ?: missing.declarerId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "$declarer requires ${missing.missingId}",
            fontSize = 12.sp,
            color = DepColors.Missing,
        )
        Text(
            text = "Plugin id \"${missing.missingId}\" is declared but not loaded.",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun PluginNodeRow(
    node: PluginNode,
    expanded: Boolean,
    blockingDependents: List<String>,
    onClick: () -> Unit,
) {
    val hasIssues = node.dependencies.any { it.status != EdgeStatus.RESOLVED }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (expanded) MaterialTheme.colors.surface.copy(alpha = 0.5f) else MaterialTheme.colors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status dot: green if everything resolves, orange if any missing,
            // gray if any disabled. A single dot, not one per dependency - the
            // per-edge list lives in the expanded view.
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            hasIssues && node.dependencies.any { it.status == EdgeStatus.MISSING } -> DepColors.Missing
                            hasIssues -> DepColors.Disabled
                            else -> DepColors.Resolved
                        },
                    ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = node.pluginId + " v" + node.version,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (node.requiredCount > 0) {
                    Text(
                        text = "${node.requiredCount} req",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    )
                }
                if (node.optionalCount > 0) {
                    Text(
                        text = "${node.optionalCount} opt",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    )
                }
                if (node.blockingDependentsCount > 0) {
                    Text(
                        text = "${node.blockingDependentsCount} blocking",
                        fontSize = 10.sp,
                        color = DepColors.Missing,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(18.dp)
                    .padding(start = 4.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = if (expanded) 0.9f else 0.4f),
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            ExpandedNodeDetail(node = node, blockingDependents = blockingDependents)
        }
    }
}

@Composable
private fun ExpandedNodeDetail(node: PluginNode, blockingDependents: List<String>) {
    Column(modifier = Modifier.padding(start = 18.dp)) {
        // Metadata line: type, system flag, enabled state.
        Text(
            text = "Type: " + node.type +
                if (node.isSystemPlugin) " (system)" else "" +
                if (!node.enabled) " (disabled)" else "",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        )
        Text(
            text = "Jar: " + (node.jarPath.ifBlank { "(unknown)" }),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (node.dependencies.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Dependencies",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
            )
            for (dep in node.dependencies) {
                DependencyEdgeRow(edge = dep)
            }
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "No declared dependencies",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        }

        // "What would break" drill-down.
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "What would break if this plugin were uninstalled",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.8f),
        )
        if (blockingDependents.isEmpty()) {
            Text(
                text = "Nothing - no other loaded plugin has a required edge to this one.",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        } else {
            for (dependentId in blockingDependents) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(DepColors.Missing),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = dependentId,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DependencyEdgeRow(edge: DependencyEdge) {
    val color = when (edge.status) {
        EdgeStatus.RESOLVED -> DepColors.Resolved
        EdgeStatus.MISSING -> DepColors.Missing
        EdgeStatus.DISABLED -> DepColors.Disabled
    }
    val label = when (edge.status) {
        EdgeStatus.RESOLVED -> "OK"
        EdgeStatus.MISSING -> "MISSING"
        EdgeStatus.DISABLED -> "DISABLED"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = edge.targetPluginId + if (edge.optional) " (optional)" else "",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}
