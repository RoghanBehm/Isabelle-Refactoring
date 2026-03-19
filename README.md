# Isabelle/jEdit Refactoring Plugin

A plugin for Isabelle/jEdit that adds refactoring actions to the editor.

**Currently implemented:** rename symbol across theory files.

> Work in progress.

## Prerequisites

- Isabelle (tested on Isabelle2025-2)
- jEdit bundled with Isabelle

## Repository layout

### Source files
| File | Purpose |
|------|---------|
| `src/isabelle/jedit/dev_rename.scala` | Action entrypoints (e.g. `Refactor.rename(view)`) |
| `src/isabelle/jedit/Rename_Plugin.scala` | Core plugin class (`extends EditPlugin`): makes jEdit treat this as a proper plugin |
| `actions.xml` | Declares action IDs and the code that runs when they trigger |
| `Rename.props` | Human-readable action labels, shown in menus and the Shortcuts dialog |
| `plugin.props` | Plugin metadata and core class declaration (required to avoid a "MISSING PLUGIN CORE CLASS" error) |

### Build artifacts (generated)
| Path | Contents |
|------|---------|
| `classes/` | Compiled `.class` files |
| `jarroot/` | Staging directory for assembling the jar |
| `Hello.jar` | Final plugin jar |

## Building

Run the build script to compile and install the plugin:
```bash
./build.sh
```

The script compiles the plugin and copies the jar into your Isabelle plugins 
directory. **Before running it**, open `build.sh` and update the destination 
path in the final `cp` command to match your Isabelle installation:
```bash
# Example — change this to your actual Isabelle path:
cp Hello.jar ~/Isabelle2024/jedit/jars/
```

## Usage

1. Build and install the plugin (see above).
2. Open Isabelle/jEdit.
3. Go to **Utilities → Global Options → Shortcuts**.
4. Search for the rename action and bind it to a key.
5. Place your cursor on a symbol and trigger the shortcut to rename it.
