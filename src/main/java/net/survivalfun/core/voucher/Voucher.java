package net.survivalfun.core.voucher;

import org.bukkit.Material;

import java.util.List;

/**
 * Voucher definition from vouchers.yml: id, material, display name, lore, commands, grants.
 */
public class Voucher {
    private final String id;
    private final Material material;
    private final String displayName;
    private final List<String> lore;
    private final boolean enchanted;
    private final List<String> commands;
    private final List<Grant> grants;

    public Voucher(String id, Material material, String displayName, List<String> lore,
                   boolean enchanted, List<String> commands, List<Grant> grants) {
        this.id = id;
        this.material = material;
        this.displayName = displayName;
        this.lore = lore;
        this.enchanted = enchanted;
        this.commands = commands;
        this.grants = grants;
    }

    public String getId() { return id; }
    public Material getMaterial() { return material; }
    public String getDisplayName() { return displayName; }
    public List<String> getLore() { return lore; }
    public boolean isEnchanted() { return enchanted; }
    public List<String> getCommands() { return commands; }
    public List<Grant> getGrants() { return grants; }

    public static class Grant {
        private final int chance;
        private final String permission;

        public Grant(int chance, String permission) {
            this.chance = chance;
            this.permission = permission;
        }

        public int getChance() { return chance; }
        public String getPermission() { return permission; }

        public static Grant parse(String grantString) {
            if (grantString == null || grantString.isEmpty()) return null;
            String[] parts = grantString.split(":", 2);
            if (parts.length != 2) return null;
            try {
                int chance = Integer.parseInt(parts[0].trim());
                String permission = parts[1].trim();
                if (permission.isEmpty()) return null;
                return new Grant(chance, permission);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
