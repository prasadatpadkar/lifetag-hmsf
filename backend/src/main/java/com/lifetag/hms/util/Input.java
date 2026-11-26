package com.lifetag.hms.util;

import java.util.Map;
import java.util.regex.Pattern;

/** Small helpers for controllers that read loosely-typed JSON bodies. Every failure
 *  is an IllegalArgumentException, which GlobalExceptionHandler turns into a 400. */
public final class Input {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Input() {}

    /** A required numeric record id from a JSON body, e.g. patientId. */
    public static Long id(Map<String, ?> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) throw new IllegalArgumentException(key + " is required.");
        try {
            long id = Long.parseLong(v.toString().trim());
            if (id < 1) throw new IllegalArgumentException(key + " is not valid.");
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not valid.");
        }
    }

    /** Trimmed text of at most {@code max} characters (null stays null). */
    public static String text(String value, String label, int max) {
        if (value == null) return null;
        String t = value.trim();
        if (t.length() > max) throw new IllegalArgumentException(label + " must be at most " + max + " characters.");
        return t;
    }

    public static String email(String value, String label) {
        String t = text(value, label, 120);
        if (t == null || !EMAIL.matcher(t).matches()) throw new IllegalArgumentException("Enter a valid " + label.toLowerCase() + ".");
        return t;
    }
}
