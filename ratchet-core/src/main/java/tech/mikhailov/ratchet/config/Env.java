package tech.mikhailov.ratchet.config;


/**
 * Process environment, with blank treated as unset.
 */
public final class Env {

    private Env() {
    }

    /** Null when unset or blank. */
    public static String get(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    public static String get(String name, String fallback) {
        String v = get(name);
        return v == null ? fallback : v;
    }

    /**
     * True for 1/true/yes; false for 0/false/no; otherwise the fallback.
     */
    public static boolean flag(String name, boolean fallback) {
        String v = get(name);
        if (v == null) {
            return fallback;
        }
        if (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes")) {
            return true;
        }
        if (v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no")) {
            return false;
        }
        return fallback;
    }

}
