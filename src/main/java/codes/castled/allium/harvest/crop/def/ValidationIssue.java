package codes.castled.allium.harvest.crop.def;

/** One configuration validation problem, pinned to a file and config path. */
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
