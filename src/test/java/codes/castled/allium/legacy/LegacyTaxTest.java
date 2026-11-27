package codes.castled.allium.legacy;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyTaxTest {
    @TempDir
    Path folder;

    @Test
    void theRateRoundsAndRespectsTheMinimum() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("percent", "5");
        c.set("minimum-tax", "1.00");
        LegacyTax.Settings tax = new LegacyTax.Settings(c);
        assertEquals(new BigDecimal("5.00"), tax.tax(new BigDecimal("100")));
        assertEquals(new BigDecimal("1.00"), tax.tax(new BigDecimal("3")), "the minimum applies");
        assertEquals(new BigDecimal("0.50"), tax.tax(new BigDecimal("0.50")), "never more than the amount");
        assertEquals("5%", tax.rate());
        assertEquals(LegacyTax.Settings.accountId("Tax"), tax.accountId());
        assertNull(LegacyTax.percent("101", false));
        assertNull(LegacyTax.percent("0", false));
        assertEquals(new BigDecimal("12.50"), LegacyTax.percent("12.5", false));
    }

    @Test
    void anImposedTaxIsRecordedMailedAndRefundedAfterConfirmation() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        LegacyConfigManager config = mock(LegacyConfigManager.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("economy.tax.percent", "5");
        when(config.raw()).thenReturn(yaml);
        LegacyMessageManager messages = mock(LegacyMessageManager.class);
        when(messages.resolve(anyString(), anyString(), any(String[].class))).thenReturn("receipt");
        when(messages.resolveColored(anyString(), anyString(), any(String[].class))).thenReturn("line");
        LegacyEconomyModule economy = new LegacyEconomyModule(plugin, config, messages);
        economy.enable();
        List<String> mail = new ArrayList<>();
        economy.tax().mail = (player, text) -> mail.add(player + ":" + text);

        UUID bobId = UUID.randomUUID();
        OfflinePlayer bob = mock(OfflinePlayer.class);
        when(bob.getUniqueId()).thenReturn(bobId);
        when(bob.getName()).thenReturn("Bob");
        when(server.getPlayerExact("Bob")).thenReturn(null);
        when(server.getOfflinePlayer("Bob")).thenReturn(bob);
        when(server.getOfflinePlayer(bobId)).thenReturn(bob);
        when(bob.hasPlayedBefore()).thenReturn(true);
        economy.adjust(bobId, "Bob", new BigDecimal("1000"));
        CommandSender staff = mock(CommandSender.class);
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(staff.getName()).thenReturn("Console");

        assertTrue(economy.tax().command(staff, new String[]{"impose", "Bob", "10"}));
        UUID taxId = economy.taxSettings().accountId();
        assertEquals(new BigDecimal("900.00"), economy.getBalance(bobId));
        assertEquals(new BigDecimal("100.00"), economy.getBalance(taxId));
        assertEquals(Collections.singletonList(bobId + ":receipt"), mail);
        assertEquals(1, economy.tax().entries().size());

        // Reloading keeps the history; a refund needs confirming.
        economy.reload();
        long id = economy.tax().entries().get(0).id;
        assertTrue(economy.tax().command(staff, new String[]{"refund", Long.toString(id), "50"}));
        assertEquals(new BigDecimal("900.00"), economy.getBalance(bobId), "nothing moves before confirming");
        assertTrue(economy.tax().command(staff, new String[]{"refund", Long.toString(id), "50", "confirm"}));
        assertEquals(new BigDecimal("950.00"), economy.getBalance(bobId));
        assertEquals(new BigDecimal("50.00"), economy.getBalance(taxId));
        assertTrue(economy.tax().command(staff, new String[]{"refund", Long.toString(id), "confirm"}));
        verify(messages).send(eq(staff), eq("economy.tax-already-refunded"), anyString(), any(String[].class));
        assertEquals(new BigDecimal("950.00"), economy.getBalance(bobId));
    }
}
