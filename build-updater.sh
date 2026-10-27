#!/bin/sh
# Builds mods/kloudcraft-updater.jar, the client mod that carries the current
# kloudsync.jar into every instance. Run it after changing sync/ or updater/.
set -eu
cd "$(dirname "$0")"

loader=0.20.0
build=dist/updater
rm -rf "$build"
mkdir -p "$build/sync" "$build/mod"

javac --release 17 -d "$build/sync" sync/KloudSync.java
jar --create --file "$build/mod/kloudsync.jar" --main-class KloudSync -C "$build/sync" .

curl -sfL -o "$build/fabric-loader.jar" \
  "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$loader/fabric-loader-$loader.jar"
javac --release 17 -cp "$build/fabric-loader.jar" -d "$build/mod" updater/KloudUpdater.java
cp updater/fabric.mod.json "$build/mod/"

jar --create --file mods/kloudcraft-updater.jar -C "$build/mod" .
packwiz refresh
echo "built mods/kloudcraft-updater.jar"
