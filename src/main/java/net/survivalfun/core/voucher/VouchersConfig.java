package net.survivalfun.core.voucher;

import net.survivalfun.core.PluginStart;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads vouchers from plugins/vouchers.yml (default from resources).
 */
public class VouchersConfig {

    private final PluginStart plugin;
    private final File file;
    private final Map<String, Voucher> vouchers = new HashMap<>();

    public VouchersConfig(PluginStart plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "vouchers.yml");
        reloadConfig();
    }

    public void reloadConfig() {
        if (!file.exists()) {
            plugin.saveResource("vouchers.yml", false);
        }
        vouchers.clear();
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("vouchers");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            try {
                Voucher v = loadVoucher(config, id);
                if (v != null) vouchers.put(id.toLowerCase(java.util.Locale.ENGLISH), v);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load voucher " + id + ": " + e.getMessage());
            }
        }
    }

    private Voucher loadVoucher(FileConfiguration config, String id) {
        String path = "vouchers." + id;
        String materialName = config.getString(path + ".Material", "PAPER");
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase(java.util.Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid material for voucher " + id + ": " + materialName);
            material = Material.PAPER;
        }
        String displayName = config.getString(path + ".DisplayName", "&fVoucher");
        List<String> lore = config.getStringList(path + ".Lore");
        boolean enchanted = config.getBoolean(path + ".Enchanted", false);
        List<String> commands = config.getStringList(path + ".Commands");
        List<String> grantStrings = config.getStringList(path + ".Grant");
        List<Voucher.Grant> grants = new ArrayList<>();
        if (grantStrings != null) {
            for (String s : grantStrings) {
                Voucher.Grant g = Voucher.Grant.parse(s);
                if (g != null) grants.add(g);
            }
        }
        return new Voucher(
            id,
            material,
            displayName,
            lore != null ? lore : Collections.emptyList(),
            enchanted,
            commands != null ? commands : Collections.emptyList(),
            grants
        );
    }

    public Voucher getVoucher(String id) {
        return id == null ? null : vouchers.get(id.toLowerCase(java.util.Locale.ENGLISH));
    }

    public Set<String> getVoucherIds() {
        return Collections.unmodifiableSet(vouchers.keySet());
    }
}
