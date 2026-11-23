package codes.castled.spawnercraft;

import codes.castled.allium.api.AddonItems;
import codes.castled.allium.api.ItemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

final class Heads {
    record Head(String mob, String id, String name, String texture, String sound, double chance,
                double looting, double bonus, String variant) {}

    private final AddonItems items;
    private final Map<String, Head> byId = new LinkedHashMap<>();
    private final Map<String, List<Head>> byMob = new TreeMap<>();
    private final Map<String, EntityType> types = new TreeMap<>();
    private final Map<String, ItemStack> prototypes = new java.util.concurrent.ConcurrentHashMap<>();

    Heads(AddonItems items, InputStream catalog, InputStream legacy) throws IOException {
        this.items = items;
        for (EntityType type : EntityType.values()) {
            if (type != EntityType.UNKNOWN && type.isAlive() && type.isSpawnable()) types.put(type.getKey().getKey(), type);
        }
        load(catalog);
        load(legacy);
        Material core = Optional.ofNullable(Material.matchMaterial("TRIAL_SPAWNER")).orElse(Material.SPAWNER);
        items.defineStackableSigned(new ItemDefinition("spawner_core", core, "Spawner Core", null, Map.of(), true));
        items.defineVariants("spawner_core", mobs().stream().filter(types::containsKey).toList(), this::initializeCore);
    }

