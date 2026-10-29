#!/bin/sh
# Checks the pack's mods against each other like Fabric does at launch, needs a JDK.
set -eu
cd "$(dirname "$0")"

loader=$(sed -n 's/^fabric = "\(.*\)"$/\1/p' pack.toml)
jar=dist/fabric-loader-$loader.jar
mkdir -p dist
[ -s "$jar" ] || curl -sfL -o "$jar" \
  "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$loader/fabric-loader-$loader.jar"
exec java -cp "$jar" check/PackCheck.java . "$jar"
