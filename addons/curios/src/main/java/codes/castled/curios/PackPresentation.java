package codes.castled.curios;

import codes.castled.allium.api.ItemIdentity;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Presentation bridge: renders SF-accurate card frames, tier badges, small-caps MiniMessage lore,
 * and textured card menus on Paper/Adventure servers, with clean legacy formatting fallback on
 * Spigot 1.20.1.
 *
 * <p>With the textured pack, frames are font-glyph panels written into the item's own lore (see
 * {@link FramePanels}), never markers rewritten on the way to the client. Every player therefore
 * sees the same framed tooltip, creative players included (a creative client sends items back as
 * it was shown them, so packet-rewritten frames have to be skipped for it).
 */
final class PackPresentation {
    private static final String BAR_CHAR = "\u2500";
    /** A 1x1 transparent tooltip style: on 1.21.2+ it hides the vanilla box behind a glyph panel. */
    private static final String PANEL_TOOLTIP_STYLE = "card";

    static final String MENU_HOME_GLYPH = "\ua508";
    static final String MENU_MERGE_GLYPH = "\ua509";
    static final String MENU_BOOST_GLYPH = "\ua50a";
    static final String MENU_TRADE_GLYPH = "\ua50b";

    private PackPresentation() {}

    static void tooltip(ItemMeta meta, String frame) {
        try {
            ItemMeta.class.getMethod("setTooltipStyle", NamespacedKey.class)
                    .invoke(meta, new NamespacedKey("curios", frame));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // Older servers/clients use the default tooltip style.
        }
    }

