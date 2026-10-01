package codes.castled.allium.tradingcards.config;

/**
 * One trading card configuration problem, pinned to a file and config path.
 *
 * <p>Mirrors the harvest module's own {@code ValidationIssue} rather than
 * sharing it, so the two subsystems can be split into separate plugins later
 * without either depending on the other.
 */
public record ValidationIssue(
    Severity severity,
    String file,
    String path,
    String message
) {

    public enum Severity { WARNING, ERROR }

    public static ValidationIssue error(String file, String path, String message) {
        return new ValidationIssue(Severity.ERROR, file, path, message);
    }

    public static ValidationIssue warning(String file, String path, String message) {
        return new ValidationIssue(Severity.WARNING, file, path, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return severity + " [" + file + " @ " + path + "]: " + message;
    }
}
