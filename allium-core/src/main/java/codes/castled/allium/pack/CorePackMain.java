package codes.castled.allium.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Build and validation entry point used by the Gradle build and the live pack gate.
 *
 * <pre>
 * build    &lt;manifest.json&gt; &lt;pack root&gt; &lt;out dir&gt; [--texture-root &lt;dir&gt;] [--name &lt;zip name&gt;]
 * validate &lt;pack.zip&gt; &lt;manifest.json&gt; [--lane &lt;label&gt; &lt;format&gt; &lt;client.jar&gt;]... [--report &lt;file&gt;]
 * mcmeta   &lt;client format&gt; &lt;pack.mcmeta&gt;...   (how that client reads each file; one JSON line each)
 * </pre>
 *
 * {@code --texture-root} substitutes textures from another directory (local server-only exports);
 * those builds skip the provenance hash check and must never be committed.
 */
public final class CorePackMain {

    public static final String DEFAULT_NAME = "allium-core-pack.zip";

    private CorePackMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
        }
        int status = switch (args[0]) {
            case "build" -> build(args);
            case "validate" -> validate(args);
            case "mcmeta" -> mcmeta(args);
            default -> {
                usage();
                yield 2;
            }
        };
        System.exit(status);
    }

    private static int build(String[] args) throws IOException {
        if (args.length < 4) usage();
        Path manifestPath = Path.of(args[1]);
        Path root = Path.of(args[2]);
        Path out = Path.of(args[3]);
        Path textureRoot = null;
        String name = DEFAULT_NAME;
        for (int i = 4; i < args.length; i += 2) {
            if (i + 1 >= args.length) usage();
            switch (args[i]) {
                case "--texture-root" -> textureRoot = Path.of(args[i + 1]);
                case "--name" -> name = args[i + 1];
                default -> usage();
            }
        }
        AssetManifest manifest = AssetManifest.parse(Files.readString(manifestPath));
        Path textures = textureRoot == null ? root : textureRoot;
        SortedMap<String, byte[]> files = CorePackBuilder.files(manifest, t -> Files.readAllBytes(textures.resolve(t.file())), textureRoot == null);
        List<String> problems = PackValidator.structure(files, manifest);
        if (!problems.isEmpty()) {
            problems.forEach(p -> System.err.println("INVALID " + p));
            return 1;
        }
        byte[] zip = CorePackBuilder.zip(files);
        Files.createDirectories(out);
        Files.write(out.resolve(name), zip);
        String sha1 = CorePackBuilder.sha1(zip);
        Files.writeString(out.resolve(name + ".sha1"), sha1 + "\n");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("file", name);
        info.put("sha1", sha1);
        info.put("sha256", CorePackBuilder.sha256(zip));
        info.put("bytes", zip.length);
        info.put("entries", files.size());
        info.put("manifestSha256", CorePackBuilder.sha256(Files.readAllBytes(manifestPath)));
        info.put("provenanceVerified", textureRoot == null);
        Files.writeString(out.resolve(name + ".json"), Json.write(info) + "\n");
        System.out.println("PACK " + name + " sha1=" + sha1 + " bytes=" + zip.length + " entries=" + files.size());
        return 0;
    }

    private static int validate(String[] args) throws IOException {
        if (args.length < 3) usage();
        byte[] zip = Files.readAllBytes(Path.of(args[1]));
        AssetManifest manifest = AssetManifest.parse(Files.readString(Path.of(args[2])));
        SortedMap<String, byte[]> files = PackValidator.unzip(zip);
        List<String> structure = PackValidator.structure(files, manifest);
        List<Object> lanes = new ArrayList<>();
        boolean clean = structure.isEmpty();
        Path report = null;
        for (int i = 3; i < args.length; ) {
            if (args[i].equals("--lane") && i + 3 < args.length) {
                PackFormatRules.Format format = PackFormatRules.Format.parse(args[i + 2]);
                VanillaAssets vanilla = VanillaAssets.fromClientJar(Path.of(args[i + 3]));
                PackValidator.Report result = PackValidator.lane(args[i + 1], format, files, manifest, vanilla);
                clean &= result.clean();
                Map<String, Object> lane = new LinkedHashMap<>();
                lane.put("lane", result.lane());
                lane.put("clientFormat", format.toString());
                lane.put("vanilla", vanilla.label());
                lane.put("compatible", result.format().compatible());
                lane.put("declared", result.format().declared());
                lane.put("overlays", result.format().overlays());
                lane.put("errors", result.errors());
                lane.put("notes", result.notes());
                lane.put("renders", new java.util.TreeMap<>(result.renders()));
                lanes.add(lane);
                i += 4;
            } else if (args[i].equals("--report") && i + 1 < args.length) {
                report = Path.of(args[i + 1]);
                i += 2;
            } else {
                usage();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clean", clean);
        out.put("sha1", CorePackBuilder.sha1(zip));
        out.put("bytes", zip.length);
        out.put("entries", files.size());
        out.put("structure", structure);
        out.put("lanes", lanes);
        String text = Json.write(out);
        if (report != null) {
            Files.writeString(report, text + "\n", StandardCharsets.UTF_8);
        }
        System.out.println((clean ? "CLEAN " : "INVALID ") + text);
        return clean ? 0 : 1;
    }

    private static int mcmeta(String[] args) throws IOException {
        if (args.length < 3) usage();
        PackFormatRules.Format client = PackFormatRules.Format.parse(args[1]);
        for (int i = 2; i < args.length; i++) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", args[i]);
            out.put("client", client.toString());
            try {
                PackFormatRules.Result result = PackFormatRules.evaluate(Json.parseObject(Files.readString(Path.of(args[i]))), client);
                out.put("compatible", result.compatible());
                out.put("declared", result.declared());
                out.put("errors", result.errors());
            } catch (IllegalArgumentException e) {
                out.put("compatible", false);
                out.put("errors", List.of(e.getMessage()));
            }
            System.out.println(Json.write(out));
        }
        return 0;
    }

    private static void usage() {
        System.err.println("usage: build <manifest> <pack root> <out dir> [--texture-root <dir>] [--name <zip>]\n"
                + "       validate <zip> <manifest> [--lane <label> <format> <client.jar>]... [--report <file>]\n"
                + "       mcmeta <client format> <pack.mcmeta>...");
        System.exit(2);
    }
}
