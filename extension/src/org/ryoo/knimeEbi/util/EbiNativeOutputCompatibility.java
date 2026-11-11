package org.ryoo.knimeEbi.util;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Narrow syntax repairs for native outputs verified against the pinned Ebi reader. */
public final class EbiNativeOutputCompatibility {
    private static final Pattern SNFA_INITIAL_STATE = Pattern.compile(
        "\\A(stochastic non-deterministic finite automaton\\r?\\n# initial state\\r?\\n)"
        + "AutomatonState\\(([0-9]+)\\)(\\r?\\n# number of states\\r?\\n([0-9]+)(?:\\r?\\n|$))");
    private EbiNativeOutputCompatibility() { }
    public static String normalize(final String format, final String text) {
        if (!".snfa".equals(format)) return text;
        final var matcher = SNFA_INITIAL_STATE.matcher(text);
        if (!matcher.find()) return text;
        if (new BigInteger(matcher.group(2)).compareTo(new BigInteger(matcher.group(4))) >= 0)
            throw new IllegalArgumentException("Ebi SNFA initial state lies outside the declared state range.");
        return matcher.group(1) + matcher.group(2) + matcher.group(3) + text.substring(matcher.end());
    }
}
