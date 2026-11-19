package codes.castled.allium.command.essentials;

import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Strider;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.Colorable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Essentials-compatible mob name resolution, {@code :data} modifier application, and stacked
 * spawning, version-safe across Spigot 1.20.1 through Paper 26.3.
 */
public final class MobSpawner {

    public record MobEntry(String canonicalName, EntityType type) {}

    public record ParsedPart(String mobToken, String rawData) {}

    private static final Random RANDOM = new Random();
    private static final Map<String, MobEntry> BY_ALIAS;
    private static final List<MobEntry> CANONICAL_MOBS;

    static {
        Map<String, MobEntry> aliases = new LinkedHashMap<>();
        Map<EntityType, MobEntry> canonical = new LinkedHashMap<>();

        for (EntityType type : EntityType.values()) {
            if (type == EntityType.PLAYER || type == EntityType.UNKNOWN) continue;
            if (!type.isSpawnable() || type.getEntityClass() == null) continue;
            String raw = type.name().toLowerCase(Locale.ROOT);
            String compact = raw.replace("_", "");
            MobEntry entry = new MobEntry(compact, type);
            canonical.put(type, entry);
            aliases.putIfAbsent(compact, entry);
            aliases.putIfAbsent(raw, entry);
        }

        // Essentials classic aliases across 1.20.1 - 26.3 enum renames.
        registerAlias(aliases, "snowman", "SNOW_GOLEM", "SNOWMAN");
        registerAlias(aliases, "mushroomcow", "MOOSHROOM", "MUSHROOM_COW");
        registerAlias(aliases, "mooshroom", "MOOSHROOM", "MUSHROOM_COW");
        registerAlias(aliases, "pigzombie", "ZOMBIFIED_PIGLIN", "PIG_ZOMBIE");
        registerAlias(aliases, "endercrystal", "END_CRYSTAL", "ENDER_CRYSTAL");
        registerAlias(aliases, "strayskeleton", "STRAY");
        registerAlias(aliases, "exp", "EXPERIENCE_ORB");
        registerAlias(aliases, "xporb", "EXPERIENCE_ORB");
        registerAlias(aliases, "chestminecart", "CHEST_MINECART", "MINECART_CHEST");
        registerAlias(aliases, "tntminecart", "TNT_MINECART", "MINECART_TNT");
        registerAlias(aliases, "furnaceminecart", "FURNACE_MINECART", "MINECART_FURNACE");
        registerAlias(aliases, "hopperminecart", "HOPPER_MINECART", "MINECART_HOPPER");
        registerAlias(aliases, "spawnerminecart", "SPAWNER_MINECART", "MINECART_MOB_SPAWNER");
        registerAlias(aliases, "boat", "OAK_BOAT", "BOAT");
        registerAlias(aliases, "chestboat", "OAK_CHEST_BOAT", "CHEST_BOAT");

        BY_ALIAS = Collections.unmodifiableMap(aliases);
        List<MobEntry> sorted = new ArrayList<>(canonical.values());
        sorted.sort((a, b) -> a.canonicalName().compareTo(b.canonicalName()));
        CANONICAL_MOBS = Collections.unmodifiableList(sorted);
    }

    private MobSpawner() {}

