# kloudcraft

Client mods for the kloud Minecraft server. The pack follows the server's
Minecraft version on its own and your launcher pulls the changes on every start.

## Install

You need [Prism Launcher](https://prismlauncher.org) with your Minecraft account added.

1. Add Instance -> Import
2. Paste `https://github.com/larsgrah/kloudcraft/releases/latest/download/kloudcraft.zip`
3. Launch

That is it. Mods are downloaded and updated each time you press Launch.

When the server moved to a new Minecraft version you get a small
"kloudcraft was updated" message. Close Prism completely, open it again and press Launch.
Prism only picks up a new Minecraft or Fabric version after a restart, so until then the message comes back
instead of a broken start.

Coming from an older kloudcraft instance? Import this one as a new instance and copy over
what you want to keep from the old instance folder: `options.txt`, `servers.dat`, `config`,
`schematics`, `saves`, `resourcepacks`, `shaderpacks`. Do not copy `mods`.

Your own extra mods in the `mods` folder are left alone.

## Maintaining

- `mods.txt` is the mod list, one Modrinth slug per line. Push a change and the pack is rebuilt.
- The `update` workflow runs hourly. It asks the server which Minecraft version it runs and
  moves the pack there, with the latest build of every mod.
- Mods without a build for that version are left out and listed in `missing.txt`.
  They are retried every hour and come back on their own.
- Run the workflow by hand to pull the latest build of every mod without a Minecraft change.
- `./build-instance.sh` builds the instance zip for the release. It only needs a rebuild
  when `sync/` or `instance/` change.
