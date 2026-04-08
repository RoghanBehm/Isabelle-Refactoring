#!/bin/bash
set -e

rm -rf classes jarroot Isabelle_Refactor.jar
mkdir -p classes jarroot

isabelle scalac -d classes \
  src/isabelle/jedit/refactor.scala \
  src/isabelle/jedit/rename.scala \
  src/isabelle/jedit/Refactor_Plugin.scala \
  src/isabelle/jedit/utils.scala \
  src/isabelle/jedit/headless_handler.scala \
  src/isabelle/jedit/edits.scala

cp -r classes/* jarroot/
cp actions.xml Refactor.props plugin.props jarroot/

jar cf Isabelle_Refactor.jar -C jarroot .
cp Isabelle_Refactor.jar ~/.isabelle/Isabelle2025-2/jedit/jars/
