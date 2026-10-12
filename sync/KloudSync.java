import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;

// Prism pre-launch command. Keeps the instance on the pack's Minecraft and
// Fabric version, then hands over to packwiz-installer for the mods.
public class KloudSync {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        Path inst = Path.of(System.getenv("INST_DIR"));
        Path mc = Path.of(System.getenv("INST_MC_DIR"));

        String pack;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new Exception("HTTP " + res.statusCode());
            pack = res.body();
        } catch (Exception e) {
            // offline or github down - start with what is installed
            System.out.println("kloudcraft: could not check for updates, starting as is: " + e);
            return;
        }

        String minecraft = version(pack, "minecraft");
        String fabric = version(pack, "fabric");
        String wanted = minecraft + " " + fabric;
        Path marker = inst.resolve(".packversion");
        String have = Files.exists(marker) ? Files.readString(marker).trim() : "";

        if (!wanted.equals(have)) {
            // Prism reads the components before this runs, so the change only counts on the next launch
            Files.writeString(inst.resolve("mmc-pack.json"), """
                    {
                        "components": [
                            {"uid": "net.minecraft", "version": "%s", "important": true},
                            {"uid": "net.fabricmc.fabric-loader", "version": "%s"}
                        ],
                        "formatVersion": 1
                    }
                    """.formatted(minecraft, fabric));
            Files.writeString(marker, wanted + "\n");
            String msg = "kloudcraft was updated to Minecraft " + minecraft + ".\nPress Launch again to start it.";
            System.out.println(msg);
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, msg, "kloudcraft", JOptionPane.INFORMATION_MESSAGE);
            }
            System.exit(1);
        }

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-jar", "packwiz-installer-bootstrap.jar", "-s", "client", url)
                .directory(mc.toFile())
                .inheritIO()
                .start();
        System.exit(p.waitFor());
    }

    static String version(String pack, String key) {
        Matcher m = Pattern.compile("^" + key + " = \"(.+)\"$", Pattern.MULTILINE).matcher(pack);
        if (!m.find()) throw new IllegalStateException("no " + key + " version in pack.toml");
        return m.group(1);
    }
}
