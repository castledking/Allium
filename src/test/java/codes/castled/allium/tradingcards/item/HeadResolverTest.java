package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStreamReader;

class HeadResolverTest {
    @Test
    void foxAndAllayRewardsDoNotDependOnBeingEnabledAsSpawnerHeadDrops() {
        for (EntityType mob : new EntityType[]{EntityType.FOX, EntityType.ALLAY}) {
            var head = HeadResolver.headDataFor(mob);
            assertNotNull(head, mob + " card can be quoted but cannot build its reward");
            assertFalse(head.sound().isBlank());
            String skin = new String(Base64.getDecoder().decode(head.texture()),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(skin.contains("textures.minecraft.net/texture/"));
        }
    }

    @Test
    void everyShippedCardCanResolveItsHeadReward() throws Exception {
        try (var stream = getClass().getResourceAsStream("/tradingcards/cards.yml")) {
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream,
                java.nio.charset.StandardCharsets.UTF_8));
            var cards = yaml.getConfigurationSection("cards");
            for (String id : cards.getKeys(false)) {
                var mob = EntityType.valueOf(cards.getString(id + ".mob"));
                assertTrue(HeadResolver.hasDefaultHead(mob), id + " has no trade reward");
                assertTrue(new CardHeadItemResolver(null).exists(
                    codes.castled.allium.item.ItemRef.parse("cardhead:" + id)));
            }
        }
        assertFalse(HeadResolver.hasDefaultHead(EntityType.ARMOR_STAND));
    }

    @Test
    void bundledHeadsRetainTheirDatapackMobIdentityEvenWhenSoundsAreShared() throws Exception {
        try (var stream = getClass().getResourceAsStream("/spawnercraft/more-mob-heads-names.txt")) {
            String names = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (EntityType mob : EntityType.values()) {
                String name = HeadResolver.defaultNameFor(mob);
                if (name != null) assertTrue(names.contains(name + "=" + mob.name().toLowerCase(java.util.Locale.ROOT)), name);
            }
        }
    }
}
