package codes.castled.allium.compat.legacy;

import java.lang.reflect.Method;

public final class LegacyClassLoadingCheck {

    public static void main(String[] args) {
        for (String className : args) {
            try {
                String resolved = resolveClassName(className);
                Class<?> clazz = Class.forName(resolved);
                System.out.println("[OK] Loaded: " + resolved);

                if (className.contains("#")) {
                    String methodName = className.substring(className.indexOf("#") + 1);
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(methodName)) {
                            System.out.println("[OK] Method found: " + methodName);
                            break;
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                System.err.println("[FAIL] Could not load: " + className);
                System.exit(1);
            }
        }
    }

    private static String resolveClassName(String raw) {
        if (raw.contains("#")) {
            return raw.substring(0, raw.indexOf("#"));
        }
        return raw;
    }

    private LegacyClassLoadingCheck() {
    }
}
