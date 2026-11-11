package org.ryoo.knimeEbi.util;

import java.util.Set;
import java.util.regex.Pattern;

/** Retains native exact expressions and removes auxiliary approximate numeric lines. */
public final class EbiScalarResult {
    private static final Set<String> EXACT_FORMATS = Set.of(".frac", ".croot", ".rldiv", ".logdiv", ".logpol");
    private static final Pattern APPROXIMATION = Pattern.compile("(?m)^Approximately\\b[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)");
    private EbiScalarResult() { }
    public static String exactText(final String format, final String result) {
        if (result == null) throw new IllegalArgumentException("Ebi returned no scalar result.");
        if (!EXACT_FORMATS.contains(format)) return result;
        final String exact = APPROXIMATION.matcher(result).replaceAll("");
        if (exact.isBlank()) throw new IllegalArgumentException("Ebi returned an approximation without an exact expression.");
        return exact;
    }
}
