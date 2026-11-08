package codes.castled.allium.message;

import java.util.Objects;

/**
 * A message read from the lang files by key, with a built-in {@link Messages} entry whose text
 * is used when no lang file has the key. Tool definitions point their message slots at keys this
 * way; {@code LangMessage.of(m)} is exactly {@code m}.
 */
public record LangMessage(String key, Messages fallback) {

    public LangMessage {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fallback, "fallback");
    }

    public static LangMessage of(Messages message) {
        return new LangMessage(message.getKey(), message);
    }
}
