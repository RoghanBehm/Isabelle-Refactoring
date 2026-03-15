# Isabelle/jEdit Plugin Template

This repo serves as a template for building an Isabelle/jEdit "plugin" (jEdit action-set packaged as a `.jar`)
using Isabelle's Scala toolchain. Actions are registered via `actions.xml`, labelled via `Hello.props`, and
a core plugin class is declared via `Hello_Plugin.scala` + `plugin.props` to avoid the "MISSING PLUGIN CORE 
CLASS" label in the shortcuts UI.


## Directory layout

- `src/isabelle/jedit/sb_rename.scala`
  Scala code containing action entrypoints (e.g. `Refactor.rename(view)`).

- `src/isabelle/jedit/Hello_Plugin.scala`
  Optional core plugin class (`extends EditPlugin`). Makes jEdit treat this as a "proper" plugin.

- `actions.xml`
  Declares the action IDs and the code that runs when they trigger (calls into Scala methods).

- `Hello.props`
  Human-readable labels for actions (shown in menus / *Utilities -> Global Options -> Shortcuts*).

- `plugin.props`
  Declares the plugin core class and basic metadata. Required if you want to avoid
  “MISSING PLUGIN CORE CLASS”.


Build artifacts:
- `classes/`   compiled `.class` files
- `jarroot/`   staging directory used to assemble jar contents
- `Hello.jar`  final plugin jar


## Building the plugin

```bash
# Clean old artifacts
rm -rf classes jarroot Hello.jar
mkdir -p classes jarroot

# Compile Scala sources into ./classes
isabelle scalac -d classes \
  src/isabelle/jedit/sb_rename.scala \
  src/isabelle/jedit/Hello_Plugin.scala

# Stage jar contents
cp -r classes/* jarroot/
cp actions.xml Hello.props plugin.props jarroot/

# Create the plugin jar
jar cf Hello.jar -C jarroot .

# Copy jar to Isabelle/jEdit. On my setup, this looks like:
cp Hello.jar ~/.isabelle/Isabelle2025-2/jedit/jars/
```

## Using the plugin
After copying the jar as instructed above, open Isabelle and go to *Utilities ->
Global Options -> Shortcuts* to bind the action to a hotkey.
