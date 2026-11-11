package org.ryoo.knimeEbi.scaffolder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validation shared by configuration, execution and dependency-free tests. */
public final class EbiParameterRules {
    private static final Pattern INTEGER_MIN = Pattern.compile("integer above (-?\\d+)");
    private static final Pattern FRACTION_RANGE = Pattern.compile("fraction between ([^ ]+) and ([^ .]+(?:\\.[0-9]+)?)");
    private static final Pattern CHOICE = Pattern.compile("`([^`]+)`");
    private EbiParameterRules() { }

    public static void validateSettings(final EbiCommandMetadata metadata, final String[] values) {
        List<EbiCommandMetadataParameter> settings = metadata.inputs.stream().filter(p -> !p.isPort).toList();
        if (values == null || values.length != settings.size())
            throw new IllegalArgumentException("Expected " + settings.size() + " settings for " + metadata.commandName + ".");
        for (int i = 0; i < values.length; i++) validate(settings.get(i), values[i]);
        if (metadata.commandName.equals("Ebi sample folds") && values.length == 3) {
            // The native contract requires the selected fold to be strictly below the number of folds.
            BigInteger folds = new BigInteger(values[0].trim());
            BigInteger fold = new BigInteger(values[2].trim());
            if (fold.compareTo(folds) >= 0) throw new IllegalArgumentException("The fold index must be smaller than the number of folds.");
        }
    }

    /** Canonical numeric transport after validation; never alter user String labels. */
    public static String[] normalizeSettings(final EbiCommandMetadata metadata, final String[] values) {
        validateSettings(metadata, values);
        List<EbiCommandMetadataParameter> parameters = metadata.inputs.stream().filter(p -> !p.isPort).toList();
        String[] normalized = values.clone();
        for (int i = 0; i < values.length; i++) {
            normalized[i] = switch (parameters.get(i).type) {
                case "Integer", "Long", "Byte", "Short" -> new BigInteger(values[i].trim()).toString();
                case "BigFraction" -> {
                    Rational r = rational(values[i].trim());
                    yield r.denominator().equals(BigInteger.ONE) ? r.numerator().toString()
                        : r.numerator() + "/" + r.denominator();
                }
                case "Double", "Float" -> Double.toString(Double.parseDouble(values[i].trim()));
                case "Boolean" -> values[i].trim();
                default -> values[i];
            };
        }
        return normalized;
    }

