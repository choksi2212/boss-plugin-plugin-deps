# Plugin Deps

A BOSS plugin that visualizes the dependency graph of every loaded plugin in
the host. Today the only way to see what a plugin depends on is to open its
`plugin.json` in a text editor - the host loads the plugins but does not
surface the dependency edges as a navigable picture.

This plugin draws that picture. It lists every loaded plugin as a node, draws
edges for required and optional dependencies, flags cycles, surfaces missing
dependencies, and answers "what would break if I uninstalled X?".

## What it shows

The sidebar panel renders three sections:

- **Cycles** (red) - a chain of required dependencies that loops back on
  itself. Two plugins declaring a required dependency on each other is the
  simplest case; longer chains show up the same way.
- **Missing Dependencies** (orange) - one or more plugins declare a
  required dependency on a `pluginId` that no loaded plugin claims.
- **Loaded Plugins** - one row per loaded plugin with:
  - Display name and id
  - Type, version, system flag, enabled state
  - A status dot (green / orange / gray) summarizing the plugin's
    outgoing edges
  - Required-dep count, optional-dep count, blocking-dependents count
  - Expanded view: full dependency list with per-edge status
  - Expanded view: "what would break if I uninstalled this plugin?" - a
    transitive list of plugins that depend on it (directly or indirectly)

## MCP tools

Four tools contributed to the `boss` MCP server:

| Tool | Description |
| --- | --- |
| `plugin_deps_graph` | Full graph as one JSON document: nodes, edges, cycles, missing |
| `plugin_deps_blocking_for` | Transitive blocking list for one plugin id |
| `plugin_deps_cycles` | Just the cycles, as arrays of plugin ids |
| `plugin_deps_missing` | Every declared-but-unloaded required dep |

The MCP tools re-probe on every call, so the result matches the loader
state at the moment the agent called.

## Install

1. Download `boss-plugin-plugin-deps-0.1.0.jar` from the latest release.
2. Open BOSS.
3. Open the Toolbox (`Settings > Plugins`).
4. Install from JAR.

Or copy the jar into `~/.boss/plugins/` and restart BOSS.

### Building from source

```bash
./gradlew buildPluginJar -x test
```

The plugin jar is produced at `build/libs/boss-plugin-plugin-deps-0.1.0.jar`.
`./gradlew build` runs the same task plus unit tests and reports.

## Compatibility

- `boss-plugin-api` 1.0.93 or newer.
- BOSS 9.4.2 or newer.
- Tested on macOS, Windows, and Linux desktop builds.

## License

See the upstream BOSS license; this plugin is released under the same terms.
