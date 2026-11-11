package org.ryoo.knimeEbi.scaffolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Ebi's own generated manual to retain file capabilities absent from ProM metadata. */
public final class EbiManualMetadata {
    private static final Pattern SECTION = Pattern.compile("\\\\clearpage\\\\section\\{\\\\texttt\\{([^}]+)}}");
    private static final Pattern PARAMETER = Pattern.compile("(?m)^<\\\\texttt\\{([^}]+)}>&");
    private static final Pattern EXTENSION = Pattern.compile("\\((\\.[a-z0-9.]+)(?: --|\\))");
    private static final Set<String> DOCUMENT_FORMATS = Set.of(".slpn", ".sptree", ".sdfa", ".snfa", ".dfg", ".dfm",
        ".slang", ".lang", ".dfa", ".lpn", ".ptree", ".powl", ".sbpmn", ".sdfm", ".ali", ".sali");
    private static final Map<String, String> INPUT_FORMATS = Map.of(
        "XLogPortObject", ".xes", "PetriNetPortObject", ".pnml", "ProcessTreePortObject", ".ptml",
        "DfgMsdPortObject", ".dfg", "DFMPortObject", ".dfm", "BpmnPortObject", ".bpmn");
    private final Map<String, String> sections = new LinkedHashMap<>();

    public EbiManualMetadata(final String manual) {
        Matcher m = SECTION.matcher(manual);
        List<String> names = new ArrayList<>(); List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        while (m.find()) { names.add(m.group(1)); starts.add(m.start()); ends.add(m.end()); }
        for (int i = 0; i < names.size(); i++) {
            String previous = sections.put(names.get(i), manual.substring(ends.get(i), i + 1 < names.size() ? starts.get(i + 1) : manual.length()));
            if (previous != null) throw new IllegalArgumentException("Duplicate manual command " + names.get(i));
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("No command contracts in Ebi manual.");
    }

    public EbiCommandMetadata enrich(final EbiCommandMetadata javaMetadata) {
        String section = section(javaMetadata.commandName);
        List<Argument> arguments = arguments(section);
        if (arguments.size() != javaMetadata.inputs.size())
            throw new IllegalArgumentException("Java/manual argument count differs for " + javaMetadata.commandName);
        List<EbiCommandMetadataParameter> inputs = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            Argument a = arguments.get(i); EbiCommandMetadataParameter old = javaMetadata.inputs.get(i);
            if (old.isPort != !a.formats.isEmpty())
                throw new IllegalArgumentException("Java/manual argument kind differs for " + javaMetadata.commandName + " at " + i);
            inputs.add(parameter(a, old.type));
        }
        return create(javaMetadata.commandName, javaMetadata.shortDescription, javaMetadata.fullDescription,
            inputs, section, javaMetadata.outputFormats.get(javaMetadata.output.portType));
    }

    /** Explicitly requested legacy command with a scalar exporter not exposed by ProM. */
    public EbiCommandMetadata entropicRelevance() {
        String command = "Ebi conformance entropic-relevance";
        String section = section(command);
        List<EbiCommandMetadataParameter> inputs = arguments(section).stream().map(a -> parameter(a, "EbiObject")).toList();
        return create(command, "Compute entropic relevance between an event log and a stochastic model.",
            "Compute entropic relevance between an event log and a stochastic model. Exact logarithmic results are retained as text.",
            inputs, section, ".logpol");
    }

