package codes.castled.allium.message;

import codes.castled.allium.PluginStart;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageManager {

    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)&?#([0-9a-f]{6})");

    private final PluginStart plugin;
    private final LanguageManager langManager;

    public MessageManager(PluginStart plugin, LanguageManager langManager) {
        this.plugin = plugin;
        this.langManager = langManager;
    }

    public void send(CommandSender sender, Messages message, String... replacements) {
        String text = resolve(message, replacements);
        if (text.isEmpty()) return;

        sender.sendMessage(colorize(text));
    }

    public void broadcast(Messages message, String... replacements) {
        String text = resolve(message, replacements);
        if (text.isEmpty()) return;

        String colored = colorize(text);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(colored);
        }

        plugin.getServer().getConsoleSender().sendMessage(stripColor(colored));
    }

    public void sendToPlayer(Player player, Messages message, String... replacements) {
        send(player, message, replacements);
    }

    public String resolve(Messages message, String... replacements) {
        String text = langManager.get(message);

        String prefix = langManager.get(Messages.PREFIX);
        text = text.replace("{prefix}", prefix);

        text = text.replace("{version}", plugin.getDescription().getVersion());

        if (replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                text = text.replace(replacements[i], replacements[i + 1]);
            }
        }

        return text;
    }

    public static String colorize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder(input.length() + 32);
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (int i = 0; i < 6; i++) {
                replacement.append('§').append(Character.toLowerCase(hex.charAt(i)));
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public Builder builder(Messages message) {
        return new Builder(message);
    }

    private String stripColor(String text) {
        return ChatColor.stripColor(text);
    }

    public final class Builder {
        private final Messages message;
        private final Map<String, String> replacements = new HashMap<>();

        Builder(Messages message) {
            this.message = message;
        }

        public Builder replace(String placeholder, String value) {
            replacements.put(placeholder, value);
            return this;
        }

        public Builder player(Player player) {
            replacements.put("{player}", player.getName());
            return this;
        }

        public Builder state(boolean enabled) {
            replacements.put("{state}", enabled ? "&aenabled" : "&cdisabled");
            return this;
        }

        public void send(CommandSender sender) {
            String[] flat = new String[replacements.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                flat[i++] = entry.getKey();
                flat[i++] = entry.getValue();
            }
            MessageManager.this.send(sender, message, flat);
        }

        public void broadcast() {
            String[] flat = new String[replacements.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                flat[i++] = entry.getKey();
                flat[i++] = entry.getValue();
            }
            MessageManager.this.broadcast(message, flat);
        }
    }
}