    public static void validate(final EbiCommandMetadataParameter parameter, final String value) {
        String label = title(parameter);
        if (value == null) throw new IllegalArgumentException(label + " must not be null.");
        String trimmed = value.trim();
        try {
            switch (parameter.type) {
                case "Integer", "Long", "Byte", "Short" -> {
                    BigInteger n = new BigInteger(trimmed);
                    BigInteger min = BigInteger.ZERO;
                    Matcher limit = INTEGER_MIN.matcher(parameter.typeDescription);
                    if (limit.find()) min = new BigInteger(limit.group(1));
                    if (n.compareTo(min) < 0) throw new IllegalArgumentException("must be at least " + min);
                    BigInteger max = switch (parameter.type) {
                        case "Byte" -> BigInteger.valueOf(Byte.MAX_VALUE);
                        case "Short" -> BigInteger.valueOf(Short.MAX_VALUE);
                        case "Integer" -> BigInteger.valueOf(Integer.MAX_VALUE);
                        default -> BigInteger.valueOf(Long.MAX_VALUE);
                    };
                    if (n.compareTo(max) > 0) throw new IllegalArgumentException("is outside the supported integer range");
                }
                case "BigFraction" -> {
                    Rational fraction = rational(trimmed);
                    Matcher range = FRACTION_RANGE.matcher(parameter.typeDescription);
                    if (range.find() && (fraction.compareTo(rational(range.group(1))) < 0 || fraction.compareTo(rational(range.group(2))) > 0))
                        throw new IllegalArgumentException("must be between " + range.group(1) + " and " + range.group(2));
                }
                case "Double", "Float" -> {
                    double d = Double.parseDouble(trimmed);
                    if (!Double.isFinite(d)) throw new IllegalArgumentException("must be finite");
                }
                case "Boolean" -> {
                    if (!trimmed.equals("true") && !trimmed.equals("false")) throw new IllegalArgumentException("must be true or false");
                }
                case "Character" -> {
                    if (value.codePointCount(0, value.length()) != 1) throw new IllegalArgumentException("must contain one character");
                }
                case "String" -> {
                    List<String> allowed = choices(parameter);
                    if (!allowed.isEmpty() && !allowed.contains(value)) throw new IllegalArgumentException("must be one of " + allowed);
                    if (allowed.isEmpty() && trimmed.isEmpty()) throw new IllegalArgumentException("must not be empty");
                }
                default -> throw new IllegalArgumentException("has an unsupported setting type " + parameter.type);
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(label + ": " + (ex.getMessage() == null ? "invalid value" : ex.getMessage()), ex);
        }
    }

    public static String title(final EbiCommandMetadataParameter p) {
        return EbiNodeText.settingTitle(p);
    }

    public static String defaultValue(final EbiCommandMetadata metadata, final int index) {
        EbiCommandMetadataParameter p = metadata.inputs.get(index);
        String name = p.typeDescription.toLowerCase(Locale.ROOT);
        return switch (p.type) {
            case "Integer", "Long", "Byte", "Short" -> {
                if (metadata.commandName.equals("Ebi sample folds")) yield index == 1 ? "5" : "0";
                if (name.contains("sample") || name.contains("repetition") || name.contains("random splits")) yield "100";
                Matcher m = INTEGER_MIN.matcher(name);
                yield m.find() ? m.group(1) : "0";
            }
            case "BigFraction" -> name.contains("p-value") || name.contains("significance") ? "0.05"
                : name.contains("minimum fraction of traces") ? "0.8" : "0.5";
            case "Double", "Float" -> "0.0";
            case "Boolean" -> "false";
            case "Character" -> "a";
            case "String" -> {
                List<String> allowed = choices(p);
                if (allowed.contains(">=")) yield ">=";
                if (!allowed.isEmpty()) yield allowed.get(0);
                yield "";
            }
            default -> throw new IllegalArgumentException("Unknown setting type " + p.type);
        };
    }

    private static List<String> choices(final EbiCommandMetadataParameter p) {
        int start = p.typeDescription.indexOf("Accepted values:");
        if (start < 0) return List.of();
        String constraints = p.typeDescription.substring(start);
        if (!constraints.contains("either one of")) return List.of();
        Matcher m = CHOICE.matcher(constraints); List<String> choices = new ArrayList<>();
        while (m.find()) choices.add(m.group(1)); return choices;
    }
    static Rational rational(final String value) {
        if (value.contains("/")) {
            String[] parts = value.split("/", -1);
            if (parts.length != 2) throw new IllegalArgumentException("use a decimal or numerator/denominator");
            return new Rational(new BigInteger(parts[0].trim()), new BigInteger(parts[1].trim()));
        }
        BigDecimal decimal = new BigDecimal(value);
        if (Math.abs((long)decimal.scale()) > 10000) throw new IllegalArgumentException("decimal exponent is too large; use a practical exact fraction");
        return decimal.scale() >= 0
            ? new Rational(decimal.unscaledValue(), BigInteger.TEN.pow(decimal.scale()))
            : new Rational(decimal.unscaledValue().multiply(BigInteger.TEN.pow(-decimal.scale())), BigInteger.ONE);
    }
    record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {
        Rational {
            if (denominator.signum() == 0) throw new IllegalArgumentException("denominator must not be zero");
            if (denominator.signum() < 0) { numerator = numerator.negate(); denominator = denominator.negate(); }
        }
        @Override public int compareTo(final Rational other) {
            return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
        }
    }
}
