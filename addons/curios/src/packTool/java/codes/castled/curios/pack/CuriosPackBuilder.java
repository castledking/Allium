package codes.castled.curios.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yaml.snakeyaml.Yaml;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the Curios resource pack and resizable card-panel layout manifest. */
public final class CuriosPackBuilder {
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Map<String, String> ICONS = new LinkedHashMap<>();
    private static final List<String> TIERS = List.of("simple", "elite", "ultimate", "legendary", "fabled");
    private static final List<String> PARTS = List.of("header", "body", "bottom");
    private static final int[] FILLS = {1, 2, 4, 8, 16, 32, 64, 128};
    private static final String PANEL_FONT = "curios:card_panel";
    private static final int PANEL_BASE = 0xE200;
    private static final int NEGATIVE_BASE = 0xE301;
    private static final int PANEL_IN = 0xE310;

    static {
        ICONS.put("charm", "slot/charm");
        ICONS.put("head", "slot/head");
        ICONS.put("chest", "slot/chest");
        ICONS.put("belt", "slot/belt");
        ICONS.put("ring", "slot/ring");
        ICONS.put("card", "uis/inventory/card");
        ICONS.put("prev", "relique/item/prev");
        ICONS.put("next", "relique/item/next");
        ICONS.put("up", "relique/item/up");
        ICONS.put("down", "relique/item/down");
        ICONS.put("card_menu", "uis/inventory/card");
        ICONS.put("card_locked", "uis/inventory/locked");
        ICONS.put("card_prev", "uis/inventory/arrow_left_red");
        ICONS.put("card_next", "uis/inventory/arrow_right_green");
        ICONS.put("card_back", "uis/inventory/back_red");
        ICONS.put("blank", "uis/inventory/blank");
    }

