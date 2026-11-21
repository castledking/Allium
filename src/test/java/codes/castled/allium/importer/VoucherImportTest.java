package codes.castled.allium.importer;

import codes.castled.allium.tool.definition.ToolDefinitionParser;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.powertool.PowerToolBehaviorType;
import codes.castled.allium.tool.powertool.PowerToolParameters;
import codes.castled.allium.tool.powertool.PowerToolAction;
import codes.castled.allium.tool.powertool.PowerToolBinding;
import codes.castled.allium.tool.powertool.PowerToolTrigger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VoucherImportTest {

    /** Pre-recode Allium's bundled vouchers.yml, as shipped. */
    public static final String LEGACY = """
            vouchers:
              TFly10m:
                Material: FEATHER
                DisplayName: "&6&l10m Fly Time &7(Click)"
                Lore:
                  - "&fClick to redeem &610m&f of flight"
                  - "&ftime. &6/fly &fto toggle flight."
                Enchanted: true
                Commands:
                  - "fly %player% add 10m"
                Grant: []

              RandomGlow:
                Material: GLOW_INK_SAC
                DisplayName: "&d&lRandom Glow &7(Click)"
                Lore:
                  - "&fClick to redeem a &drandom glow&f!"
                Enchanted: true
                Commands: []
                Grant:
                  - "10:essentials.glow.color.aqua"
                  - "10:essentials.glow.color.blue"
                  - "10:essentials.glow.color.green"
                  - "10:essentials.glow.color.red"
                  - "10:essentials.glow.color.yellow"
                  - "10:essentials.glow.color.gold"
                  - "10:essentials.glow.color.light_purple"
                  - "10:essentials.glow.color.dark_purple"
                  - "10:essentials.glow.color.white"
                  - "1:essentials.glow.color.black"
            """;

    static final ToolEnvironment ENV = new ToolEnvironment() {
        @Override public boolean isItemMaterial(String material) { return Material.matchMaterial(material) != null; }
        @Override public boolean isEntityType(String entityType) { return true; }
        @Override public boolean hasLangKey(String key) { return true; }
        @Override public boolean isCoreItem(String localId) { return false; }
        @Override public Optional<String> coreItemBehavior(String localId) { return Optional.empty(); }
    };

    static final List<String> ALLOWLIST = List.of("allium:", "minecraft:effect", "minecraft:particle", "minecraft:playsound",
            "minecraft:title", "minecraft:tellraw");

    public static List<String> validate(Map<String, Object> document) {
        String name = String.valueOf(document.get("id")).replace("allium:", "");
        ToolDefinitionParser.Result result = ToolDefinitionParser.parse("tools/" + name + ".yml", document,
                Map.of(PowerToolBehaviorType.ID, new PowerToolBehaviorType()), ENV);
        return result.valid() ? List.of() : result.errors();
    }

    static VoucherImport.Plan plan(String yaml, Map<String, String> existing) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return VoucherImport.plan(config, new VoucherImport.Context(Set.of("fly", "glow", "heal"), ALLOWLIST, true,
                existing::get, VoucherImportTest::validate));
    }

    @Test
    void theLegacyDefaultsBecomeTwoLoadablePowertools() throws Exception {
        VoucherImport.Plan plan = plan(LEGACY, Map.of());
        assertEquals(2, plan.count(VoucherImport.Status.WRITE), plan.toString());

        VoucherImport.Item fly = plan.items().get(0);
        assertEquals("TFly10m", fly.legacyId());
        assertEquals("voucher_tfly10m", fly.toolName());
        assertEquals(List.of(), fly.notes());
        ToolDefinitionParser.Result parsed = ToolDefinitionParser.parse("tools/voucher_tfly10m.yml", fly.document(),
                Map.of(PowerToolBehaviorType.ID, new PowerToolBehaviorType()), ENV);
        assertTrue(parsed.valid(), parsed.errors().toString());
        assertEquals("10m Fly Time (Click)", parsed.definition().name());
        assertEquals("feather", parsed.definition().item().material());
        assertEquals("&6&l10m Fly Time &7(Click)", parsed.definition().item().name());
        assertEquals(2, parsed.definition().item().lore().size());
        assertEquals(Map.of("unbreaking", 1), parsed.definition().item().enchantments());
        assertEquals(List.of("HIDE_ENCHANTS"), parsed.definition().item().hideFlags());
        PowerToolParameters parameters = parsed.definition().parameters(PowerToolParameters.class);
        assertEquals(1, parameters.uses().max());
        assertTrue(parameters.uses().consumeOnEmpty());
        assertFalse(parameters.uses().bypassable());
        assertFalse(parameters.uses().requireSingleItem());
        PowerToolBinding redeem = parameters.binding(PowerToolTrigger.RIGHT);
        assertEquals(PowerToolBinding.HAND_BOTH, redeem.hand());
        assertTrue(redeem.cancelInteraction());
        assertEquals(2, redeem.actions().size());
        assertEquals(PowerToolAction.TYPE_CONSOLE, redeem.actions().get(0).type());
        assertEquals("allium:fly {player} add 10m", redeem.actions().get(0).value());
        assertEquals("&aVoucher redeemed: &6&l10m Fly Time &7(Click)", redeem.actions().get(1).value());

        VoucherImport.Item glow = plan.items().get(1);
        assertEquals("voucher_randomglow", glow.toolName());
        PowerToolBinding glowRedeem = ToolDefinitionParser.parse("tools/voucher_randomglow.yml", glow.document(),
                Map.of(PowerToolBehaviorType.ID, new PowerToolBehaviorType()), ENV).definition()
                .parameters(PowerToolParameters.class).binding(PowerToolTrigger.RIGHT);
        PowerToolAction grant = glowRedeem.actions().get(0);
        assertEquals(PowerToolAction.TYPE_GRANT, grant.type());
        assertTrue(grant.required(), "with every colour already held the voucher must be kept");
        assertEquals("10:allium.glow.aqua 10:allium.glow.blue 10:allium.glow.green 10:allium.glow.red 10:allium.glow.yellow"
                + " 10:allium.glow.gold 10:allium.glow.light_purple 10:allium.glow.dark_purple 10:allium.glow.white 1:allium.glow.black",
                grant.value());
        assertEquals(10, glow.notes().size());
        assertTrue(glow.notes().get(0).contains("essentials.glow.color.aqua is allium.glow.aqua"), glow.notes().get(0));
    }

    @Test
    void anImportedFileIsSameNextTimeAndADifferentFileIsKept() throws Exception {
        VoucherImport.Plan first = plan(LEGACY, Map.of());
        Map<String, String> existing = new HashMap<>();
        for (VoucherImport.Item item : first.items()) {
            YamlConfiguration written = new YamlConfiguration();
            written.loadFromString(VoucherImport.yaml(item.document()));
            existing.put(item.toolName(), written.saveToString());
        }
        assertEquals(2, plan(LEGACY, existing).count(VoucherImport.Status.SAME));

        existing.put("voucher_tfly10m", "id: allium:voucher_tfly10m\nname: Mine\n");
        VoucherImport.Plan again = plan(LEGACY, existing);
        assertEquals(VoucherImport.Status.KEPT, again.items().get(0).status());
        assertEquals(VoucherImport.Status.SAME, again.items().get(1).status());
    }

    @Test
    void namesCommandsGrantsAndMaterialsThatNeedAttentionAreReported() throws Exception {
        VoucherImport.Plan plan = plan("""
                vouchers:
                  Odd:
                    Material: NOT_A_THING
                    Commands:
                      - "/crate key %player% vote"
                      - "heal %player% %rank%"
                      - "say {player}"
                    Grant:
                      - "0:never.picked"
                      - "five:bad.weight"
                      - "3:Kits.VIP"
                  odd:
                    Commands: ["heal %player%"]
                  "!!!":
                    Commands: []
                  Empty: {}
                """, Map.of());
        VoucherImport.Item odd = plan.items().get(0);
        assertEquals(VoucherImport.Status.WRITE, odd.status(), odd.notes().toString());
        String notes = String.join("\n", odd.notes());
        assertTrue(notes.contains("Material 'NOT_A_THING' is no item; it is paper"), notes);
        assertTrue(notes.contains("'crate key {player} vote' will not run until 'crate' is in powertools.console-allowlist"), notes);
        assertTrue(notes.contains("%rank%, which pre-recode Allium never filled in"), notes);
        assertTrue(notes.contains("chance 0, so it was never picked"), notes);
        assertTrue(notes.contains("'five:bad.weight' is not chance:permission"), notes);
        assertFalse(notes.contains("allium:heal"), "allium:heal is allowed: " + notes);
        assertEquals("paper", ((Map<?, ?>) odd.document().get("item")).get("material"));

        assertEquals(VoucherImport.Status.BLOCKED, plan.items().get(1).status(), "Odd and odd are the same tool");
        assertEquals(VoucherImport.Status.BLOCKED, plan.items().get(2).status());
        assertNull(plan.items().get(2).toolName());
        assertEquals(VoucherImport.Status.WRITE, plan.items().get(3).status());
        assertTrue(plan.items().get(3).notes().get(0).contains("only says it was redeemed"));
    }

    @Test
    void toolNamesAreLowerCaseWithPunctuationFolded() {
        assertEquals("voucher_tfly10m", VoucherImport.toolName("TFly10m"));
        assertEquals("voucher_vip_kit_2", VoucherImport.toolName(" VIP-Kit (2) "));
        assertNull(VoucherImport.toolName("***"));
        assertNull(VoucherImport.toolName("x".repeat(60)));
    }
}