    private EbiCommandMetadata create(final String command, final String title, final String help,
            final List<EbiCommandMetadataParameter> inputs, final String section, final String scalarFormat) {
        Matcher outputLine = Pattern.compile("(?m)^\\\\noindent Output: (.*)$").matcher(section);
        if (!outputLine.find()) throw new IllegalArgumentException("No output contract for " + command);
        String outputText = outputLine.group(1);
        String nativeType = outputText.split(", which can be written", 2)[0].trim();
        Set<String> exporters = extensions(outputText);
        LinkedHashMap<String, String> outputs = new LinkedHashMap<>();
        String nativeFormat = "";
        Matcher defaultFormat = Pattern.compile("The output file extension \\(without period\\)\\. The default is ([a-z0-9.]+)\\. Possible values").matcher(section);
        if (defaultFormat.find()) nativeFormat = "." + defaultFormat.group(1);
        if (exporters.isEmpty()) {
            if (scalarFormat == null || scalarFormat.isBlank()) throw new IllegalArgumentException("No scalar exporter for " + command);
            outputs.put("BufferedDataTable", scalarFormat);
        } else {
            // Native stochastic objects must not silently lose their probabilities.
            if (DOCUMENT_FORMATS.contains(nativeFormat) && (nativeType.contains("stochastic") || nativeType.contains("automaton")))
                outputs.put("EbiDocumentPortObject", nativeFormat);
            if (nativeType.contains("process tree") && exporters.contains(".ptml")) outputs.put("ProcessTreePortObject", ".ptml");
            if (nativeType.equals("directly follows graph") && exporters.contains(".dfg")) {
                outputs.put("DfgMsdPortObject", ".dfg");
                outputs.put("DFMPortObject", ".dfg");
            }
            if (nativeType.contains("event log") && exporters.contains(".xes")) outputs.put("XLogPortObject", ".xes");
            for (String port : EbiCommandMetadata.PORT_ORDER) {
                String extension = INPUT_FORMATS.get(port);
                // The pinned Ebi .dfm exporter has a confirmed end-activity bitmap defect.
                // Do not expose it as a typed output; .dfg is the faithful graph representation.
                if (port.equals("DFMPortObject")) continue;
                if (extension != null && exporters.contains(extension)) outputs.putIfAbsent(port, extension);
            }
            if (DOCUMENT_FORMATS.contains(nativeFormat) && !nativeFormat.equals(".dfm")) outputs.putIfAbsent("EbiDocumentPortObject", nativeFormat);
            if (outputs.isEmpty()) throw new IllegalArgumentException("No supported output adapter for " + command);
        }
        List<EbiCommandVariant> variants = new ArrayList<>();
        List<List<String>> tuples = new ArrayList<>(); tuples.add(List.of());
        for (EbiCommandMetadataParameter p : inputs) {
            if (!p.isPort) continue;
            List<List<String>> next = new ArrayList<>();
            for (List<String> tuple : tuples) for (String type : p.supportedPortTypes) {
                List<String> expanded = new ArrayList<>(tuple); expanded.add(type); next.add(List.copyOf(expanded));
            }
            tuples = next;
        }
        for (Map.Entry<String, String> output : outputs.entrySet()) for (List<String> tuple : tuples)
            variants.add(new EbiCommandVariant(tuple, output.getKey(), output.getValue()));
        List<String> allowed = List.copyOf(outputs.keySet());
        String description = help;
        if (outputs.containsKey("EbiDocumentPortObject") && allowed.size() > 1)
            description += " The Ebi document output preserves the native stochastic model. Standard PM4KNIME model outputs are structural projections and may discard stochastic weights.";
        return new EbiCommandMetadata(command, title, description, inputs,
            new EbiCommandMetadataParameter(nativeType, allowed.get(0), "", true, allowed,
                outputs.containsKey("EbiDocumentPortObject") ? List.of(outputs.get("EbiDocumentPortObject")) : List.of()), variants);
    }

    private static EbiCommandMetadataParameter parameter(final Argument a, final String originalType) {
        String description = a.name.replace("\\_", " ") + "\n" + plain(a.help) + "\nAccepted values: " + plain(a.accepted);
        if (a.formats.isEmpty()) return new EbiCommandMetadataParameter(originalType, "", description, false);
        LinkedHashSet<String> ports = new LinkedHashSet<>();
        for (String port : EbiCommandMetadata.PORT_ORDER) if (a.formats.contains(INPUT_FORMATS.get(port))) ports.add(port);
        List<String> raw = a.formats.stream().filter(DOCUMENT_FORMATS::contains).sorted().toList();
        if (!raw.isEmpty()) ports.add("EbiDocumentPortObject");
        if (ports.isEmpty()) throw new IllegalArgumentException("No adapter for input " + a.name + ": " + a.formats);
        List<String> allowed = List.copyOf(ports);
        return new EbiCommandMetadataParameter(originalType, allowed.get(0), description, true, allowed, raw);
    }

    private List<Argument> arguments(final String section) {
        Matcher p = PARAMETER.matcher(section);
        List<String> names = new ArrayList<>(); List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        while (p.find()) { names.add(p.group(1)); starts.add(p.start()); ends.add(p.end()); }
        List<Argument> result = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String part = section.substring(ends.get(i), i + 1 < names.size() ? starts.get(i + 1) : section.length());
            String help = part.split("\\\\\\\\", 2)[0];
            Matcher accepted = Pattern.compile("Accepted values:\\}\\\\quad ([^\\n]*)").matcher(part);
            if (!accepted.find()) throw new IllegalArgumentException("No accepted values for " + names.get(i));
            result.add(new Argument(names.get(i), help, accepted.group(1), extensions(accepted.group(1))));
        }
        return result;
    }
    private String section(final String command) {
        String value = sections.get(command);
        if (value == null) throw new IllegalArgumentException("No manual contract for " + command);
        return value;
    }
    private static Set<String> extensions(final String text) {
        Matcher m = EXTENSION.matcher(text); Set<String> result = new LinkedHashSet<>();
        while (m.find()) result.add(m.group(1)); return result;
    }
    private static String plain(final String text) {
        return text.replace("\\_", "_").replace("\\%", "%").replace("\\\\", "")
            .replace("\\quad", " ").replace("\\textsuperscript{\\textdagger}", " (conversion may fail)").trim();
    }
    private record Argument(String name, String help, String accepted, Set<String> formats) { }
}
