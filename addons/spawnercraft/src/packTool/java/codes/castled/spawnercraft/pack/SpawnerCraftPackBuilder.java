package codes.castled.spawnercraft.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the SpawnerCraft resource pack from bundled art and optional SF changer assets. */
public final class SpawnerCraftPackBuilder {
    private static final String MODEL = "spawnercraft:item/spawner_changer";
    private static final String PLAIN = "spawnercraft:item/plain_blaze_rod";
    private static final String CARRIER = "minecraft:item/blaze_rod";
    private static final String MODEL_FILE = "assets/spawnercraft/models/item/spawner_changer.json";
    private static final String OVERLAY = "spawnercraft_model_1_21_11";
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private record Vec3i(int x, int y, int z) {
        Vec3i negate() { return new Vec3i(-x, -y, -z); }
    }

    private record FaceBasis(Vec3i normal, Vec3i u, Vec3i v) {}

    private static final Map<String, FaceBasis> FACES = new LinkedHashMap<>();
    private static final Map<Vec3i, String> FACE_NAMES = new HashMap<>();

    static {
        FACES.put("down", new FaceBasis(new Vec3i(0, -1, 0), new Vec3i(1, 0, 0), new Vec3i(0, 0, -1)));
        FACES.put("up", new FaceBasis(new Vec3i(0, 1, 0), new Vec3i(1, 0, 0), new Vec3i(0, 0, 1)));
        FACES.put("north", new FaceBasis(new Vec3i(0, 0, -1), new Vec3i(-1, 0, 0), new Vec3i(0, -1, 0)));
        FACES.put("south", new FaceBasis(new Vec3i(0, 0, 1), new Vec3i(1, 0, 0), new Vec3i(0, -1, 0)));
        FACES.put("west", new FaceBasis(new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, -1, 0)));
        FACES.put("east", new FaceBasis(new Vec3i(1, 0, 0), new Vec3i(0, 0, -1), new Vec3i(0, -1, 0)));
        for (var e : FACES.entrySet()) FACE_NAMES.put(e.getValue().normal(), e.getKey());
    }

    private SpawnerCraftPackBuilder() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: SpawnerCraftPackBuilder <packDir> <resourcesDir> <outputZip> [--sf <sfDir>]");
        }
        Path packDir = Path.of(args[0]);
        Path resourcesDir = Path.of(args[1]);
        Path output = Path.of(args[2]);
        Path sf = null;
        for (int i = 3; i < args.length; i++) {
            if ("--sf".equals(args[i]) && i + 1 < args.length) {
                sf = Path.of(args[++i]);
            }
        }
        build(packDir, resourcesDir, output, sf);
    }

    private static double[][] rotation(char axis, double degrees) {
        double rad = Math.toRadians(degrees);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        return switch (axis) {
            case 'x' -> new double[][]{{1, 0, 0}, {0, cos, -sin}, {0, sin, cos}};
            case 'y' -> new double[][]{{cos, 0, sin}, {0, 1, 0}, {-sin, 0, cos}};
            case 'z' -> new double[][]{{cos, -sin, 0}, {sin, cos, 0}, {0, 0, 1}};
            default -> throw new IllegalArgumentException("Unknown axis: " + axis);
        };
    }

    private static double[][] multiply(double[][] left, double[][] right) {
        double[][] out = new double[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r][c] = left[r][0] * right[0][c] + left[r][1] * right[1][c] + left[r][2] * right[2][c];
            }
        }
        return out;
    }

    private static double[] transform(int[][] matrix, double[] vec) {
        return new double[]{
                matrix[0][0] * vec[0] + matrix[0][1] * vec[1] + matrix[0][2] * vec[2],
                matrix[1][0] * vec[0] + matrix[1][1] * vec[1] + matrix[1][2] * vec[2],
                matrix[2][0] * vec[0] + matrix[2][1] * vec[1] + matrix[2][2] * vec[2]
        };
    }

    private static Vec3i transform(int[][] matrix, Vec3i vec) {
        return new Vec3i(
                matrix[0][0] * vec.x() + matrix[0][1] * vec.y() + matrix[0][2] * vec.z(),
                matrix[1][0] * vec.x() + matrix[1][1] * vec.y() + matrix[1][2] * vec.z(),
                matrix[2][0] * vec.x() + matrix[2][1] * vec.y() + matrix[2][2] * vec.z()
        );
    }

    private static boolean validSignedPermutation(int[][] m) {
        for (int i = 0; i < 3; i++) {
            int rowSum = Math.abs(m[i][0]) + Math.abs(m[i][1]) + Math.abs(m[i][2]);
            int colSum = Math.abs(m[0][i]) + Math.abs(m[1][i]) + Math.abs(m[2][i]);
            if (rowSum != 1 || colSum != 1) return false;
        }
        return true;
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    @SuppressWarnings("unchecked")
    private static void rebaseElement(Map<String, Object> element) {
        Map<String, Object> rot = (Map<String, Object>) element.get("rotation");
        if (rot == null) return;
        if (rot.containsKey("axis")) {
            double ang = num(rot.get("angle"));
            if (ang == -45 || ang == -22.5 || ang == 0 || ang == 22.5 || ang == 45) return;
        }
        double rx = 0, ry = 0, rz = 0;
        if (rot.containsKey("axis")) {
            String ax = String.valueOf(rot.get("axis"));
            double ang = num(rot.get("angle"));
            if ("x".equals(ax)) rx = ang;
            else if ("y".equals(ax)) ry = ang;
            else if ("z".equals(ax)) rz = ang;
        } else {
            rx = num(rot.get("x"));
            ry = num(rot.get("y"));
            rz = num(rot.get("z"));
        }
        double[][] authored = multiply(rotation('z', rz), multiply(rotation('y', ry), rotation('x', rx)));

        char chosenAxis = 0;
        double chosenAngle = 0;
        int[][] snapped = null;
        double[] angles = {0, -45, 45, -22.5, 22.5};
        char[] axes = {'x', 'y', 'z'};
        outer:
        for (double angle : angles) {
            for (char axis : axes) {
                double[][] basis = multiply(rotation(axis, -angle), authored);
                int[][] candidate = new int[3][3];
                double maxDiff = 0;
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        candidate[r][c] = (int) Math.round(basis[r][c]);
                        maxDiff = Math.max(maxDiff, Math.abs(basis[r][c] - candidate[r][c]));
                    }
                }
                if (!validSignedPermutation(candidate)) continue;
                if (maxDiff <= 0.045) {
                    chosenAxis = axis;
                    chosenAngle = angle;
                    snapped = candidate;
                    break outer;
                }
            }
        }
        if (snapped == null) {
            throw new IllegalArgumentException("Unsupported legacy spawner changer rotation: " + rot);
        }

        List<Number> originList = (List<Number>) rot.get("origin");
        double[] origin = {originList.get(0).doubleValue(), originList.get(1).doubleValue(), originList.get(2).doubleValue()};
        double[] from = corner((List<Number>) element.get("from"), origin, snapped);
        double[] to = corner((List<Number>) element.get("to"), origin, snapped);
        List<Double> newFrom = List.of(Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2]));
        List<Double> newTo = List.of(Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2]));
        for (double n : newFrom) if (n < -16 || n > 32) throw new IllegalArgumentException("Legacy spawner changer element exceeds Minecraft's box bounds");
        for (double n : newTo) if (n < -16 || n > 32) throw new IllegalArgumentException("Legacy spawner changer element exceeds Minecraft's box bounds");
        element.put("from", newFrom);
        element.put("to", newTo);

        Map<String, Object> newRot = new LinkedHashMap<>();
        newRot.put("origin", originList);
        newRot.put("axis", String.valueOf(chosenAxis));
        newRot.put("angle", chosenAngle == Math.rint(chosenAngle) ? (int) chosenAngle : chosenAngle);
        element.put("rotation", newRot);

        Map<String, Object> oldFaces = (Map<String, Object>) element.get("faces");
        Map<String, Object> newFaces = new LinkedHashMap<>();
        for (var e : oldFaces.entrySet()) {
            FaceBasis basis = FACES.get(e.getKey());
            String newName = FACE_NAMES.get(transform(snapped, basis.normal()));
            FaceBasis targetBasis = FACES.get(newName);
            List<Vec3i> directions = List.of(targetBasis.u(), targetBasis.v(), targetBasis.u().negate(), targetBasis.v().negate());
            int turn = directions.indexOf(transform(snapped, basis.u())) * 90;
            Map<String, Object> face = (Map<String, Object>) e.getValue();
            if (turn != 0) {
                int existing = face.get("rotation") instanceof Number n ? n.intValue() : 0;
                face.put("rotation", (existing + turn) % 360);
            }
            newFaces.put(newName, face);
        }
        element.put("faces", newFaces);
    }

    private static double[] corner(List<Number> point, double[] origin, int[][] snapped) {
        double[] diff = {point.get(0).doubleValue() - origin[0], point.get(1).doubleValue() - origin[1], point.get(2).doubleValue() - origin[2]};
        double[] offset = transform(snapped, diff);
        return new double[]{round6(origin[0] + offset[0]), round6(origin[1] + offset[1]), round6(origin[2] + offset[2])};
    }

    private static Map<String, Object> modelRef(String model) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "minecraft:model");
        out.put("model", model);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static void build(Path packDir, Path resourcesDir, Path output, Path sf) throws IOException {
        Map<String, Object> yaml = new Yaml().load(Files.readString(resourcesDir.resolve("spawner_changer.yml"), StandardCharsets.UTF_8));
        int modelData = ((Number) ((Map<String, Object>) yaml.get("item")).get("custom-model-data")).intValue();

        Map<String, Object> modern = null;
        Map<String, Object> legacy = null;
        byte[] texture = null;
        if (sf != null) {
            Path assets = sf.resolve("Nexo/pack/assets/sf");
            String modernJson = Files.readString(assets.resolve("models/items/spawner_changer.json"), StandardCharsets.UTF_8);
            modern = GSON.fromJson(modernJson, Map.class);
            Map<String, Object> textures = (Map<String, Object>) modern.get("textures");
            Map<String, Object> rebound = new LinkedHashMap<>();
            for (String key : textures.keySet()) rebound.put(key, MODEL);
            modern.put("textures", rebound);
            texture = Files.readAllBytes(assets.resolve("textures/items/spawner_changer.png"));

            legacy = GSON.fromJson(GSON.toJson(modern), Map.class);
            legacy.remove("format_version");
            Map<String, Object> display = (Map<String, Object>) legacy.get("display");
            if (display != null) display.remove("on_shelf");
            for (Object el : (List<?>) legacy.get("elements")) {
                rebaseElement((Map<String, Object>) el);
            }
        }

        Map<String, Object> carrier = new LinkedHashMap<>();
        carrier.put("parent", "minecraft:item/handheld");
        carrier.put("textures", Map.of("layer0", CARRIER));

        Files.createDirectories(output.getParent());
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(output))) {
            archive.setLevel(Deflater.DEFAULT_COMPRESSION);

            Map<String, Object> pack = new LinkedHashMap<>();
            pack.put("description", "§bSpawnercraft Resources §7v1.0");
            pack.put("pack_format", 15);
            pack.put("supported_formats", List.of(15, 97));
            pack.put("min_format", 15);
            pack.put("max_format", List.of(97, 1));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("pack", pack);
            if (sf != null) {
                Map<String, Object> overlayEntry = new LinkedHashMap<>();
                overlayEntry.put("directory", OVERLAY);
                overlayEntry.put("formats", List.of(75, 97));
                overlayEntry.put("min_format", 75);
                overlayEntry.put("max_format", List.of(97, 1));
                metadata.put("overlays", Map.of("entries", List.of(overlayEntry)));
            }
            write(archive, "pack.mcmeta", metadata);
            write(archive, "pack.png", Files.readAllBytes(packDir.resolve("pack.png")));
            if (sf == null) return;

            write(archive, "assets/spawnercraft/textures/item/spawner_changer.png", texture);
            write(archive, MODEL_FILE, legacy);
            write(archive, OVERLAY + "/" + MODEL_FILE, modern);
            write(archive, "assets/spawnercraft/models/item/plain_blaze_rod.json", carrier);

            Map<String, Object> rodLegacy = new LinkedHashMap<>(carrier);
            rodLegacy.put("overrides", List.of(
                    Map.of("predicate", Map.of("custom_model_data", modelData), "model", MODEL),
                    Map.of("predicate", Map.of("custom_model_data", modelData + 1), "model", PLAIN)
            ));
            write(archive, "assets/minecraft/models/item/blaze_rod.json", rodLegacy);

            Map<String, Object> dispatch = new LinkedHashMap<>();
            dispatch.put("type", "minecraft:range_dispatch");
            dispatch.put("property", "minecraft:custom_model_data");
            dispatch.put("index", 0);
            dispatch.put("fallback", modelRef(CARRIER));
            dispatch.put("entries", List.of(
                    Map.of("threshold", modelData, "model", modelRef(MODEL)),
                    Map.of("threshold", modelData + 1, "model", modelRef(CARRIER))
            ));
            write(archive, "assets/minecraft/items/blaze_rod.json", Map.of("model", dispatch));
        }
    }

    private static void write(ZipOutputStream archive, String path, Object data) throws IOException {
        byte[] bytes = data instanceof byte[] raw ? raw : GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry(path);
        entry.setTimeLocal(FIXED_TIME);
        archive.putNextEntry(entry);
        archive.write(bytes);
        archive.closeEntry();
    }
}
