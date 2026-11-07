package codes.castled.allium.item.spec;

/** A malformed item spec, with the zero-based character position of the problem when known. */
public final class ItemSpecException extends IllegalArgumentException {

    private final int position;

    public ItemSpecException(String message, int position) {
        super(position >= 0 ? message + " (at character " + (position + 1) + ")" : message);
        this.position = position;
    }

    public ItemSpecException(String message) {
        this(message, -1);
    }

    /** Zero-based position in the parsed text, or -1. */
    public int position() {
        return position;
    }
}
