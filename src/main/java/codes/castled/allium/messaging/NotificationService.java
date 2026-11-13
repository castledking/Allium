package codes.castled.allium.messaging;

import codes.castled.allium.message.Messages;
import codes.castled.allium.text.FormattedText;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/** Structured messaging notifications. Call on the server thread; bodies never become template input. */
public interface NotificationService {
    void send(CommandSender recipient, Notification notification);

    enum Sound { NONE, MESSAGE, SENT }

    /** Placeholders are trusted template values (including braces); body is separately parsed untrusted input. */
    record Notification(Messages message, Map<String, String> placeholders, @Nullable FormattedText body, Sound sound) {
        public Notification {
            Objects.requireNonNull(message, "message");
            placeholders = Map.copyOf(placeholders);
            Objects.requireNonNull(sound, "sound");
        }
    }
}
