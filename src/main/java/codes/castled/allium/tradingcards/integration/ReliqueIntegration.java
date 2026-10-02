package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.item.TradingCardData;
import com.github.darksoulq.relique.core.RelicRegistries;
import com.github.darksoulq.relique.core.RelicValidators;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Installs the {@code card} slot into Relique.
 *
 * <p>Relique's GUI is generated entirely from its slot registry, so the slot
 * appears by being declared — not by editing the menu. Three pieces are needed
 * and each has its own failure mode:
 *
 * <ul>
 *   <li>The slot definition, written into Relique's own data folder. Declared as
 *       a file rather than through {@code RelicManager.registerSlot} because that
 *       method does the {@code SLOTS.put} and nothing else: no tag, no attribute,
 *       so the slot would validate against nothing and never appear.
 *   <li>A {@code RelicValidator}, so only trading cards are accepted. Validators
 *       OR together and the first match wins.
 *   <li>The slot listed for {@code minecraft:player}, or players never get it.
 * </ul>
 *
 * <p>Touches Relique classes directly, so only constructed when Relique is
 * enabled.
 */
public final class ReliqueIntegration {

    private static final String SLOT_ID = TradingCardsBranding.RELIQUE_SLOT;
    /**
     * The validator's registry path, not a full key.
     *
     * <p>Relique creates its validator registry with its own namespace —
     * {@code DeferredRegistry.create(VALIDATORS, "relique")} — so
     * {@code register(path, ...)} builds {@code Key("relique", path)}. Passing a
     * namespaced id produced {@code Key[relique:allium:trading_card]}, which
     * Adventure rejects for having two colons, and the whole integration
     * silently disabled itself with a warning nobody was reading.
     *
     * <p>So this is {@code trading_card} here, and {@code relique:trading_card} in
     * the slot JSON, which Relique parses with {@code Key.key(...)} as a full key.
     */
    private static final String VALIDATOR_PATH = "trading_card";

    private ReliqueIntegration() {}

    /**
     * Writes the slot definition and merges it into the player entity file.
     *
     * <p>Idempotent: re-running merges rather than duplicating, so a reload
     * cannot produce two card slots.
     *
     * @return true when the slot is installed
     */
    public static boolean install(Plugin plugin, Logger logger) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Relique")) {
            return false;
        }
        try {
            File relicFolder = new File(Bukkit.getPluginManager().getPlugin("Relique")
                .getDataFolder(), "relic");
            File alliumFolder = new File(relicFolder, "allium");
            File slotsFolder = new File(alliumFolder, "slots");
            File entitiesFolder = new File(alliumFolder, "entities");
            if (!slotsFolder.exists() && !slotsFolder.mkdirs()) {
                logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                    + "] Could not create " + slotsFolder + "; the /reliques card slot is not installed");
                return false;
            }
            if (!entitiesFolder.exists() && !entitiesFolder.mkdirs()) {
                logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                    + "] Could not create " + entitiesFolder + "; the card slot is not granted to players");
                return false;
            }

            writeSlotDefinition(plugin, logger, new File(slotsFolder, SLOT_ID + ".json"));
            ensurePlayerHasSlot(new File(entitiesFolder, "player.json"));
            registerValidator(logger);
            return true;
        } catch (Throwable t) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Could not install the Relique card slot: " + t);
            return false;
        }
    }

    /**
     * Copies the shipped slot definition into Relique's data folder, without
     * overwriting an existing one.
     *
     * <p>Not overwritten on purpose: an operator who has edited the size or the
     * icon has made a deliberate change, and Allium re-asserting the file on
     * every boot would silently undo it.
     */
    private static void writeSlotDefinition(Plugin plugin, Logger logger, File target) {
        if (target.exists()) {
            return;
        }
        String resource = "tradingcards/relic/allium/slots/" + SLOT_ID + ".json";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                    + "] Missing bundled resource " + resource);
                return;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Installed the Relique card slot");
        } catch (Exception e) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Could not write the card slot definition: " + e.getMessage());
        }
    }

    /**
     * Adds the card slot to the player entity file, if it is not listed.
     *
     * <p>The file is read as text rather than parsed and re-serialised, because
     * rewriting it through a YAML round-trip would discard the operator's
     * comments and key order.
     */
    private static void ensurePlayerHasSlot(File playerFile) {
        try {
            if (!playerFile.exists()) {
                Files.writeString(playerFile.toPath(),
                    "{\n  \"entities\": [\n    \"minecraft:player\"\n  ],\n"
                        + "  \"slots\": [\n    \"" + SLOT_ID + "\"\n  ]\n}\n");
                return;
            }
            String contents = Files.readString(playerFile.toPath());
            if (contents.contains("\"" + SLOT_ID + "\"")) {
                return;
            }
            // Append to the existing slots array, tolerating either layout.
            int slotsKey = contents.indexOf("\"slots\"");
            if (slotsKey < 0) {
                Files.writeString(playerFile.toPath(), contents
                    + ",\n  \"slots\": [\n    \"" + SLOT_ID + "\"\n  ]\n");
                return;
            }
            int open = contents.indexOf('[', slotsKey);
            int close = contents.indexOf(']', open);
            if (open < 0 || close < 0) {
                return;
            }
            String existing = contents.substring(open + 1, close).trim();
            String merged = existing.isEmpty()
                ? "    \"" + SLOT_ID + "\""
                : existing + ",\n    \"" + SLOT_ID + "\"";
            Files.writeString(playerFile.toPath(),
                contents.substring(0, open + 1) + "\n" + merged + "\n  "
                    + contents.substring(close));
        } catch (Exception e) {
            // A player file Allium cannot parse means the slot is simply not
            // granted; the operator can add it by hand.
        }
    }

    /**
     * Registers the validator that makes the slot accept trading cards.
     *
     * <p>Registered under Relique's namespace, so it matches the
     * {@code relique:trading_card} key in the slot JSON.
     */
    private static void registerValidator(Logger logger) {
        try {
            RelicValidators.VALIDATORS.register(VALIDATOR_PATH,
                id -> (slotId, item, entity) -> TradingCardData.isCard(item));
            RelicRegistries.VALIDATORS.getClass();   // touch to fail fast on an API change
        } catch (Throwable t) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Relique present but its validator API does not match: " + t);
        }
    }

    /** True when the slot is actually usable, for the command's diagnostics. */
    public static boolean slotInstalled() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Relique")) {
            return false;
        }
        try {
            return com.github.darksoulq.relique.core.RelicManager.getSlot(SLOT_ID) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The validator's view of an item, for tests and diagnostics. */
    public static boolean accepts(ItemStack item) {
        return TradingCardData.isCard(item);
    }
}