    static String itemModel(ItemMeta meta) {
        try {
            Object key = ItemMeta.class.getMethod("getItemModel").invoke(meta);
            return key == null ? null : key.toString();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    static Inventory inventory(InventoryHolder holder, boolean textured) {
        if (textured) {
            Inventory inv = fontInventory(holder, 9, "curios:gui", "\ue701\ue702");
            if (inv != null) return inv;
        }
        return Bukkit.createInventory(holder, 9, "Curios Equipment");
    }

    static Inventory cardMenuInventory(InventoryHolder holder, boolean textured, String glyph, String plainTitle) {
        if (textured) {
            Inventory inv = fontInventory(holder, 45, "curios:card_menus", "\ue701" + glyph);
            if (inv != null) return inv;
        }
        return Bukkit.createInventory(holder, 45, plainTitle);
    }

    private static Inventory fontInventory(InventoryHolder holder, int size, String fontKey, String glyphs) {
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
            Class<?> namedColorClass = Class.forName("net.kyori.adventure.text.format.NamedTextColor");
            Class<?> textColorClass = Class.forName("net.kyori.adventure.text.format.TextColor");
            Object font = keyClass.getMethod("key", String.class).invoke(null, fontKey);
            Object white = namedColorClass.getField("WHITE").get(null);
            Object text = componentClass.getMethod("text", String.class, textColorClass).invoke(null, glyphs, white);
            Object title = componentClass.getMethod("font", keyClass).invoke(text, font);
            title = clearShadow(title);
            return (Inventory) Bukkit.class.getMethod("createInventory", InventoryHolder.class, int.class, componentClass)
                    .invoke(null, holder, size, title);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /**
     * Applies full SF trading card styling (ornate card_frame panel, tier badge, small-caps lore,
     * deterministic starting boosts, quality band, tier pips, and 5 bonus slots).
     */
    static boolean applyCardPresentation(ItemStack stack, Catalog.Entry entry, ItemIdentity identity,
                                         CardRules rules, boolean textured) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        CardRules.Tier tier = CardRules.Tier.parse(entry.legacyTier());
        int level = CardRules.parseIntSafe(identity.state().get("level"), 0);
        double xp = parseDoubleSafe(identity.state().get("xp"), 0.0);
        int quality = Math.max(1, Math.min(100, CardRules.parseIntSafe(identity.state().get("quality"), 100)));
        String frame = identity.state().getOrDefault("frame", tier.name().toLowerCase(Locale.ROOT));
        List<String> signatures = CardRules.parseSignatures(identity.state().get("signatures"));
        List<CardRules.BonusSlotData> bonuses = CardRules.parseBonuses(identity.state().get("bonuses"));
        List<CardRules.StartingValue> starting = rules.startingValues(entry.legacyCard(), tier, quality, level, signatures);
        CardRules.QualityBand band = rules.bandFor(quality);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        if (applyAdventureCard(stack, meta, entry, tier, level, xp, quality, band, frame, starting, bonuses, rules, textured)) {
            tagJadeModName(stack, CARD_MOD_ID);
            return true;
        }

        // Legacy Spigot 1.20.1 fallback: readable small-caps SF lore with matching colors
        String mobName = CardRules.titleCase(entry.legacyCard());
        String mobCol = CardRules.legacyColourCode(rules.mobColour(entry.legacyCard()));
        meta.setDisplayName(mobCol + CardRules.smallCaps(mobName + " Trading Card"));
        List<String> lore = buildLegacyCardLore(entry, tier, level, xp, quality, band, starting, bonuses, rules);
        meta.setLore(lore);
        tooltip(meta, frame);
        if (!stack.setItemMeta(meta)) return false;
        tagJadeModName(stack, CARD_MOD_ID);
        return true;
    }

    /**
     * The id Jade reads to name a card's "mod" (the blue line it adds under the name in creative
     * and at the end elsewhere). Its namespace's name comes from the pack's
     * {@code jade.modName.trading_cards} lang key, so cards read "Trading Cards" instead of
     * "Minecraft". Clients without Jade ignore both.
     */
    static final String CARD_MOD_ID = "trading_cards:card";

    /**
     * Adds Jade's {@code $jade:stack} id at the root of the item's data (its custom data on
     * 1.20.5+ clients). Bukkit has no API for a root tag, so the item is rebuilt from its own tag
     * text with the entry added, and kept only if the rebuilt item still has the same data keys
     * and lore. Anything unexpected leaves the item as it was.
     */
    static void tagJadeModName(ItemStack stack, String id) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return;
            String tag = meta.getAsString();
            if (tag == null || tag.contains("$jade:stack")) return;
            String entry = "\"$jade:stack\":{id:\"" + id + "\"}";
            String patched;
            if (tag.startsWith("{")) {
                // Item NBT (before 1.20.5): the entry is a root tag.
                patched = tag.equals("{}") ? "{" + entry + "}" : "{" + entry + "," + tag.substring(1);
            } else {
                // Data components (1.20.5+): the entry goes inside custom_data, beside Bukkit's data.
                int at = tag.indexOf("custom_data={");
                if (at < 0) return;
                at += "custom_data={".length();
                patched = tag.substring(0, at) + entry + (tag.charAt(at) == '}' ? "" : ",") + tag.substring(at);
            }
            ItemStack rebuilt = Bukkit.getItemFactory().createItemStack(stack.getType().getKey() + patched);
            ItemMeta rebuiltMeta = rebuilt.getItemMeta();
            if (rebuiltMeta == null
                    || !rebuiltMeta.getPersistentDataContainer().getKeys().equals(meta.getPersistentDataContainer().getKeys())
                    || !Objects.equals(rebuiltMeta.getLore(), meta.getLore())
                    || !rebuiltMeta.getAsString().contains("$jade:stack")) {
                return;
            }
            stack.setItemMeta(rebuiltMeta);
        } catch (RuntimeException | LinkageError unavailable) {
            // No raw item text on this server: Jade keeps showing the item's own namespace.
        }
    }