    private CuriosPackBuilder() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: CuriosPackBuilder <packDir> <resourcesDir> <outputZip> [--definitions <yml>] [--panel-layout <json>]");
        }
        Path packDir = Path.of(args[0]);
        Path resourcesDir = Path.of(args[1]);
        Path output = Path.of(args[2]);
        Path definitions = resourcesDir.resolve("config.yml");
        Path panelLayout = null;
        for (int i = 3; i < args.length; i++) {
            if ("--definitions".equals(args[i]) && i + 1 < args.length) definitions = Path.of(args[++i]);
            else if ("--panel-layout".equals(args[i]) && i + 1 < args.length) panelLayout = Path.of(args[++i]);
        }
        build(packDir, resourcesDir, definitions, output, panelLayout);
    }

    private record SliceResult(int first, int last, int[] centre) {}
    private record PanelProvider(String name, BufferedImage image, int height, int ascent, String glyph) {}
    private record Route(int model, String name, String texture) {}

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                copy.setRGB(x, y, src.getRGB(x, y));
            }
        }
        return copy;
    }

    private static SliceResult slicePanel(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[][] cols = new int[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) cols[x][y] = image.getRGB(x, y);
        }
        int bestLen = 0, bestStart = 0, start = 0;
        for (int x = 1; x <= w; x++) {
            if (x == w || !Arrays.equals(cols[x], cols[start])) {
                int len = x - start;
                if (len > bestLen || (len == bestLen && start > bestStart)) {
                    bestLen = len;
                    bestStart = start;
                }
                start = x;
            }
        }
        int[] fill = cols[bestStart];
        List<int[]> runs = new ArrayList<>();
        int x = 0;
        while (x < w) {
            if (Arrays.equals(cols[x], fill)) {
                int end = x;
                while (end + 1 < w && Arrays.equals(cols[end + 1], fill)) end++;
                if (end - x + 1 >= 4) runs.add(new int[]{x, end});
                x = end + 1;
            } else {
                x++;
            }
        }
        int first = runs.get(0)[0];
        int last = runs.get(runs.size() - 1)[1];
        int firstInner = -1, lastInner = -1;
        for (int i = first; i <= last; i++) {
            if (!Arrays.equals(cols[i], fill)) {
                if (firstInner < 0) firstInner = i;
                lastInner = i;
            }
        }
        int[] centre = firstInner >= 0 ? new int[]{firstInner, lastInner + 1} : null;
        return new SliceResult(first, last, centre);
    }

    private static BufferedImage glyph(BufferedImage image, int x0, int x1) {
        int width = x1 - x0;
        int height = image.getHeight();
        BufferedImage piece = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                piece.setRGB(x, y, image.getRGB(x0 + x, y));
            }
        }
        boolean rightColumnTransparent = true;
        for (int y = 0; y < height; y++) {
            if ((piece.getRGB(width - 1, y) >>> 24) != 0) {
                rightColumnTransparent = false;
                break;
            }
        }
        if (rightColumnTransparent) {
            piece.setRGB(width - 1, 0, 0x01000000);
        }
        return piece;
    }

    @SuppressWarnings("unchecked")
    private static void cardPanels(Path assets, ZipOutputStream archive, Path manifestOut) throws IOException {
        Map<String, Object> frameFont = GSON.fromJson(
                Files.readString(assets.resolve("curios/font/card_frame.json"), StandardCharsets.UTF_8), Map.class);
        Map<String, int[]> metrics = new HashMap<>();
        for (Object pObj : (List<?>) frameFont.get("providers")) {
            Map<String, Object> p = (Map<String, Object>) pObj;
            if ("bitmap".equals(p.get("type"))) {
                metrics.put(String.valueOf(p.get("file")),
                        new int[]{((Number) p.get("height")).intValue(), ((Number) p.get("ascent")).intValue()});
            }
        }
        List<PanelProvider> providers = new ArrayList<>();
        Map<String, Object> tiers = new LinkedHashMap<>();
        for (int t = 0; t < TIERS.size(); t++) {
            String tier = TIERS.get(t);
            Map<String, Object> tierMap = new LinkedHashMap<>();
            tiers.put(tier, tierMap);
            for (int q = 0; q < PARTS.size(); q++) {
                String part = PARTS.get(q);
                String source = "curios:font/card_frame/" + tier + "_" + part + ".png";
                int[] m = metrics.get(source);
                int height = m[0], ascent = m[1];
                String relTex = source.substring(source.indexOf(':') + 1);
                BufferedImage image = toArgb(ImageIO.read(assets.resolve("curios/textures").resolve(relTex).toFile()));
                SliceResult sliced = slicePanel(image);
                int base = PANEL_BASE + t * 48 + q * 16;

                Map<String, int[]> pieces = new LinkedHashMap<>();
                pieces.put("left", new int[]{0, sliced.first()});
                if (sliced.centre() != null) pieces.put("centre", sliced.centre());
                pieces.put("right", new int[]{sliced.last() + 1, image.getWidth()});

                Map<String, Object> layout = new LinkedHashMap<>();
                List<String> names = List.of("left", "centre", "right");
                for (int index = 0; index < names.size(); index++) {
                    String name = names.get(index);
                    int[] span = pieces.get(name);
                    if (span == null || span[0] >= span[1]) continue;
                    String ch = String.valueOf((char) (base + index));
                    layout.put(name, List.of(ch, span[1] - span[0]));
                    providers.add(new PanelProvider(tier + "_" + part + "_" + name, glyph(image, span[0], span[1]), height, ascent, ch));
                }
                if (sliced.centre() != null) {
                    int leftFill = sliced.centre()[0] - sliced.first();
                    int rightFill = sliced.last() - sliced.centre()[1] + 1;
                    layout.put("bias", leftFill - (leftFill + rightFill) / 2);
                }
                Map<String, String> fills = new LinkedHashMap<>();
                for (int k = 0; k < FILLS.length; k++) {
                    int size = FILLS[k];
                    BufferedImage strip = new BufferedImage(size, image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < image.getHeight(); y++) {
                        int rgb = image.getRGB(sliced.first(), y);
                        for (int x = 0; x < size; x++) strip.setRGB(x, y, rgb);
                    }
                    String ch = String.valueOf((char) (base + 3 + k));
                    providers.add(new PanelProvider(tier + "_" + part + "_fill" + size, glyph(strip, 0, size), height, ascent, ch));
                    fills.put(Integer.toString(size), ch);
                }
                layout.put("fills", fills);
                tierMap.put(part, layout);
            }
        }

        List<Map<String, Object>> fontProviders = new ArrayList<>();
        for (PanelProvider pp : providers) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(pp.image(), "PNG", baos);
            writeBytes(archive, "assets/curios/textures/font/card_panel/" + pp.name() + ".png", baos.toByteArray());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "bitmap");
            entry.put("file", "curios:font/card_panel/" + pp.name() + ".png");
            entry.put("height", pp.height());
            entry.put("ascent", pp.ascent());
            entry.put("chars", List.of(pp.glyph()));
            fontProviders.add(entry);
        }

        Map<String, Integer> advances = new LinkedHashMap<>();
        Map<String, String> negativeManifest = new LinkedHashMap<>();
        for (int k = 0; k < 10; k++) {
            String ch = String.valueOf((char) (NEGATIVE_BASE + k));
            int adv = -(1 << k);
            advances.put(ch, adv);
            negativeManifest.put(Integer.toString(-adv), ch);
        }
        advances.put(String.valueOf((char) PANEL_IN), -3);
        Map<String, Object> spaceProvider = new LinkedHashMap<>();
        spaceProvider.put("type", "space");
        spaceProvider.put("advances", advances);
        fontProviders.add(spaceProvider);

        writeJson(archive, "assets/curios/font/card_panel.json", Map.of("providers", fontProviders));

        if (manifestOut != null) {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("font", PANEL_FONT);
            manifest.put("in", String.valueOf((char) PANEL_IN));
            manifest.put("negative", negativeManifest);
            manifest.put("tiers", tiers);
            Files.createDirectories(manifestOut.getParent());
            Files.writeString(manifestOut, escapeNonAscii(GSON.toJson(manifest)), StandardCharsets.UTF_8);
        }
    }

    private static String escapeNonAscii(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c > 0x7F) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void checkFrameGlyphs(Path assets, Path frameGlyphsFile, Map<String, Object> config) throws IOException {
        Map<String, Object> manifest = GSON.fromJson(Files.readString(frameGlyphsFile, StandardCharsets.UTF_8), Map.class);
        Map<String, Object> font = GSON.fromJson(Files.readString(assets.resolve("curios/font/frames.json"), StandardCharsets.UTF_8), Map.class);
        if (!"curios:frames".equals(manifest.get("font"))) {
            throw new IllegalArgumentException("frame-glyphs.json: unexpected font " + manifest.get("font"));
        }
        Set<Character> known = new HashSet<>();
        for (Object pObj : (List<?>) font.get("providers")) {
            Map<String, Object> provider = (Map<String, Object>) pObj;
            if (provider.get("advances") instanceof Map<?, ?> adv) {
                for (Object key : adv.keySet()) addChars(known, String.valueOf(key));
            }
            if (provider.get("chars") instanceof List<?> rows) {
                for (Object row : rows) addChars(known, String.valueOf(row));
            }
        }
        Map<String, Object> manifestFrames = (Map<String, Object>) manifest.get("frames");
        String manifestIn = String.valueOf(manifest.get("in"));
        for (String style : ((Map<String, Object>) config.get("frames")).keySet()) {
            if (TIERS.contains(style)) continue;
            Map<String, Object> frame = (Map<String, Object>) manifestFrames.get(style);
            if (frame == null) throw new IllegalArgumentException("frame-glyphs.json: no lore panel for frame " + style);
            Map<String, Object> item = (Map<String, Object>) frame.get("item");
            Set<Character> used = new TreeSet<>();
            addChars(used, manifestIn);
            addChars(used, String.valueOf(frame.get("out")));
            for (String key : List.of("header", "top", "body", "bottom", "final")) {
                addChars(used, String.valueOf(item.get(key)));
            }
            for (Object cutObj : ((Map<?, ?>) item.get("fixed")).values()) {
                Map<String, Object> cut = (Map<String, Object>) cutObj;
                addChars(used, String.valueOf(cut.get("header")));
                addChars(used, String.valueOf(cut.get("lines")));
                addChars(used, String.valueOf(cut.get("final")));
            }
            List<String> missing = new ArrayList<>();
            for (char c : used) {
                if (!known.contains(c)) missing.add(String.format("U+%04X", (int) c));
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("frame-glyphs.json: " + style + " uses glyphs missing from font/frames.json: " + missing);
            }
        }
    }

    private static void addChars(Set<Character> out, String text) {
        for (int i = 0; i < text.length(); i++) out.add(text.charAt(i));
    }

    private static Map<String, Object> paperBase() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parent", "minecraft:item/generated");
        out.put("textures", Map.of("layer0", "minecraft:item/paper"));
        return out;
    }

    @SuppressWarnings("unchecked")
    public static void build(Path packDir, Path resourcesDir, Path definitionsFile, Path output, Path manifestOut) throws IOException {
        Path assets = packDir.resolve("assets");
        Map<String, Object> config = new Yaml().load(Files.readString(definitionsFile, StandardCharsets.UTF_8));
        List<Route> routes = new ArrayList<>();
        for (var entry : ((Map<String, Object>) config.get("items")).entrySet()) {
            Map<String, Object> def = (Map<String, Object>) entry.getValue();
            routes.add(new Route(((Number) def.get("model")).intValue(), entry.getKey(), String.valueOf(def.get("texture"))));
        }
        int iconIndex = 0;
        for (var entry : ICONS.entrySet()) {
            routes.add(new Route(2679900 + iconIndex++, "icon_" + entry.getKey(), entry.getValue()));
        }
        routes.sort(Comparator.comparingInt(Route::model).thenComparing(Route::name));
        Set<Integer> seenModels = new HashSet<>();
        for (Route r : routes) {
            if (!seenModels.add(r.model())) throw new IllegalArgumentException("Duplicate custom model data");
        }

        Map<Integer, String> selectors = new TreeMap<>();
        for (Route r : routes) {
            if (!Files.isRegularFile(assets.resolve("curios/textures").resolve(r.texture() + ".png"))) {
                throw new IllegalArgumentException(r.name() + ": missing texture " + r.texture());
            }
            selectors.put(r.model(), "curios:item/" + r.name());
            selectors.putIfAbsent(r.model() + 1, "curios:item/plain_paper");
        }

        for (String style : ((Map<String, Object>) config.get("frames")).keySet()) {
            for (String part : List.of("background", "frame")) {
                Path sprite = assets.resolve("curios/textures/gui/sprites/tooltip/" + style + "_" + part + ".png");
                if (!Files.isRegularFile(sprite)) throw new IllegalArgumentException("Missing tooltip sprite: " + sprite.getFileName());
            }
        }
        checkFrameGlyphs(assets, resourcesDir.resolve("frame-glyphs.json"), config);

        try (var fonts = Files.list(assets.resolve("curios/font"))) {
            for (Path fontFile : fonts.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Map<String, Object> font = GSON.fromJson(Files.readString(fontFile, StandardCharsets.UTF_8), Map.class);
                for (Object pObj : (List<?>) font.get("providers")) {
                    Map<String, Object> provider = (Map<String, Object>) pObj;
                    if (!"bitmap".equals(provider.get("type"))) continue;
                    String file = String.valueOf(provider.get("file"));
                    int colon = file.indexOf(':');
                    String ns = file.substring(0, colon);
                    String tex = file.substring(colon + 1);
                    if (!Files.isRegularFile(assets.resolve(ns).resolve("textures").resolve(tex))) {
                        throw new IllegalArgumentException(fontFile.getFileName() + ": missing " + file);
                    }
                }
            }
        }

        Files.createDirectories(output.getParent());
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(output))) {
            archive.setLevel(Deflater.DEFAULT_COMPRESSION);
            cardPanels(assets, archive, manifestOut);

            Map<String, Object> packMeta = new LinkedHashMap<>();
            packMeta.put("pack_format", 15);
            packMeta.put("supported_formats", List.of(15, 97));
            packMeta.put("min_format", 15);
            packMeta.put("max_format", List.of(97, 1));
            packMeta.put("description", "§6Curios Resources §7v1.0");
            writeJson(archive, "pack.mcmeta", Map.of("pack", packMeta));
            writeBytes(archive, "pack.png", Files.readAllBytes(packDir.resolve("pack.png")));

            List<Path> assetFiles;
            try (var walk = Files.walk(assets)) {
                assetFiles = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path p : assetFiles) {
                writeBytes(archive, packDir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
            Set<String> textures = new TreeSet<>();
            for (Route r : routes) textures.add(r.texture());
            for (String tex : textures) {
                for (String ext : List.of(".png", ".png.mcmeta")) {
                    Path src = assets.resolve("curios/textures").resolve(tex + ext);
                    if (Files.isRegularFile(src)) {
                        writeBytes(archive, "assets/curios/textures/item/" + tex + ext, Files.readAllBytes(src));
                    }
                }
            }
            for (Route r : routes) {
                Map<String, Object> itemModel = new LinkedHashMap<>();
                itemModel.put("parent", "minecraft:item/generated");
                itemModel.put("textures", Map.of("layer0", "curios:item/" + r.texture()));
                writeJson(archive, "assets/curios/models/item/" + r.name() + ".json", itemModel);
            }
            writeJson(archive, "assets/curios/models/item/plain_paper.json", paperBase());

            List<Map<String, Object>> overrides = new ArrayList<>();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (var e : selectors.entrySet()) {
                Map<String, Object> ov = new LinkedHashMap<>();
                ov.put("predicate", Map.of("custom_model_data", e.getKey()));
                ov.put("model", e.getValue());
                overrides.add(ov);

                Map<String, Object> mod = new LinkedHashMap<>();
                mod.put("type", "minecraft:model");
                mod.put("model", e.getValue());
                Map<String, Object> ent = new LinkedHashMap<>();
                ent.put("threshold", e.getKey());
                ent.put("model", mod);
                entries.add(ent);
            }
            Map<String, Object> legacyPaper = paperBase();
            legacyPaper.put("overrides", overrides);
            writeJson(archive, "assets/minecraft/models/item/paper.json", legacyPaper);

            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "minecraft:model");
            fallback.put("model", "minecraft:item/paper");
            Map<String, Object> dispatch = new LinkedHashMap<>();
            dispatch.put("type", "minecraft:range_dispatch");
            dispatch.put("property", "minecraft:custom_model_data");
            dispatch.put("index", 0);
            dispatch.put("fallback", fallback);
            dispatch.put("entries", entries);
            writeJson(archive, "assets/minecraft/items/paper.json", Map.of("model", dispatch));
        }
    }

    private static void writeJson(ZipOutputStream archive, String path, Object value) throws IOException {
        writeBytes(archive, path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ZipOutputStream archive, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTimeLocal(FIXED_TIME);
        archive.putNextEntry(entry);
        archive.write(bytes);
        archive.closeEntry();
    }
}
