package org.ryoo.knimeEbi.scaffolder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human-readable presentation of a command contract; never changes its executable metadata. */
public final class EbiNodeText {
    private static final Pattern ARGUMENT_HEADER = Pattern.compile("[A-Z][A-Z0-9 _-]*");
    private static final Pattern HTML_TAG = Pattern.compile(
        "(?i)</?(?:p|br|div|span|b|i|u|em|strong|code|tt|pre|ul|ol|li|a|sub|sup|table|thead|tbody|tr|td|th)(?:\\s[^>]*)?/?>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);");
    private static final Map<String, String> ENTITIES = Map.ofEntries(
        Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
        Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
        Map.entry("le", "≤"), Map.entry("ge", "≥"), Map.entry("ne", "≠"),
        Map.entry("times", "×"), Map.entry("ndash", "–"), Map.entry("mdash", "—"));
    private static final Map<String, String> MATH = Map.ofEntries(
        Map.entry("le", "≤"), Map.entry("leq", "≤"), Map.entry("ge", "≥"), Map.entry("geq", "≥"),
        Map.entry("ne", "≠"), Map.entry("neq", "≠"), Map.entry("times", "×"), Map.entry("cdot", "·"),
        Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("tau", "τ"), Map.entry("epsilon", "ε"),
        Map.entry("infty", "∞"), Map.entry("rightarrow", "→"), Map.entry("leftarrow", "←"),
        Map.entry("ldots", "…"), Map.entry("dots", "…"));

    private EbiNodeText() { }

