import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;

// Checks every depends and breaks of every mod in the pack, nested jars included,
// against what the pack ships, the way Fabric Loader does at launch.
// Usage: java -cp fabric-loader.jar check/PackCheck.java <pack dir> fabric-loader.jar
public class PackCheck {
    record Mod(String id, String version, String env, List<String> provides,
               Map<String, List<String>> depends, Map<String, List<String>> breaks,
               List<byte[]> nested, String from) {}

    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] args) throws Exception {
        Path pack = Path.of(args[0]);
        String toml = Files.readString(pack.resolve("pack.toml"));
        String mc = field(toml, "minecraft");
        String fabric = field(toml, "fabric");

        // the loader is a mod too and brings its own mixinextras
        List<Mod> mods = new ArrayList<>();
        load(Files.readAllBytes(Path.of(args[1])), "fabric loader", mods);
        try (var files = Files.list(pack.resolve("mods"))) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".pw.toml")) {
                    String meta = Files.readString(f);
                    if ("server".equals(field(meta, "side"))) continue;
                    load(download(field(meta, "url")), name, mods);
                } else if (name.endsWith(".jar")) {
                    load(Files.readAllBytes(f), name, mods);
                }
            }
        }

        // what the client has, fabric skips mods marked server only and
        // loads the newest copy when several jars carry the same mod
        Map<String, String> present = new HashMap<>();
        present.put("minecraft", mc);
        present.put("java", String.valueOf(Runtime.version().feature()));
        List<Mod> loaded = mods.stream().filter(m -> !"server".equals(m.env())).toList();
        for (Mod m : loaded) {
            newest(present, m.id(), m.version());
            for (String p : m.provides()) newest(present, p, m.version());
        }

        List<String> problems = new ArrayList<>();
        for (Mod m : loaded) {
            m.depends().forEach((dep, preds) -> {
                String have = present.get(dep);
                if (have == null) {
                    problems.add(m.from() + ": " + m.id() + " needs " + dep + " " + preds + ", not in the pack");
                } else if (!matches(have, preds)) {
                    problems.add(m.from() + ": " + m.id() + " needs " + dep + " " + preds + ", pack has " + have);
                }
            });
            m.breaks().forEach((dep, preds) -> {
                String have = present.get(dep);
                if (have != null && matches(have, preds)) {
                    problems.add(m.from() + ": " + m.id() + " breaks with " + dep + " " + preds + ", pack has " + have);
                }
            });
        }

        System.out.println("checked " + loaded.size() + " mods on minecraft " + mc + ", fabric " + fabric);
        if (!problems.isEmpty()) {
            problems.stream().distinct().forEach(p -> System.out.println("  " + p));
            System.exit(1);
        }
    }

    static void newest(Map<String, String> present, String id, String version) throws Exception {
        String have = present.get(id);
        if (have == null || Version.parse(version).compareTo(Version.parse(have)) > 0) present.put(id, version);
    }

    static String field(String toml, String key) {
        Matcher m = Pattern.compile("^" + key + " = \"(.*)\"$", Pattern.MULTILINE).matcher(toml);
        return m.find() ? m.group(1) : null;
    }

    static byte[] download(String url) throws IOException, InterruptedException {
        var res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new IOException(url + " answered " + res.statusCode());
        return res.body();
    }

    // a jar and everything it carries in META-INF/jars
    static void load(byte[] jar, String from, List<Mod> out) throws IOException {
        load(jar, from, null, out);
    }

    static void load(byte[] jar, String from, String parentEnv, List<Mod> out) throws IOException {
        Mod mod = read(jar, from, parentEnv);
        if (mod == null) return;
        out.add(mod);
        for (byte[] inner : mod.nested()) load(inner, from, mod.env(), out);
    }

    static Mod read(byte[] jar, String from, String parentEnv) throws IOException {
        byte[] json = null;
        Map<String, byte[]> entries = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("fabric.mod.json")) json = zip.readAllBytes();
                else if (e.getName().startsWith("META-INF/jars/") && e.getName().endsWith(".jar")) entries.put(e.getName(), zip.readAllBytes());
            }
        }
        if (json == null) return null;

        String id = null, version = null, env = parentEnv;
        List<String> provides = new ArrayList<>();
        Map<String, List<String>> depends = new HashMap<>(), breaks = new HashMap<>();
        List<byte[]> nested = new ArrayList<>();
        try (var r = new JsonReader(new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "id" -> id = r.nextString();
                    case "version" -> version = r.nextString();
                    case "environment" -> env = r.nextString();
                    case "provides" -> provides.addAll(strings(r));
                    case "depends" -> depends = constraints(r);
                    case "breaks" -> breaks = constraints(r);
                    case "jars" -> {
                        r.beginArray();
                        while (r.hasNext()) {
                            r.beginObject();
                            while (r.hasNext()) {
                                if (r.nextName().equals("file")) {
                                    byte[] inner = entries.get(r.nextString());
                                    if (inner != null) nested.add(inner);
                                } else r.skipValue();
                            }
                            r.endObject();
                        }
                        r.endArray();
                    }
                    default -> r.skipValue();
                }
            }
        }
        return new Mod(id, version, env, provides, depends, breaks, nested, from);
    }

    static Map<String, List<String>> constraints(JsonReader r) throws IOException {
        Map<String, List<String>> out = new HashMap<>();
        r.beginObject();
        while (r.hasNext()) out.put(r.nextName(), strings(r));
        r.endObject();
        return out;
    }

    static List<String> strings(JsonReader r) throws IOException {
        List<String> out = new ArrayList<>();
        if (r.peek() == JsonToken.BEGIN_ARRAY) {
            r.beginArray();
            while (r.hasNext()) out.add(r.nextString());
            r.endArray();
        } else out.add(r.nextString());
        return out;
    }

    // a list means any of them, the same as fabric
    static boolean matches(String have, List<String> preds) {
        try {
            Version v = Version.parse(have);
            for (String p : preds) if (VersionPredicate.parse(p).test(v)) return true;
            return false;
        } catch (Exception e) {
            throw new RuntimeException("can't compare " + have + " with " + preds, e);
        }
    }
}
