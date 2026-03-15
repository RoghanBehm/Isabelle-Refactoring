#!/bin/bash
set -e

rm -rf classes jarroot Hello.jar
mkdir -p classes jarroot

isabelle scalac -d classes \
  src/isabelle/jedit/dev_rename.scala \
  src/isabelle/jedit/Rename_Plugin.scala

cp -r classes/* jarroot/
cp actions.xml Rename.props plugin.props jarroot/

jar cf Hello.jar -C jarroot .
cp Hello.jar ~/.isabelle/Isabelle2025-2/jedit/jars/