    private static boolean applyAdventureCard(ItemStack stack, ItemMeta meta, Catalog.Entry entry,
                                              CardRules.Tier tier, int level, double xp, int quality,
                                              CardRules.QualityBand band, String frame,
                                              List<CardRules.StartingValue> starting,
                                              List<CardRules.BonusSlotData> bonuses,
                                              CardRules rules, boolean textured) {
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Class<?> mmClass = Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            Object mm = mmClass.getMethod("miniMessage").invoke(null);
            Method deserialize = deserializer(mmClass);

            String mobName = CardRules.titleCase(entry.legacyCard());
            String mobCol = rules.mobColour(entry.legacyCard());
            String sep = rules.separator.isEmpty() ? "" : "<dark_gray><strikethrough>" + rules.separator + "</strikethrough></dark_gray>";

            Object title = deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps("<" + mobCol + ">" + mobName + " Trading Card</" + mobCol + ">"));
            List<Object> bodyLines = new ArrayList<>();
            bodyLines.add(title);
            if (textured) {
                // SF's hyronic_ae_configuration tier label, white so the glyph keeps its own colours.
                bodyLines.add(glyphComponent(tier.badgeGlyph, FramePanels.CARD_FONT));
            } else {
                bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps("<" + tier.colourName + ">" + CardRules.titleCase(tier.name()) + "</" + tier.colourName + ">")));
            }
            if (!sep.isEmpty()) bodyLines.add(deserialize.invoke(mm, "<!italic>" + sep));
            bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps(
                    "<gray>Level:</gray> <dark_gray>[</dark_gray><white>" + level + "</white><dark_gray>]</dark_gray>")));
            String progressMini = progressLineMini(level, xp, entry.maxLevel(), rules);
            if (!progressMini.isEmpty()) {
                bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps(progressMini)));
            }
            if (!sep.isEmpty()) bodyLines.add(deserialize.invoke(mm, "<!italic>" + sep));
            bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps("<gray>While equipped in </gray><aqua>/relics</aqua><gray>:</gray>")));
            if (starting.isEmpty()) {
                bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps("  <dark_gray>None</dark_gray>")));
            } else {
                for (CardRules.StartingValue sv : starting) {
                    CardRules.BonusDef def = CardRules.BONUSES.get(sv.boostId());
                    String label = def != null ? def.displayMini() : "<white>" + CardRules.titleCase(sv.boostId());
                    bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps(
                            "  " + label + " <white>+" + CardRules.formatNumber(sv.base()) + "</white>" + sv.pointsSuffixMini())));
                }
            }
            if (!sep.isEmpty()) bodyLines.add(deserialize.invoke(mm, "<!italic>" + sep));
            bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps(
                    "<gray>Quality:</gray> <" + band.colour() + ">" + band.label() + "</" + band.colour()
                            + "> <dark_gray>[" + quality + "%]</dark_gray>")));
            if (!sep.isEmpty()) bodyLines.add(deserialize.invoke(mm, "<!italic>" + sep));
            bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps(
                    "<gray>Tier:</gray> " + pipRowMini(tier) + " <" + tier.colourName + ">["
                            + (tier.ordinal() + 1) + "/" + CardRules.Tier.values().length + "]</" + tier.colourName + ">")));

            int unlocked = Math.min(CardRules.BONUS_SLOT_COUNT, tier.ordinal() + 1);
            for (int i = 0; i < CardRules.BONUS_SLOT_COUNT; i++) {
                CardRules.BonusSlotData slot = i < bonuses.size() ? bonuses.get(i) : CardRules.BonusSlotData.EMPTY;
                if (i >= unlocked) {
                    bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps("<gray>Bonus:</gray> <#F15A45>Locked \uD83D\uDD12</#F15A45>")));
                } else if (!slot.isEmpty()) {
                    CardRules.BonusDef def = CardRules.BONUSES.get(slot.boostId());
                    String line = def != null ? def.lineMini(slot.strength()) : "<white>" + CardRules.titleCase(slot.boostId());
                    bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps(line)));
                } else {
                    bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps("<gray>Bonus:</gray> <#777777>Empty!</#777777>")));
                }
            }
            if (!sep.isEmpty()) bodyLines.add(deserialize.invoke(mm, "<!italic>" + sep));
            bodyLines.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps("<dark_gray>Right-Click to Open Menu</dark_gray>")));

            FramePanels.Panel panel = textured ? FramePanels.bundled().panel(frame, tier.name(), bodyLines.size(),
                    widest(bodyLines), FramePanels.CARD_WIDTH) : null;
            if (panel != null) {
                // The panel's title is its first framed line, so the name line is left blank.
                applyPanel(meta, panel, bodyLines);
            } else {
                ItemMeta.class.getMethod("displayName", componentClass).invoke(meta, title);
                ItemMeta.class.getMethod("lore", List.class).invoke(meta, bodyLines.subList(1, bodyLines.size()));
                tooltip(meta, frame);
            }
            if (!stack.setItemMeta(meta)) return false;
            if (panel != null) {
                applyPaperBlankItemName(stack);
            }
            return true;
        } catch (Throwable unavailable) {
            reportFallback("card", unavailable);
            return false;
        }
    }

    private static final Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final boolean ADVENTURE = present("net.kyori.adventure.text.Component")
            && present("net.kyori.adventure.text.minimessage.MiniMessage");

    private static boolean present(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Logs once why a component path fell back to legacy text, unless Adventure is absent (Spigot),
     * where the fallback is the intended path.
     */
    private static void reportFallback(String what, Throwable cause) {
        Throwable root = cause instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                ? ite.getCause() : cause;
        if (!ADVENTURE) return;
        if (REPORTED.add(what + ":" + root.getClass().getName())) {
            Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                    "[Curios] Drawing a " + what + " with components failed; using plain text instead", root);
        }
    }

    /**
     * {@code MiniMessage.deserialize(String)}. Adventure 4.14 (Paper 1.20.1) declares only the
     * erased {@code deserialize(Object)} it inherits from its serializer interface.
     */
    private static Method deserializer(Class<?> miniMessage) throws NoSuchMethodException {
        try {
            return miniMessage.getMethod("deserialize", String.class);
        } catch (NoSuchMethodException olderAdventure) {
            return miniMessage.getMethod("deserialize", Object.class);
        }
    }

    private static void applyPaperBlankItemName(ItemStack stack) {
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Class<?> dctClass = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            Class<?> valuedClass = Class.forName("io.papermc.paper.datacomponent.DataComponentType$Valued");
            Object itemNameType = dctClass.getField("ITEM_NAME").get(null);
            Object blank = componentClass.getMethod("text", String.class).invoke(null, " ");
            ItemStack.class.getMethod("setData", valuedClass, Object.class).invoke(stack, itemNameType, blank);
        } catch (Throwable ignored) {
            // Older Paper versions without DataComponentTypes still use displayName(" ").
        }
    }

    /**
     * Writes {@code lines} (title first) into {@code meta} inside {@code panel}: a header line, every
     * line behind its own strip, and the panel's last line. The name is blanked and the tooltip style
     * set transparent, so only the panel's art frames the text.
     */
    private static void applyPanel(ItemMeta meta, FramePanels.Panel panel, List<Object> lines)
            throws ReflectiveOperationException {
        Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
        Method append = componentClass.getMethod("append", componentClass);
        Method empty = componentClass.getMethod("empty");
        List<Object> lore = new ArrayList<>(lines.size() + 3);
        lore.add(glyphComponent(panel.header(), panel.font()));
        for (int i = 0; i < lines.size(); i++) {
            // Siblings under an unstyled parent: appended to the strip, the text would inherit the
            // panel font, which has no letters, and draw as boxes.
            Object row = append.invoke(empty.invoke(null), glyphComponent(panel.prefixes().get(i), panel.font()));
            lore.add(append.invoke(row, lines.get(i)));
        }
        lore.add(glyphComponent(panel.last(), panel.font()));
        if (panel.blankAfter()) lore.add(componentClass.getMethod("empty").invoke(null));
        Object blankName = componentClass.getMethod("text", String.class).invoke(null, " ");
        ItemMeta.class.getMethod("displayName", componentClass).invoke(meta, blankName);
        ItemMeta.class.getMethod("lore", List.class).invoke(meta, lore);
        tooltip(meta, PANEL_TOOLTIP_STYLE);
    }

    /** The widest of {@code lines} (Adventure components), in pixels, as the client draws them. */
    private static int widest(List<Object> lines) throws ReflectiveOperationException {
        Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
        Class<?> plainClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
        Object plain = plainClass.getMethod("plainText").invoke(null);
        Method serialize = plainClass.getMethod("serialize", componentClass);
        int widest = 0;
        for (Object line : lines) widest = Math.max(widest, TextWidth.of((String) serialize.invoke(plain, line)));
        return widest;
    }

    private static Object glyphComponent(String text, String fontKey) throws ReflectiveOperationException {
        Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
        Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
        Class<?> namedColorClass = Class.forName("net.kyori.adventure.text.format.NamedTextColor");
        Class<?> textColorClass = Class.forName("net.kyori.adventure.text.format.TextColor");
        Class<?> decoClass = Class.forName("net.kyori.adventure.text.format.TextDecoration");
        Object font = keyClass.getMethod("key", String.class).invoke(null, fontKey);
        Object white = namedColorClass.getField("WHITE").get(null);
        Object italic = decoClass.getField("ITALIC").get(null);
        Object bold = decoClass.getField("BOLD").get(null);
        Object comp = componentClass.getMethod("text", String.class, textColorClass).invoke(null, text, white);
        comp = componentClass.getMethod("font", keyClass).invoke(comp, font);
        comp = componentClass.getMethod("decoration", decoClass, boolean.class).invoke(comp, italic, false);
        comp = componentClass.getMethod("decoration", decoClass, boolean.class).invoke(comp, bold, false);
        return clearShadow(comp);
    }

    private static Object clearShadow(Object component) {
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Class<?> shadowClass = Class.forName("net.kyori.adventure.text.format.ShadowColor");
            Object none = shadowClass.getMethod("none").invoke(null);
            return componentClass.getMethod("shadowColor", shadowClass).invoke(component, none);
        } catch (Throwable ignored) {
            return component;
        }
    }

    private static List<String> buildLegacyCardLore(Catalog.Entry entry, CardRules.Tier tier, int level, double xp,
                                                    int quality, CardRules.QualityBand band,
                                                    List<CardRules.StartingValue> starting,
                                                    List<CardRules.BonusSlotData> bonuses, CardRules rules) {
        List<String> out = new ArrayList<>();
        String sep = rules.separator.isEmpty() ? "" : "§8§m" + rules.separator;
        out.add(tier.legacyColour + CardRules.smallCaps(CardRules.titleCase(tier.name())));
        if (!sep.isEmpty()) out.add(sep);
        out.add("§7" + CardRules.smallCaps("Level:") + " §8[§f" + level + "§8]");
        String progress = progressLineLegacy(level, xp, entry.maxLevel(), rules);
        if (!progress.isEmpty()) out.add(progress);
        if (!sep.isEmpty()) out.add(sep);
        out.add("§7" + CardRules.smallCaps("While equipped in ") + "§b/relics§7:");
        if (starting.isEmpty()) {
            out.add("  §8" + CardRules.smallCaps("None"));
        } else {
            for (CardRules.StartingValue sv : starting) {
                CardRules.BonusDef def = CardRules.BONUSES.get(sv.boostId());
                String label = def != null ? def.legacyColour() + def.plainName() : "§f" + CardRules.titleCase(sv.boostId());
                out.add("  " + CardRules.smallCaps(label + " §f+" + CardRules.formatNumber(sv.base())) + sv.pointsSuffixLegacy());
            }
        }
        if (!sep.isEmpty()) out.add(sep);
        out.add("§7" + CardRules.smallCaps("Quality:") + " " + CardRules.legacyColourCode(band.colour())
                + CardRules.smallCaps(band.label()) + " §8[" + quality + "%]");
        if (!sep.isEmpty()) out.add(sep);
        out.add("§7" + CardRules.smallCaps("Tier:") + " " + pipRowLegacy(tier) + " " + tier.legacyColour
                + "[" + (tier.ordinal() + 1) + "/" + CardRules.Tier.values().length + "]");

        int unlocked = Math.min(CardRules.BONUS_SLOT_COUNT, tier.ordinal() + 1);
        for (int i = 0; i < CardRules.BONUS_SLOT_COUNT; i++) {
            CardRules.BonusSlotData slot = i < bonuses.size() ? bonuses.get(i) : CardRules.BonusSlotData.EMPTY;
            if (i >= unlocked) {
                out.add("§7" + CardRules.smallCaps("Bonus:") + " §c" + CardRules.smallCaps("Locked \uD83D\uDD12"));
            } else if (!slot.isEmpty()) {
                CardRules.BonusDef def = CardRules.BONUSES.get(slot.boostId());
                out.add(CardRules.smallCaps(def != null ? def.lineLegacy(slot.strength()) : "§f" + CardRules.titleCase(slot.boostId())));
            } else {
                out.add("§7" + CardRules.smallCaps("Bonus:") + " §8" + CardRules.smallCaps("Empty!"));
            }
        }
        if (!sep.isEmpty()) out.add(sep);
        out.add("§8" + CardRules.smallCaps("Right-Click to Open Menu"));
        return out;
    }

    static String strengthBarMini(int strength, int width) {
        double ratio = Math.max(0, Math.min(100, strength)) / 100.0;
        int filled = Math.max(1, (int) Math.floor(width * ratio));
        return barMini(filled, width, ratio) + " <gray>" + strength + "%</gray>";
    }

    private static String progressLineMini(int level, double xp, int maxLevel, CardRules rules) {
        if (level >= maxLevel) {
            return barMini(rules.xpBarWidth, rules.xpBarWidth, 1.0) + " <green>Max Level</green>";
        }
        double needed = rules.xpForLevel(level);
        if (needed <= 0) return "";
        double ratio = xp <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, xp / needed));
        int filled = (int) Math.round(rules.xpBarWidth * ratio);
        return barMini(filled, rules.xpBarWidth, ratio) + " <gray>" + (long) xp + "</gray><dark_gray>/" + (long) needed + " XP</dark_gray>";
    }

    private static String progressLineLegacy(int level, double xp, int maxLevel, CardRules rules) {
        if (level >= maxLevel) {
            return barLegacy(rules.xpBarWidth, rules.xpBarWidth, 1.0) + " §a" + CardRules.smallCaps("Max Level");
        }
        double needed = rules.xpForLevel(level);
        if (needed <= 0) return "";
        double ratio = xp <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, xp / needed));
        int filled = (int) Math.round(rules.xpBarWidth * ratio);
        return barLegacy(filled, rules.xpBarWidth, ratio) + " §7" + (long) xp + "§8/" + (long) needed + " XP";
    }

    private static String barMini(int filled, int width, double ratio) {
        int f = Math.max(0, Math.min(width, filled));
        String colour = ratio >= 0.95 ? "green" : ratio >= 0.8 ? "dark_green"
                : ratio >= 0.6 ? "yellow" : ratio >= 0.4 ? "gold"
                : ratio >= 0.2 ? "red" : "dark_red";
        return "<" + colour + "><strikethrough>" + BAR_CHAR.repeat(f) + "</strikethrough></" + colour + ">"
                + "<dark_gray><strikethrough>" + BAR_CHAR.repeat(width - f) + "</strikethrough></dark_gray>";
    }

    private static String barLegacy(int filled, int width, double ratio) {
        int f = Math.max(0, Math.min(width, filled));
        String colour = ratio >= 0.95 ? "§a" : ratio >= 0.8 ? "§2"
                : ratio >= 0.6 ? "§e" : ratio >= 0.4 ? "§6"
                : ratio >= 0.2 ? "§c" : "§4";
        return colour + "§m" + BAR_CHAR.repeat(f) + "§8§m" + BAR_CHAR.repeat(width - f) + "§r";
    }

    private static String pipRowMini(CardRules.Tier tier) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < CardRules.Tier.values().length; i++) {
            row.append(i <= tier.ordinal() ? "<green>◆</green>" : "<#777777>◇</#777777>");
        }
        return row.toString();
    }

    private static String pipRowLegacy(CardRules.Tier tier) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < CardRules.Tier.values().length; i++) {
            row.append(i <= tier.ordinal() ? "§a◆" : "§8◇");
        }
        return row.toString();
    }

    /**
     * Decorates a GUI button item with small-caps MiniMessage text (or clean legacy fallback). With
     * the textured pack, the title and lore are drawn inside {@code frame}'s panel, as legacy's
     * {@code [frame:...]} buttons were for survival players, but written into the item itself.
     */
    static ItemStack decorateButton(ItemStack item, String titleMini, List<String> loreMini, String frame,
                                    boolean textured) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        if (frame != null && !frame.isBlank()) tooltip(meta, frame);
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Class<?> mmClass = Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            Object mm = mmClass.getMethod("miniMessage").invoke(null);
            Method deserialize = deserializer(mmClass);
            Object nameComp = deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps(titleMini));
            List<Object> loreComps = new ArrayList<>(loreMini.size());
            for (String line : loreMini) {
                loreComps.add(deserialize.invoke(mm, "<!italic>" + CardRules.smallCaps(line)));
            }
            List<Object> lines = new ArrayList<>(loreComps.size() + 1);
            lines.add(nameComp);
            lines.addAll(loreComps);
            FramePanels.Panel panel = textured && frame != null
                    ? FramePanels.bundled().panel(frame, "simple", lines.size(), widest(lines), 0) : null;
            if (panel != null) {
                applyPanel(meta, panel, lines);
                item.setItemMeta(meta);
                applyPaperBlankItemName(item);
                return item;
            }
            ItemMeta.class.getMethod("displayName", componentClass).invoke(meta, nameComp);
            ItemMeta.class.getMethod("lore", List.class).invoke(meta, loreComps);
            item.setItemMeta(meta);
            return item;
        } catch (Throwable unavailable) {
            reportFallback("menu button", unavailable);
            meta.setDisplayName(miniToLegacy(CardRules.smallCaps(titleMini)));
            List<String> legacyLore = new ArrayList<>(loreMini.size());
            for (String line : loreMini) legacyLore.add(miniToLegacy(CardRules.smallCaps(line)));
            meta.setLore(legacyLore);
            item.setItemMeta(meta);
            return item;
        }
    }

    static String miniToLegacy(String mini) {
        if (mini == null) return "";
        return mini
                .replace("<dark_gray>", "§8").replace("</dark_gray>", "")
                .replace("<gray>", "§7").replace("</gray>", "")
                .replace("<white>", "§f").replace("</white>", "")
                .replace("<green>", "§a").replace("</green>", "")
                .replace("<dark_green>", "§2").replace("</dark_green>", "")
                .replace("<aqua>", "§b").replace("</aqua>", "")
                .replace("<dark_aqua>", "§3").replace("</dark_aqua>", "")
                .replace("<yellow>", "§e").replace("</yellow>", "")
                .replace("<gold>", "§6").replace("</gold>", "")
                .replace("<red>", "§c").replace("</red>", "")
                .replace("<dark_red>", "§4").replace("</dark_red>", "")
                .replace("<light_purple>", "§d").replace("</light_purple>", "")
                .replace("<dark_purple>", "§5").replace("</dark_purple>", "")
                .replace("<strikethrough>", "§m").replace("</strikethrough>", "§r")
                .replaceAll("<#[0-9A-Fa-f]{6}>", "§7").replaceAll("</#[0-9A-Fa-f]{6}>", "")
                .replaceAll("</?[a-z_]+>", "");
    }

    private static double parseDoubleSafe(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
