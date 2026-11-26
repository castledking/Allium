package codes.castled.menus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

record ShopCatalog(String title, int rows, List<Category> categories, String auctionCommand) {
    static final Pattern POTION = Pattern.compile("minecraft:potion_contents=\\{potion:\"minecraft:([a-z_]+)\"}");

    record Category(String title, String displayName, String icon, String texture, int slot, int pages,
                    List<String> lore, List<Product> products) {}
    record Product(String id, String reference, String itemArgument, int slot, int page, double buy, Price sellPrice, boolean sell) {
        String displayName() {
            String item = reference.substring(reference.lastIndexOf(':') + 1);
            var potion = POTION.matcher(itemArgument);
            if (!potion.find()) return titleCase(item.replace(';', '_'));
            String effect = potion.group(1).replaceFirst("^(long_|strong_)", "");
            String carrier = item.equals("tipped_arrow") ? "Arrow" : titleCase(item);
            return carrier + " of " + titleCase(effect);
        }
    }

    private static String titleCase(String key) {
        return java.util.Arrays.stream(key.split("[_/-]"))
                .filter(word -> !word.isEmpty())
                .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1))
                .collect(Collectors.joining(" "));
    }
    record Price(long min, long max, long interval) {
        double amount(String id, long nowSeconds) {
            if (min == max) return min / 100.0;
            long period = nowSeconds / interval;
            return (min + new java.util.Random(period * 31 + id.hashCode()).nextLong(max - min + 1)) / 100.0;
        }
    }

    static ShopCatalog load(Path file) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        int rows = yaml.getInt("rows", 6);
        if (rows < 3 || rows > 6) throw invalid("rows must be 3..6");
        String auction = yaml.getString("auction-command", "auction").strip();
        if (!auction.isEmpty() && !auction.matches("[a-z0-9_:-]+(?: [a-z0-9_:-]+)*")) throw invalid("Invalid auction-command");
        var categorySection = yaml.getConfigurationSection("categories");
        List<Category> categories = new ArrayList<>();
        var occupied = new HashSet<Integer>();
        if (categorySection != null) for (String name : categorySection.getKeys(false)) {
            var section = categorySection.getConfigurationSection(name);
            if (section == null) throw invalid("Invalid category " + name);
            int slot = section.getInt("slot", -1);
            int pages = section.getInt("pages", 0);
            if (slot < -1 || slot >= (rows - 1) * 9 || slot >= 0 && !occupied.add(slot) || pages < 0 || pages > 200) {
                throw invalid("Invalid category slot or page count for " + name);
            }
            String icon = section.getString("icon", "CHEST");
            String texture = section.getString("texture", "");
            if (!texture.isEmpty() && !texture.matches("[0-9a-f]{32,64}")) throw invalid("Invalid head texture for " + name);
            var products = section.getConfigurationSection("products");
            if (products == null) throw invalid("Missing products for " + name);
            List<Product> items = new ArrayList<>();
            var productSlots = new HashSet<Integer>();
            for (String id : products.getKeys(false)) {
                var row = products.getConfigurationSection(id);
                if (row == null) throw invalid("Invalid product " + id);
                String ref = row.getString("item", id);
                if (!ref.matches("[a-z0-9_-]+:[a-z0-9_/-]+(?::[a-z0-9_/-]+)?(?:;[a-z0-9_]+)?")) throw invalid("Invalid item for " + id);
                int productSlot = row.getInt("slot", -1);
                int page = row.getInt("page", 1);
                if (productSlot < -1 || productSlot >= 45 || page < 1 || pages > 0 &&
                        (page > pages || productSlot < 0 || !productSlots.add(page * 45 + productSlot))) {
                    throw invalid("Invalid or duplicate product placement for " + id);
                }
                long buy = cents(row.get("buy", 0), id);
                items.add(new Product(id, ref, row.getString("item-argument", ""), productSlot, page, buy / 100.0,
                        sellPrice(row, id), row.getBoolean("sell", true)));
            }
            String title = section.getString("title", name);
            categories.add(new Category(title, section.getString("display-name", title), icon, texture, slot, pages,
                    List.copyOf(section.getStringList("lore")), List.copyOf(items)));
        }
        return new ShopCatalog(yaml.getString("title", "&8Shop"), rows, List.copyOf(categories), auction);
    }

    private static Price sellPrice(ConfigurationSection row, String id) {
        if (!row.contains("sell-price")) return null;
        var floating = row.getConfigurationSection("sell-price");
        if (floating == null) {
            long price = cents(row.get("sell-price"), id);
            return new Price(price, price, 1);
        }
        long min = cents(floating.get("min"), id);
        long max = cents(floating.get("max"), id);
        int interval = floating.getInt("interval", 300);
        if (min == 0 || max < min || interval < 1 || interval > 86_400) throw invalid("Invalid floating price for " + id);
        return new Price(min, max, interval);
    }

    private static long cents(Object number, String id) {
        if (!(number instanceof Number)) throw invalid("Invalid price for " + id);
        try {
            BigDecimal value = new BigDecimal(number.toString());
            if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100_000_000)) > 0) throw invalid("Invalid price for " + id);
            return value.movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw invalid("Invalid price for " + id);
        }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("shop.yml: " + message); }
}
