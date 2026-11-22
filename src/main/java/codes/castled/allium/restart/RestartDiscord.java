package codes.castled.allium.restart;

import github.scarsz.discordsrv.DiscordSRV;

import java.util.List;
import java.util.function.Consumer;

final class RestartDiscord {
    private RestartDiscord() {}

    static void send(String message, Consumer<String> warning) {
        try {
            var plugin = DiscordSRV.getPlugin();
            if (plugin == null || plugin.getJda() == null) return;
            var channel = plugin.getMainTextChannel();
            if (channel != null) {
                channel.sendMessage(message).allowedMentions(List.of()).queue(sent -> {},
                        failed -> warning.accept("Could not relay restart notice through DiscordSRV."));
            }
        } catch (RuntimeException | LinkageError unavailable) {
            warning.accept("DiscordSRV restart notices are unavailable.");
        }
    }
}
