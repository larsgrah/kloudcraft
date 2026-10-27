package kloudcraft;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

// Puts the kloudsync.jar bundled in this mod into the Prism instance. The sync can't
// replace itself, Windows locks a running jar, but nothing holds it while the game runs.
public class KloudUpdater implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;
        try {
            Path inst = FabricLoader.getInstance().getGameDir().toAbsolutePath().getParent();
            Path target = inst.resolve("kloudsync.jar");
            // only touch instances that already run the sync
            if (!Files.exists(target)) return;
            byte[] bundled;
            try (InputStream in = KloudUpdater.class.getResourceAsStream("/kloudsync.jar")) {
                bundled = in.readAllBytes();
            }
            if (Arrays.equals(Files.readAllBytes(target), bundled)) return;
            Path tmp = inst.resolve("kloudsync.jar.new");
            Files.write(tmp, bundled);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("[kloudcraft] updated " + target);
        } catch (Exception e) {
            // never stop the game over this, next start tries again
            System.out.println("[kloudcraft] could not update kloudsync.jar: " + e);
        }
    }
}
