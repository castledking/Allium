package codes.castled.allium.api;

/**
 * Allium refused this addon: its declared API range does not include this Allium's API version,
 * it bundles (shades) its own copy of the API, or it declares no {@code allium-api} range. The
 * message says which and what to do. Allium disables a refused addon as soon as it has enabled.
 */
public final class AddonRefusedException extends RuntimeException {

    public AddonRefusedException(String message) {
        super(message);
    }
}
