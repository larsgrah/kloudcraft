#!/bin/sh
# Builds dist/kloudcraft.zip, the Prism instance friends import once.
set -eu
cd "$(dirname "$0")"

mc=$(sed -n 's/^minecraft = "\(.*\)"$/\1/p' pack.toml)
fabric=$(sed -n 's/^fabric = "\(.*\)"$/\1/p' pack.toml)

rm -rf dist
mkdir -p dist/build dist/kloudcraft/minecraft

javac --release 17 -d dist/build sync/KloudSync.java
jar --create --file dist/kloudcraft/kloudsync.jar --main-class KloudSync -C dist/build .

curl -sfL -o dist/kloudcraft/minecraft/packwiz-installer-bootstrap.jar \
  https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar

cp instance/instance.cfg dist/kloudcraft/
echo "$mc $fabric" > dist/kloudcraft/.packversion
cat > dist/kloudcraft/mmc-pack.json <<JSON
{
    "components": [
        {"uid": "net.minecraft", "version": "$mc", "important": true},
        {"uid": "net.fabricmc.fabric-loader", "version": "$fabric"}
    ],
    "formatVersion": 1
}
JSON

(cd dist/kloudcraft && zip -qr ../kloudcraft.zip .)
echo "built dist/kloudcraft.zip for minecraft $mc"