    private static void registerAlias(Map<String, MobEntry> aliases, String alias, String... enumNames) {
        for (String enumName : enumNames) {
            try {
                EntityType type = EntityType.valueOf(enumName);
                if (type.isSpawnable() && type.getEntityClass() != null) {
                    aliases.put(alias.toLowerCase(Locale.ROOT), new MobEntry(alias.toLowerCase(Locale.ROOT), type));
                    return;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public static Optional<MobEntry> lookup(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String lower = token.trim().toLowerCase(Locale.ROOT);
        MobEntry exact = BY_ALIAS.get(lower);
        if (exact != null) return Optional.of(exact);
        return Optional.ofNullable(BY_ALIAS.get(lower.replace("_", "").replace("-", "")));
    }

    public static List<MobEntry> allMobs() {
        return CANONICAL_MOBS;
    }

    /** Parses {@code zombie:baby,horse:black:saddle} into ordered {@link ParsedPart}s. */
    public static List<ParsedPart> parseSpec(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        String[] chunks = spec.split(",", -1);
        List<ParsedPart> parts = new ArrayList<>(chunks.length);
        for (String chunk : chunks) {
            int colon = chunk.indexOf(':');
            if (colon < 0) {
                parts.add(new ParsedPart(chunk.trim(), null));
            } else {
                parts.add(new ParsedPart(chunk.substring(0, colon).trim(), chunk.substring(colon + 1).trim()));
            }
        }
        return parts;
    }

    /** Spawns one stack (1 or more entities mounted in order, matching Essentials). */
    public static Entity spawnStack(Location location, List<MobEntry> entries, List<String> rawDatas, Player owner) {
        World world = location.getWorld();
        if (world == null || entries.isEmpty()) return null;
        Entity previous = null;
        Entity root = null;
        for (int i = 0; i < entries.size(); i++) {
            MobEntry entry = entries.get(i);
            Entity spawned = spawnOne(world, location, entry.type());
            if (spawned == null) return null;
            applyDefaults(entry.type(), spawned);
            String data = i < rawDatas.size() ? rawDatas.get(i) : null;
            if (data != null && !data.isEmpty()) {
                applyData(spawned, data, owner);
            }
            if (root == null) {
                root = spawned;
            }
            if (previous != null) {
                previous.addPassenger(spawned);
            }
            previous = spawned;
        }
        return root;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Entity spawnOne(World world, Location location, EntityType type) {
        Class<? extends Entity> clazz = type.getEntityClass();
        if (clazz != null) {
            try {
                return world.spawn(location, (Class) clazz);
            } catch (IllegalArgumentException ignored) {
                return world.spawnEntity(location, type);
            }
        }
        return world.spawnEntity(location, type);
    }

    private static void applyDefaults(EntityType type, Entity entity) {
        if (type == EntityType.SKELETON && entity instanceof LivingEntity living) {
            EntityEquipment eq = living.getEquipment();
            if (eq != null) {
                eq.setItemInMainHand(new ItemStack(Material.BOW, 1));
            }
        }
    }

    /** Applies Essentials-style {@code :data} modifiers to a spawned entity. */
    public static void applyData(Entity entity, String rawData, Player owner) {
        if (entity == null || rawData == null || rawData.isBlank()) return;
        String lower = rawData.toLowerCase(Locale.ROOT);
        String[] tokens = lower.split("[:;+\\s]+");

        // Ageable / Zombie baby or adult
        boolean wantsBaby = hasAny(lower, tokens, "baby", "puppy", "kitten", "piglet", "chick", "colt", "lamb", "calf", "child");
        boolean wantsAdult = hasAny(lower, tokens, "adult");
        if (entity instanceof Zombie zombie) {
            if (wantsBaby) zombie.setBaby(true);
            else if (wantsAdult) zombie.setBaby(false);
        } else if (entity instanceof Ageable ageable) {
            if (wantsBaby) ageable.setBaby();
            else if (wantsAdult) ageable.setAdult();
        }

        // Taming
        if (entity instanceof Tameable tameable && hasAny(lower, tokens, "tamed", "tame")) {
            tameable.setTamed(true);
            if (owner != null) {
                tameable.setOwner(owner);
            }
        }

        // Creeper charged/powered
        if (entity instanceof Creeper creeper && hasAny(lower, tokens, "powered", "charged", "electric")) {
            creeper.setPowered(true);
        }

        // Wolf angry & collar color
        if (entity instanceof Wolf wolf) {
            if (hasAny(lower, tokens, "angry", "rabid")) {
                wolf.setAngry(true);
            }
            DyeColor collar = findDyeColor(lower, tokens);
            if (collar != null) {
                wolf.setCollarColor(collar);
            }
        }

        // Cat collar color
        if (entity instanceof Cat cat) {
            DyeColor collar = findDyeColor(lower, tokens);
            if (collar != null) {
                cat.setCollarColor(collar);
            }
        }

        // Sheep / Colorable
        if (entity instanceof Colorable colorable) {
            DyeColor dye = findDyeColor(lower, tokens);
            if (dye != null) {
                colorable.setColor(dye);
            }
        }

        // Shulker color
        if (entity instanceof Shulker shulker) {
            DyeColor dye = findDyeColor(lower, tokens);
            if (dye != null) {
                shulker.setColor(dye);
            }
        }

        // Pig / Strider saddle
        if (entity instanceof Pig pig && hasAny(lower, tokens, "saddle")) {
            pig.setSaddle(true);
        }
        if (entity instanceof Strider strider && hasAny(lower, tokens, "saddle")) {
            strider.setSaddle(true);
        }

        // Slime / MagmaCube / Phantom size
        if (entity instanceof Slime slime) {
            Integer size = findNumber(tokens, 1, 100);
            if (size != null) slime.setSize(size);
        } else if (entity instanceof Phantom phantom) {
            Integer size = findNumber(tokens, 1, 100);
            if (size != null) phantom.setSize(size);
        }

        // Experience orb amount
        if (entity instanceof ExperienceOrb orb) {
            Integer exp = findNumber(tokens, 1, 2_000_000_000);
            if (exp != null) orb.setExperience(exp);
        }

        // Zombie / Skeleton armor and weapons
        if (entity instanceof Zombie || entity.getType() == EntityType.SKELETON) {
            applyHumanoidEquipment((LivingEntity) entity, lower, tokens);
        }

        // Horse color, style, saddle, horse armor
        if (entity instanceof Horse horse) {
            applyHorseData(horse, lower, tokens, owner);
        }

        // Villager profession and biome type (version-safe lookup)
        if (entity instanceof Villager villager) {
            applyVillagerData(villager, tokens);
        }

        // Parrot variant
        if (entity instanceof Parrot parrot) {
            for (Parrot.Variant v : Parrot.Variant.values()) {
                if (hasAny(lower, tokens, v.name().toLowerCase(Locale.ROOT))) {
                    parrot.setVariant(v);
                    break;
                }
            }
        }

        // Fox type
        if (entity instanceof Fox fox) {
            if (hasAny(lower, tokens, "snow", "white")) fox.setFoxType(Fox.Type.SNOW);
            else if (hasAny(lower, tokens, "red")) fox.setFoxType(Fox.Type.RED);
        }

        // Axolotl variant
        if (entity instanceof Axolotl axolotl) {
            if (hasAny(lower, tokens, "lucy", "leucistic", "pink")) axolotl.setVariant(Axolotl.Variant.LUCY);
            else if (hasAny(lower, tokens, "wild", "brown")) axolotl.setVariant(Axolotl.Variant.WILD);
            else if (hasAny(lower, tokens, "gold")) axolotl.setVariant(Axolotl.Variant.GOLD);
            else if (hasAny(lower, tokens, "cyan")) axolotl.setVariant(Axolotl.Variant.CYAN);
            else if (hasAny(lower, tokens, "blue")) axolotl.setVariant(Axolotl.Variant.BLUE);
        }

        // Mooshroom variant
        if (entity instanceof MushroomCow cow) {
            if (hasAny(lower, tokens, "brown")) cow.setVariant(MushroomCow.Variant.BROWN);
            else if (hasAny(lower, tokens, "red")) cow.setVariant(MushroomCow.Variant.RED);
        }

        // Llama color
        if (entity instanceof Llama llama) {
            for (Llama.Color c : Llama.Color.values()) {
                if (hasAny(lower, tokens, c.name().toLowerCase(Locale.ROOT))) {
                    llama.setColor(c);
                    break;
                }
            }
        }

        // Goat screaming
        if (entity instanceof Goat goat && hasAny(lower, tokens, "screaming")) {
            goat.setScreaming(true);
        }

        // Raider patrol leader
        if (entity instanceof Raider raider && hasAny(lower, tokens, "leader")) {
            raider.setPatrolLeader(true);
        }

        // Rabbit type
        if (entity instanceof Rabbit rabbit) {
            if (hasAny(lower, tokens, "killer")) rabbit.setRabbitType(Rabbit.Type.THE_KILLER_BUNNY);
            else if (hasAny(lower, tokens, "salt")) rabbit.setRabbitType(Rabbit.Type.SALT_AND_PEPPER);
            else {
                for (Rabbit.Type t : Rabbit.Type.values()) {
                    if (hasAny(lower, tokens, t.name().toLowerCase(Locale.ROOT))) {
                        rabbit.setRabbitType(t);
                        break;
                    }
                }
            }
        }

        // Panda gene
        if (entity instanceof Panda panda) {
            for (Panda.Gene g : Panda.Gene.values()) {
                if (hasAny(lower, tokens, g.name().toLowerCase(Locale.ROOT))) {
                    panda.setMainGene(g);
                    break;
                }
            }
        }
    }

    private static void applyHumanoidEquipment(LivingEntity living, String lower, String[] tokens) {
        EntityEquipment eq = living.getEquipment();
        if (eq == null) return;
        if (lower.contains("noarmor") || lower.contains("noarmour")) {
            eq.clear();
        } else if (lower.contains("armor") || lower.contains("armour")) {
            String tier = lower.contains("netherite") ? "NETHERITE"
                    : lower.contains("diamond") ? "DIAMOND"
                    : lower.contains("gold") ? "GOLDEN"
                    : lower.contains("leather") ? "LEATHER"
                    : "IRON";
            equipPiece(eq, tier + "_HELMET", "helmet");
            equipPiece(eq, tier + "_CHESTPLATE", "chestplate");
            equipPiece(eq, tier + "_LEGGINGS", "leggings");
            equipPiece(eq, tier + "_BOOTS", "boots");
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
        }

        if (hasAny(lower, tokens, "netheritesword")) eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
        else if (hasAny(lower, tokens, "diamondsword")) eq.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        else if (hasAny(lower, tokens, "goldsword")) eq.setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
        else if (hasAny(lower, tokens, "ironsword")) eq.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        else if (hasAny(lower, tokens, "stonesword", "sword")) eq.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
        else if (hasAny(lower, tokens, "bow")) eq.setItemInMainHand(new ItemStack(Material.BOW));
    }

    private static void equipPiece(EntityEquipment eq, String materialName, String slot) {
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) return;
        ItemStack stack = new ItemStack(mat, 1);
        switch (slot) {
            case "helmet" -> eq.setHelmet(stack);
            case "chestplate" -> eq.setChestplate(stack);
            case "leggings" -> eq.setLeggings(stack);
            case "boots" -> eq.setBoots(stack);
            default -> {}
        }
    }

    private static void applyHorseData(Horse horse, String lower, String[] tokens, Player owner) {
        if (hasAny(lower, tokens, "black")) horse.setColor(Horse.Color.BLACK);
        else if (hasAny(lower, tokens, "chestnut", "liver")) horse.setColor(Horse.Color.CHESTNUT);
        else if (hasAny(lower, tokens, "creamy", "flaxen")) horse.setColor(Horse.Color.CREAMY);
        else if (hasAny(lower, tokens, "gray", "dapple")) horse.setColor(Horse.Color.GRAY);
        else if (hasAny(lower, tokens, "buckskin", "darkbrown", "dark", "dbrown")) horse.setColor(Horse.Color.DARK_BROWN);
        else if (hasAny(lower, tokens, "bay", "brown")) horse.setColor(Horse.Color.BROWN);
        else if (hasAny(lower, tokens, "white")) horse.setColor(Horse.Color.WHITE);

        if (hasAny(lower, tokens, "polka", "sooty")) horse.setStyle(Horse.Style.BLACK_DOTS);
        else if (hasAny(lower, tokens, "blaze", "socks")) horse.setStyle(Horse.Style.WHITE);
        else if (hasAny(lower, tokens, "leopard", "appaloosa")) horse.setStyle(Horse.Style.WHITE_DOTS);
        else if (hasAny(lower, tokens, "paint", "milky", "splotchy")) horse.setStyle(Horse.Style.WHITEFIELD);

        if (hasAny(lower, tokens, "saddle")) {
            horse.setTamed(true);
            if (owner != null) horse.setOwner(owner);
            horse.getInventory().setSaddle(new ItemStack(Material.SADDLE, 1));
        }
        if (lower.contains("diamondarmor")) horse.getInventory().setArmor(new ItemStack(Material.DIAMOND_HORSE_ARMOR, 1));
        else if (lower.contains("goldarmor")) horse.getInventory().setArmor(new ItemStack(Material.GOLDEN_HORSE_ARMOR, 1));
        else if (lower.contains("leatherarmor")) horse.getInventory().setArmor(new ItemStack(Material.LEATHER_HORSE_ARMOR, 1));
        else if (lower.contains("armor")) horse.getInventory().setArmor(new ItemStack(Material.IRON_HORSE_ARMOR, 1));
    }

    private static void applyVillagerData(Villager villager, String[] tokens) {
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            Object prof = resolveConstant(Villager.Profession.class, tok.toUpperCase(Locale.ROOT));
            if (prof instanceof Villager.Profession p) {
                villager.setProfession(p);
                continue;
            }
            Object biome = resolveConstant(Villager.Type.class, tok.toUpperCase(Locale.ROOT));
            if (biome instanceof Villager.Type t) {
                villager.setVillagerType(t);
            }
        }
    }

    /** Resolves both enum constants (1.20.1) and keyed interface fields (1.21+ / 26.3) safely. */
    private static Object resolveConstant(Class<?> type, String name) {
        try {
            if (type.isEnum()) {
                Method valueOf = type.getMethod("valueOf", String.class);
                return valueOf.invoke(null, name);
            }
            return type.getField(name).get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static DyeColor findDyeColor(String lower, String[] tokens) {
        if (hasAny(lower, tokens, "random")) {
            DyeColor[] values = DyeColor.values();
            return values[RANDOM.nextInt(values.length)];
        }
        for (DyeColor color : DyeColor.values()) {
            String name = color.name().toLowerCase(Locale.ROOT);
            if (hasAny(lower, tokens, name, name.replace("_", ""))) {
                return color;
            }
        }
        return null;
    }

    private static Integer findNumber(String[] tokens, int min, int max) {
        for (String tok : tokens) {
            if (tok.matches("[0-9]{1,10}")) {
                try {
                    int val = Integer.parseInt(tok);
                    if (val >= min && val <= max) return val;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static boolean hasAny(String fullLower, String[] tokens, String... needles) {
        for (String needle : needles) {
            for (String tok : tokens) {
                if (tok.equals(needle)) return true;
            }
            if (fullLower.contains(needle)) return true;
        }
        return false;
    }

    /** Lists valid data hint tokens for a given mob type (shown for {@code /spawnmob <mob>:}). */
    public static List<String> dataHints(EntityType type) {
        Set<String> hints = new TreeSet<>();
        Class<? extends Entity> clazz = type.getEntityClass();
        if (clazz == null) return List.of();
        if (Ageable.class.isAssignableFrom(clazz) || Zombie.class.isAssignableFrom(clazz)) {
            hints.add("baby");
            hints.add("adult");
        }
        if (Tameable.class.isAssignableFrom(clazz)) hints.add("tamed");
        if (Colorable.class.isAssignableFrom(clazz) || Shulker.class.isAssignableFrom(clazz)) {
            hints.add("random");
            hints.add("<color>");
        }
        if (Creeper.class.isAssignableFrom(clazz)) hints.add("powered");
        if (Wolf.class.isAssignableFrom(clazz)) {
            hints.add("angry");
            hints.add("<collar-color>");
        }
        if (Pig.class.isAssignableFrom(clazz) || Strider.class.isAssignableFrom(clazz)) hints.add("saddle");
        if (Slime.class.isAssignableFrom(clazz) || Phantom.class.isAssignableFrom(clazz)) hints.add("<1-100>");
        if (ExperienceOrb.class.isAssignableFrom(clazz)) hints.add("<amount>");
        if (Zombie.class.isAssignableFrom(clazz) || type == EntityType.SKELETON) {
            hints.addAll(List.of("ironarmor", "goldarmor", "diamondarmor", "netheritearmor", "noarmor", "ironsword", "diamondsword", "bow"));
        }
        if (Horse.class.isAssignableFrom(clazz)) {
            hints.addAll(List.of("black", "chestnut", "creamy", "gray", "darkbrown", "brown", "white", "polka", "blaze", "leopard", "paint", "saddle", "diamondarmor"));
        }
        if (Villager.class.isAssignableFrom(clazz)) {
            hints.addAll(List.of("armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher", "leatherworker", "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith", "desert", "jungle", "plains", "savanna", "snowy", "swamp", "taiga"));
        }
        if (Fox.class.isAssignableFrom(clazz)) hints.addAll(List.of("red", "snow"));
        if (Parrot.class.isAssignableFrom(clazz)) hints.addAll(List.of("red", "green", "blue", "cyan", "gray"));
        if (Axolotl.class.isAssignableFrom(clazz)) hints.addAll(List.of("lucy", "wild", "gold", "cyan", "blue"));
        if (MushroomCow.class.isAssignableFrom(clazz)) hints.addAll(List.of("red", "brown"));
        if (Goat.class.isAssignableFrom(clazz)) hints.add("screaming");
        if (Raider.class.isAssignableFrom(clazz)) hints.add("leader");
        if (Rabbit.class.isAssignableFrom(clazz)) hints.addAll(List.of("black", "brown", "gold", "salt", "white", "killer"));
        return List.copyOf(hints);
    }
}
