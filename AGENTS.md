# AGENTS.md

Notes for coding agents working on this plugin.

## Project Overview

`boss-plugin-plugin-deps` is a BOSS plugin that visualizes the dependency
graph of every loaded plugin. It exposes a sidebar panel for humans and a
`plugin_deps_*` MCP tool set for in-terminal agents.

The plugin is **type `mixed`** - panel + MCP tools.

## Build

```bash
./gradlew buildPluginJar -x test --no-daemon
```

The plugin JAR is built by the `buildPluginJar` task and lands at
`build/libs/boss-plugin-plugin-deps-${version}.jar`. It is also produced
by the standard `./gradlew build` task.

CI is set up in `.github/workflows/`:

- `build.yml` - the release workflow (push to `main`). Delegates to the
  shared plugin-release workflow in `risa-labs-inc/BossConsole-Releases`.
- `test.yml` - the test workflow (pull requests). Downloads the latest
  `boss-plugin-api` jar into `build/downloaded-deps/` and runs
  `./gradlew build`.

## Layout

```
src/main/kotlin/ai/rever/boss/plugin/dynamic/plugindeps/
  PluginDepsDynamicPlugin.kt   - entry point; registers panel + MCP tools
  PluginDepsInfo.kt            - PanelInfo (id, displayName, icon, slot)
  PluginDepsComponent.kt       - PanelComponentWithUI host
  PluginDepsViewModel.kt       - StateFlow of DependencyGraph + selection
  PluginDepsContent.kt         - Compose UI for the panel
  DependencyGraph.kt           - data model + cycle / closure algorithms
  PluginProbe.kt               - reads PluginLoaderDelegate + per-JAR manifests
  PluginDepsMcpTools.kt        - plugin_deps_* MCP tool provider
```

## Architecture

### `DependencyGraph` is the source of truth

The graph is built once per probe call. Both the panel and the MCP tools
hold immutable snapshots, so neither can see the other's mutations and
neither has to worry about partial state mid-update.

### `PluginProbe` is the only thing that talks to the host

Two reads:

1. `context.getPluginAPI(PluginLoaderDelegate::class.java).getLoadedPlugins()` -
   the loader's view of what is currently in memory.
2. Each loaded JAR's `META-INF/boss-plugin/plugin.json` - the dependency
   declarations that live in the manifest.

The probe fails closed on either read: if the loader delegate is null, or
the loader call throws, or every manifest is unreadable, the panel renders
the degraded banner instead of crashing.

### `PluginDepsViewModel` is the panel's only mutable state

Two `StateFlow`s: the current `DependencyGraph` and the selected node id.
The MCP tools bypass the viewmodel and re-probe on every call so they always
see fresh state.

## Constraints

- NO em-dashes (U+2014). Spaced hyphens (` - `) only in any prose this
  plugin ships: README, AGENTS, commit messages, PR descriptions.
- Every Kotlin file ends with a newline.
- Use Compose Multiplatform Resource API (not Android resources).
- Use `BossLogger` for logging (not `println()`).