    /** Plain role name used by port groups and the real node-port tooltip descriptions. */
    public static String inputName(final EbiCommandMetadata metadata, final int logicalIndex) {
        List<EbiCommandMetadataParameter> inputs = portInputs(metadata);
        EbiCommandMetadataParameter parameter = inputs.get(logicalIndex);
        String name = inputRole(parameter);
        long sameNames = inputs.stream().filter(p -> inputRole(p).equals(name)).count();
        if (sameNames > 1) {
            String ordinal = switch (logicalIndex) { case 0 -> "First"; case 1 -> "Second"; case 2 -> "Third"; default -> "Additional"; };
            return ordinal + " " + Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    /** XML fragment describing the argument and only the representations this extension accepts. */
    public static String inputDescription(final EbiCommandMetadata metadata, final int logicalIndex) {
        EbiCommandMetadataParameter parameter = portInputs(metadata).get(logicalIndex);
        String body = body(parameter.typeDescription);
        int accepted = body.indexOf("Accepted values:");
        if (accepted >= 0) body = body.substring(0, accepted).trim();
        body = toPlainText(body);
        if (body.isBlank() || body.matches("(?i)(a file|the log|the model|any file supported by Ebi)[.]?")) {
            body = inputName(metadata, logicalIndex) + " to process.";
        } else {
            // Backend file-handler vocabulary is not a KNIME file-reader promise.
            body = body.replace("Any file supported by Ebi that can be converted.",
                inputName(metadata, logicalIndex) + " to convert.")
                .replace("Any file that can be visualised textually.", "A supported log or process model to display as text.")
                .replace("A file with activities.", "A supported log or process model containing activities.")
                .replace("Any object with activities", "A supported log or process model containing activities");
        }
        StringBuilder result = new StringBuilder(paragraph(sentence(body)));
        result.append(paragraph("Supported KNIME port types: " + portNames(parameter.supportedPortTypes) + "."));
        if (parameter.supportedPortTypes.contains("EbiDocumentPortObject")) {
            result.append(paragraph("Accepted Ebi document formats: " + String.join(", ", parameter.acceptedDocumentFormats) + "."));
        }
        result.append(paragraph("Required input. Select exactly one supported representation."));
        return result.toString();
    }

    public static String outputName(final EbiCommandMetadata metadata) {
        return switch (metadata.output.type.toLowerCase(Locale.ROOT)) {
            case "xes event log", "event log" -> "Event log";
            case "labelled petri net" -> "Petri net";
            case "process tree" -> "Process tree";
            case "directly follows graph" -> "Directly-follows graph";
            case "stochastic labelled petri net" -> "Stochastic Petri net";
            case "stochastic process tree" -> "Stochastic process tree";
            case "deterministic finite automaton" -> "Deterministic automaton";
            case "stochastic deterministic finite automaton" -> "Stochastic deterministic automaton";
            case "stochastic non-deterministic finite automaton" -> "Stochastic nondeterministic automaton";
            case "fraction", "root", "rootlog", "logarithm" -> "Exact result";
            case "bool" -> "Boolean result";
            case "text" -> metadata.commandName.startsWith("Ebi test ") ? "Statistical report"
                : metadata.commandName.equals("Ebi information") ? "Object information" : "Text result";
            default -> sentenceCase(toPlainText(metadata.output.type));
        };
    }

    public static String outputDescription(final EbiCommandMetadata metadata) {
        StringBuilder result = new StringBuilder(paragraph(outputName(metadata) + " produced by this command."));
        result.append(paragraph("Available KNIME port types: " + portNames(metadata.output.supportedPortTypes) + "."));
        if (metadata.output.supportedPortTypes.contains("EbiDocumentPortObject")) {
            result.append(paragraph("The Ebi document representation uses " + metadata.outputFormat("EbiDocumentPortObject")
                + " and preserves its native data."));
        }
        if (metadata.output.supportedPortTypes.equals(List.of("BufferedDataTable"))) {
            result.append(paragraph("The result is a KNIME table with one String cell. Exact expressions remain text."));
        } else if (metadata.output.type.startsWith("stochastic ")) {
            result.append(paragraph("Use Ebi document output to retain stochastic weights. Standard model representations may discard those weights."));
        }
        return result.toString();
    }

    public static String shortDescription(final EbiCommandMetadata metadata) {
        return xmlText(toPlainText(metadata.shortDescription));
    }

    public static String fullDescription(final EbiCommandMetadata metadata) {
        String text = toPlainText(metadata.fullDescription);
        if (!metadata.output.type.startsWith("stochastic ")) {
            text = text.replace("The Ebi document output preserves the native stochastic model.",
                "The Ebi document output preserves the native model representation.")
                .replace(" Standard PM4KNIME model outputs are structural projections and may discard stochastic weights.", "");
        }
        boolean hasAlternatives = metadata.output.supportedPortTypes.size() > 1
            || metadata.inputs.stream().anyMatch(p -> p.isPort && p.supportedPortTypes.size() > 1);
        String portGuidance = hasAlternatives
            ? "To change a port type in the modern KNIME editor, use the port controls to remove the current port, then add a supported replacement. "
                + "Every input and one output are required; a missing-port configuration error remains until the required port is restored."
            : "Every input and one output are required.";
        return paragraph(text) + paragraph(portGuidance);
    }

    public static String settingTitle(final EbiCommandMetadataParameter parameter) {
        String first = header(parameter.typeDescription);
        return switch (first) {
            case "RNDSPL" -> "Random cohort splits";
            case "MINSIZ" -> "Minimum cohort fraction";
            case "SAMPLES" -> "Number of samples";
            case "ATTRIBUTE" -> "Trace attribute";
            case "NUMBER OF TRACES" -> "Number of sampled traces";
            case "K ORDER" -> "Markovian order";
            case "MEASURE" -> "Conformance measure";
            case "ALPHA" -> "Confidence level";
            case "MIN FITNESS" -> "Minimum fitness";
            case "NOISE" -> "Noise threshold";
            case "SELECTOR" -> "Event selector";
            case "ACTIVITY" -> "Activity name";
            case "OPERATOR" -> "Comparison operator";
            case "THRESHOLD" -> "Trace length threshold";
            case "NUMBER OF FOLDS" -> "Number of folds";
            case "SEED" -> "Random seed";
            case "RETURN FOLD" -> "Fold index";
            case "P-VALUE" -> "Significance threshold";
            default -> first.isBlank() || first.length() > 75 ? sentenceCase(parameter.type) + " parameter"
                : sentenceCase(toPlainText(first).replace('_', ' ')).replaceFirst("[.!?]+$", "");
        };
    }

    public static String settingDescription(final EbiCommandMetadataParameter parameter) {
        String raw = body(parameter.typeDescription).replace("`Ebi info`", "the Ebi information node");
        int boundary = raw.indexOf("Accepted values:");
        String explanation = toPlainText(boundary < 0 ? raw : raw.substring(0, boundary));
        String constraints = boundary < 0 ? "" : toPlainText(raw.substring(boundary));
        explanation = switch (explanation.toLowerCase(Locale.ROOT)) {
            case "the operator" -> "Select the comparison used to identify traces to remove.";
            case "the threshold" -> "The trace length, in events, to compare using the selected operator.";
            case "which event(s) in the trace should be the activity" -> "Choose which events must match the activity name.";
            case "the name of the activity that the filter targets" -> "The activity name used to identify traces to remove.";
            default -> explanation;
        };
        // Ebi's generated documentation says 'above', but its integer bound is inclusive.
        constraints = constraints.replaceAll("integer above (-?\\d+)", "integer at least $1");
        return paragraph(sentence(explanation)) + (constraints.isBlank() ? "" : paragraph(sentence(constraints)));
    }

    public static String portTypeName(final String type) {
        return switch (type) {
            case "XLogPortObject" -> "Event log";
            case "PetriNetPortObject" -> "Petri net";
            case "ProcessTreePortObject" -> "Process tree";
            case "DfgMsdPortObject" -> "Directly-follows graph";
            case "DFMPortObject" -> "Directly-follows model";
            case "BpmnPortObject" -> "BPMN model";
            case "EbiDocumentPortObject" -> "Ebi document";
            case "BufferedDataTable" -> "Result table";
            default -> throw new IllegalArgumentException("Unknown port type label: " + type);
        };
    }

    private static List<EbiCommandMetadataParameter> portInputs(final EbiCommandMetadata metadata) {
        return metadata.inputs.stream().filter(p -> p.isPort).toList();
    }

    private static String inputRole(final EbiCommandMetadataParameter parameter) {
        String tag = header(parameter.typeDescription), text = body(parameter.typeDescription);
        int accepted = text.indexOf("Accepted values:");
        if (accepted >= 0) text = text.substring(0, accepted);
        text = text.toLowerCase(Locale.ROOT);
        if (tag.equals("MODEL") || text.contains("(model)") || text.contains("queriable stochastic"))
            return text.contains("stochastic") ? "Stochastic model" : "Process model";
        if (tag.equals("TREE")) return "Process tree";
        if (tag.equals("LPN")) return "Petri net";
        if (tag.equals("LOG") || tag.startsWith("LANG") || tag.equals("SLANG") || text.contains("event log")
                || text.contains("(log)") || text.contains("finite language")) return "Event log";
        if (text.contains("sampleable stochastic") || text.contains("log or model")) return "Log or stochastic model";
        if (parameter.supportedPortTypes.size() == 1) return portTypeName(parameter.portType);
        if (parameter.supportedPortTypes.equals(List.of("ProcessTreePortObject", "EbiDocumentPortObject"))
                && !parameter.acceptedDocumentFormats.isEmpty()
                && parameter.acceptedDocumentFormats.stream().allMatch(f -> f.equals(".ptree") || f.equals(".sptree")))
            return "Process tree";
        if (parameter.supportedPortTypes.contains("XLogPortObject")) return "Log or process model";
        return "Process model";
    }

    private static String portNames(final List<String> types) {
        return String.join(", ", types.stream().map(EbiNodeText::portTypeName).toList());
    }
    private static String header(final String description) {
        return description.lines().findFirst().orElse("").trim();
    }
    private static String body(final String description) {
        int newline = description.indexOf('\n');
        return newline >= 0 && ARGUMENT_HEADER.matcher(description.substring(0, newline).trim()).matches()
            ? description.substring(newline + 1).trim() : description.trim();
    }
    private static String sentence(final String text) {
        String result = text.strip();
        if (result.isEmpty()) return result;
        result = Character.toUpperCase(result.charAt(0)) + result.substring(1);
        return result.matches("(?s).*[.!?:]$") ? result : result + ".";
    }
    private static String sentenceCase(final String text) {
        if (text.isBlank()) return text;
        String result = text.equals(text.toUpperCase(Locale.ROOT)) ? text.toLowerCase(Locale.ROOT) : text;
        return Character.toUpperCase(result.charAt(0)) + result.substring(1);
    }
    private static String paragraph(final String text) { return "<p>" + xmlText(text) + "</p>\n"; }
    private static String xmlText(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Decode documentation markup as text, with balanced groups rather than a greedy regex. */
    public static String toPlainText(final String source) {
        if (source == null || source.isBlank()) return "";
        String text = source.replaceAll("(?i)<(?:br\\s*/?|/p|/div|/li)>", " ");
        text = HTML_TAG.matcher(text).replaceAll("");
        text = new LatexText(text).render();
        Matcher entities = ENTITY.matcher(text); StringBuilder result = new StringBuilder();
        while (entities.find()) {
            String key = entities.group(1), replacement = ENTITIES.get(key);
            if (replacement == null && key.startsWith("#")) {
                try {
                    int code = key.startsWith("#x") ? Integer.parseInt(key.substring(2), 16) : Integer.parseInt(key.substring(1));
                    // A valid Unicode scalar is not necessarily legal XML (for example, &#0;).
                    if (code == 0x9 || code == 0xA || code == 0xD || code >= 0x20 && code <= 0xD7FF
                            || code >= 0xE000 && code <= 0xFFFD || code >= 0x10000 && code <= 0x10FFFF)
                        replacement = Character.toString(code);
                } catch (IllegalArgumentException ignored) { /* Preserve an invalid entity as visible text. */ }
            }
            entities.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? entities.group() : replacement));
        }
        entities.appendTail(result);
        return result.toString().replaceAll("`([^`]+)`", "“$1”").replaceAll("[\\s\\u00A0]+", " ").strip();
    }

    private static final class LatexText {
        private final String text;
        private int index;
        LatexText(final String text) { this.text = text; }
        String render() {
            StringBuilder out = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c != '\\') { out.append(c); continue; }
                if (index == text.length()) { out.append(c); break; }
                char next = text.charAt(index);
                if (!Character.isLetter(next)) {
                    index++;
                    if (next == '\\' || next == ',' || next == ';' || next == '!' || next == ' ') out.append(' ');
                    else out.append(next);
                    continue;
                }
                int start = index;
                while (index < text.length() && Character.isLetter(text.charAt(index))) index++;
                String command = text.substring(start, index);
                if (index < text.length() && text.charAt(index) == '*') index++;
                int commandEnd = index;
                skipSpace();
                boolean hadSeparatingSpace = index > commandEnd;
                if (command.equals("hyperref")) {
                    if (at('[')) group('[', ']');
                    out.append(renderGroup());
                } else if (command.equals("href")) {
                    group('{', '}'); out.append(renderGroup());
                } else if (command.equals("frac")) {
                    String numerator = renderGroup(), denominator = renderGroup();
                    out.append('(').append(numerator).append(")/(").append(denominator).append(')');
                } else if (command.equals("sqrt")) {
                    out.append("√(").append(renderGroup()).append(')');
                } else if (command.equals("begin") || command.equals("end") || command.equals("label")) {
                    group('{', '}'); out.append(' ');
                } else if (command.equals("item")) {
                    out.append(" • ");
                } else if (command.equals("newline") || command.equals("par")) {
                    out.append(' ');
                } else if (MATH.containsKey(command)) {
                    out.append(MATH.get(command));
                    if (hadSeparatingSpace) out.append(' ');
                } else if (at('{')) {
                    // Font commands and unknown wrappers keep their human-readable argument.
                    out.append(renderGroup());
                } else {
                    // Keep unknown content legible instead of silently deleting it.
                    out.append(command);
                    if (hadSeparatingSpace) out.append(' ');
                }
            }
            return out.toString();
        }
        private String renderGroup() { return new LatexText(group('{', '}')).render(); }
        private boolean at(final char value) { return index < text.length() && text.charAt(index) == value; }
        private void skipSpace() { while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++; }
        private String group(final char open, final char close) {
            skipSpace();
            if (!at(open)) return "";
            index++; int start = index, depth = 1;
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '\\' && index < text.length() && (text.charAt(index) == open || text.charAt(index) == close)) { index++; continue; }
                if (c == open) depth++;
                if (c == close && --depth == 0) return text.substring(start, index - 1);
            }
            return text.substring(start);
        }
    }
}