    private void load(InputStream catalog) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(catalog, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                var head = new Head(f[0], f[1], f[2], f[3], f[4], Double.parseDouble(f[5]),
                        Double.parseDouble(f[6]), Double.parseDouble(f[7]), f.length > 8 ? f[8] : "");
                if (byId.putIfAbsent(head.id(), head) != null) throw new IOException("Duplicate head ID " + head.id());
                byMob.computeIfAbsent(head.mob(), ignored -> new ArrayList<>()).add(head);
                items.defineStackableSigned(new ItemDefinition(itemId(head), carrier(head), head.name(), null, Map.of(), true));
            }
        }
    }

    private static Material carrier(Head head) {
        if (head.texture().length() > 48) return Material.PLAYER_HEAD;
        Material material = Material.getMaterial(head.texture());
        return material == null ? Material.PLAYER_HEAD : material;
    }

    private static String itemId(Head head) {
        return "sc_" + head.id();
    }

    Collection<String> ids() { return byId.keySet(); }
    Collection<String> mobs() { return byMob.keySet(); }
    EntityType type(String mob) { return types.get(mob); }

    Head find(String idOrMob) {
        Head head = byId.get(idOrMob);
        return head != null ? head : byMob.getOrDefault(idOrMob, List.of()).stream()
                .filter(row -> row.id().equals(idOrMob + "_head")).findFirst()
                .orElseGet(() -> byMob.getOrDefault(idOrMob, List.of()).stream().findFirst().orElse(null));
    }

    String mob(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0 || !stack.hasItemMeta() || !(stack.getItemMeta() instanceof SkullMeta)) return null;
        return items.identify(stack).flatMap(ref -> ref.behavior()).filter(id -> id.startsWith("spawnercraft:sc_"))
                .map(id -> byId.get(id.substring("spawnercraft:sc_".length())))
                .filter(head -> carrier(head) == stack.getType()).map(Head::mob).orElse(null);
    }

    String coreMob(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0 || (stack.getType() != Material.SPAWNER && !stack.getType().name().equals("TRIAL_SPAWNER"))
                || !stack.hasItemMeta()) return null;
        return items.identify(stack).filter(ref -> ref.behavior().filter("spawnercraft:spawner_core"::equals).isPresent())
                .map(ref -> ref.state().getOrDefault("mob", "")).orElse(null);
    }

    ItemStack create(Head head) {
        return prototypes.computeIfAbsent(head.id(), ignored -> {
            ItemStack stack = items.create("allium:" + itemId(head), 1).stack().orElseThrow();
            if (stack.getItemMeta() instanceof SkullMeta skull) {
                try {
                    if (carrier(head) == Material.PLAYER_HEAD) {
                        var profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(head.texture().getBytes(StandardCharsets.UTF_8)));
                        var textures = profile.getTextures();
                        textures.setSkin(URI.create("https://textures.minecraft.net/texture/" + head.texture()).toURL());
                        profile.setTextures(textures);
                        skull.setOwnerProfile(profile);
                        // Spigot 1.20.1 has the skull profile API but not note-block sounds on SkullMeta.
                        if (!head.sound().isEmpty()) setNoteSound(skull, NamespacedKey.fromString(head.sound()));
                    }
                } catch (java.net.MalformedURLException e) {
                    throw new IllegalArgumentException("Invalid texture for " + head.id(), e);
                }
                skull.setLore(List.of("§7SpawnerCraft ingredient", "§7Right-click to view recipes"));
                stack.setItemMeta(skull);
            }
            return stack;
        }).clone();
    }

    private static void setNoteSound(SkullMeta skull, NamespacedKey sound) {
        try {
            SkullMeta.class.getMethod("setNoteBlockSound", NamespacedKey.class).invoke(skull, sound);
        } catch (ReflectiveOperationException ignored) {
            // Optional on older server versions.
        }
    }

    ItemStack core(String mob) {
        ItemStack stack = items.create("allium:spawner_core", 1).stack().orElseThrow();
        initializeCore(stack, mob);
        return stack;
    }

    private void initializeCore(ItemStack stack, String mob) {
        if (!items.updateState(stack, Map.of("mob", mob))) throw new IllegalStateException("Could not sign spawner core");
        var meta = stack.getItemMeta();
        meta.setDisplayName("§6" + title(mob) + " Spawner Core");
        meta.setLore(List.of("§7Surround with eight heads of the same mob.", "§7Right-click to view the recipe."));
        stack.setItemMeta(meta);
    }

    List<ItemStack> roll(LivingEntity mob, Player killer, ConfigurationSection config) {
        if (killer == null || !config.getBoolean("drops.enabled", true)) return List.of();
        String mobId = mob.getType().getKey().getKey();
        var candidates = byMob.get(mobId);
        if (candidates == null || !config.getBoolean("drops.mobs." + mobId + ".enabled", true)) return List.of();
        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft("looting"));
        int looting = enchantment == null ? 0 : killer.getInventory().getItemInMainHand().getEnchantmentLevel(enchantment);
        List<ItemStack> loot = new ArrayList<>(2);
        addRoll(loot, choose(mob, candidates), looting, config);
        if (mobId.equals("wither")) {
            String bonusId = ThreadLocalRandom.current().nextBoolean() ? "wither_projectile_head" : "blue_wither_projectile_head";
            addRoll(loot, byId.get(bonusId), looting, config);
        }
        return loot;
    }

    private void addRoll(List<ItemStack> loot, Head head, int looting, ConfigurationSection config) {
        if (head == null || !config.getBoolean("drops.heads." + head.id() + ".enabled", true)) return;
        double chance = looting == 0 ? setting(config, head, "chance", head.chance())
                : setting(config, head, "looting-chance", head.looting())
                + (looting - 1) * setting(config, head, "looting-per-level", head.bonus());
        if (ThreadLocalRandom.current().nextDouble() < chance) loot.add(create(head));
    }

    private static double setting(ConfigurationSection config, Head head, String field, double fallback) {
        double value = config.getDouble("drops.heads." + head.id() + "." + field,
                config.getDouble("drops.mobs." + head.mob() + "." + field, fallback));
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
    }

    private static Head choose(LivingEntity mob, List<Head> candidates) {
        if (mob instanceof Creeper creeper) return findId(candidates, creeper.isPowered() ? "charged_creeper_head" : "creeper_head");
        if (candidates.size() == 1) return candidates.get(0);
        String mobId = mob.getType().getKey().getKey();
        if (mobId.equals("vex")) return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        if (mobId.equals("wither")) return findId(candidates, "wither_head");
        if (mobId.equals("zombie_nautilus")) return match(candidates, newVariant(mob, "temperate", "getVariant"));
        if (mobId.equals("copper_golem")) {
            String oxidation = newVariant(mob, "unaffected", "getWeatheringState");
            return findId(candidates, oxidation.equals("unaffected") ? "copper_golem_head" : oxidation + "_copper_golem_head");
        }
        if (mob instanceof Sheep sheep) {
            if ("jeb_".equals(sheep.getCustomName())) return findId(candidates, "jeb_sheep_head");
            return match(candidates, lower(sheep.getColor()));
        }
        if (mob instanceof Rabbit rabbit && "Toast".equals(rabbit.getCustomName())) return findId(candidates, "toast_rabbit_head");
        if (mob instanceof Bee bee) {
            String id = (bee.getAnger() > 0 ? "angry_" : "") + (bee.hasNectar() ? "pollinated_" : "") + "bee_head";
            return findId(candidates, id);
        }
        if (mob instanceof Goat goat) return findId(candidates, goat.isScreaming() ? "screaming_goat_head" : "goat_head");
        if (mob instanceof Villager villager) return profession(candidates, platformVariant(Villager.class, villager, "none", "getProfession"));
        if (mob instanceof ZombieVillager villager) return profession(candidates, platformVariant(ZombieVillager.class, villager, "none", "getVillagerProfession"));
        if (mob instanceof Panda panda) {
            String gene = lower(panda.getMainGene());
            String id = switch (gene) {
                case "normal" -> "panda_head";
                case "brown", "weak" -> panda.getMainGene() == panda.getHiddenGene() ? gene + "_panda_head" : "panda_head";
                default -> gene + "_panda_head";
            };
            return findId(candidates, id);
        }
        if (mob instanceof Wolf wolf) {
            String variant = platformVariant(Wolf.class, wolf, "pale");
            return findId(candidates, (wolf.isAngry() ? "angry_" : "") + variant + "_wolf_head");
        }
        if (mob instanceof TraderLlama llama) return findId(candidates, lower(llama.getColor()) + "_trader_llama_head");
        String variant = variant(mob);
        if (variant != null) {
            Head head = match(candidates, variant);
            if (head != null) return head;
            for (Head candidate : candidates) {
                if (candidate.id().startsWith(variant + "_")) return candidate;
            }
        }
        return candidates.get(0);
    }

    private static String variant(LivingEntity mob) {
        if (mob instanceof Axolotl animal) return lower(animal.getVariant());
        if (mob instanceof Cat animal) return platformVariant(Cat.class, animal, "tabby", "getCatType");
        if (mob instanceof Fox animal) return lower(animal.getFoxType());
        if (mob instanceof Frog animal) return platformVariant(Frog.class, animal, "temperate");
        if (mob instanceof Horse animal) return lower(animal.getColor());
        if (mob instanceof Llama animal) return lower(animal.getColor());
        if (mob instanceof MushroomCow animal) return lower(animal.getVariant());
        if (mob instanceof Parrot animal) return switch (animal.getVariant().name()) {
            case "RED" -> "red_blue";
            case "CYAN" -> "yellow_blue";
            default -> lower(animal.getVariant());
        };
        if (mob instanceof Rabbit animal) return switch (animal.getRabbitType()) {
            case BLACK_AND_WHITE -> "white_splotched";
            case SALT_AND_PEPPER -> "salt";
            case THE_KILLER_BUNNY -> "evil";
            default -> lower(animal.getRabbitType());
        };
        if (mob instanceof Chicken animal) return platformVariant(Chicken.class, animal, "temperate");
        if (mob instanceof Cow animal) return platformVariant(Cow.class, animal, "temperate");
        if (mob instanceof Pig animal) return platformVariant(Pig.class, animal, "temperate");
        return null;
    }

    private static String newVariant(LivingEntity mob, String fallback, String method) {
        Class<?> api = mob.getType().getEntityClass();
        return api == null ? fallback : platformVariant(api, mob, fallback, method);
    }

    private static String platformVariant(Class<?> api, LivingEntity mob, String fallback) {
        return platformVariant(api, mob, fallback, "getVariant");
    }

    private static String platformVariant(Class<?> api, LivingEntity mob, String fallback, String method) {
        try {
            Object variant = api.getMethod(method).invoke(mob);
            if (variant instanceof org.bukkit.Keyed keyed) return keyed.getKey().getKey();
            if (variant instanceof Enum<?> value) return lower(value);
        } catch (ReflectiveOperationException ignored) {
            // Older API versions do not expose these variants.
        }
        return fallback;
    }

    private static Head profession(List<Head> heads, String profession) {
        return heads.stream().filter(head -> head.variant().contains("minecraft:" + profession + "\"")).findFirst().orElse(null);
    }

    private static Head match(List<Head> candidates, String variant) {
        return candidates.stream().filter(head -> head.variant().equals(variant)).findFirst().orElse(null);
    }

    private static Head findId(List<Head> candidates, String id) {
        return candidates.stream().filter(head -> head.id().equals(id)).findFirst().orElse(null);
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }

    static String title(String mob) {
        return Arrays.stream(mob.split("_")).filter(s -> !s.isBlank())
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).collect(java.util.stream.Collectors.joining(" "));
    }
}
