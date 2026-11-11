package org.ryoo.knimeEbi.scaffolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable command contract used by both the generator and the KNIME runtime. */
public final class EbiCommandMetadata {
    private static final Pattern METHOD = Pattern.compile(
        "public\\s+static\\s+\\S+\\s+\\w+\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[^\\{]+)?\\{");
    private static final String QUOTED = "\"((?:\\\\.|[^\"\\\\])*)\"";
    private static final Pattern CALL = Pattern.compile("CallEbi\\.call_ebi\\s*\\(\\s*" + QUOTED + "\\s*,\\s*" + QUOTED);
    private static final Pattern PANEL = Pattern.compile("create_input_panel\\s*\\(\\s*" + QUOTED);
    private static final Set<String> PRIMITIVES = Set.of("Integer", "String", "Byte", "Short", "Long", "Double", "Float", "Character", "Boolean", "BigFraction");
    public static final List<String> PORT_ORDER = List.of("XLogPortObject", "PetriNetPortObject",
        "ProcessTreePortObject", "DfgMsdPortObject", "DFMPortObject", "BpmnPortObject", "EbiDocumentPortObject", "BufferedDataTable");

    public final String commandName;
    public final String shortDescription;
    public final String fullDescription;
    public final List<EbiCommandMetadataParameter> inputs;
    public final EbiCommandMetadataParameter output;
    public final List<EbiCommandVariant> variants;
    public final Map<String, String> outputFormats;

    public EbiCommandMetadata(final String metadataBlock) {
        this(parse(metadataBlock));
    }

    private EbiCommandMetadata(final EbiCommandMetadata source) {
        this(source.commandName, source.shortDescription, source.fullDescription, source.inputs,
            source.output, source.variants);
    }

    public EbiCommandMetadata(final String commandName, final String shortDescription,
            final String fullDescription, final List<EbiCommandMetadataParameter> inputs,
            final EbiCommandMetadataParameter output) {
        this(commandName, shortDescription, fullDescription, inputs, output,
            List.of(new EbiCommandVariant(inputs.stream().filter(p -> p.isPort).map(p -> p.portType).toList(),
                output.portType, defaultOutputFormat(output.type, output.portType))));
    }

    public EbiCommandMetadata(final String commandName, final String shortDescription,
            final String fullDescription, final List<EbiCommandMetadataParameter> inputs,
            final EbiCommandMetadataParameter output, final List<EbiCommandVariant> variants) {
        this.commandName = Objects.requireNonNull(commandName, "commandName");
        this.shortDescription = Objects.requireNonNull(shortDescription, "shortDescription");
        this.fullDescription = Objects.requireNonNull(fullDescription, "fullDescription");
        this.inputs = List.copyOf(inputs);
        this.output = Objects.requireNonNull(output, "output");
        this.variants = List.copyOf(new LinkedHashSet<>(variants));
        if (commandName.isBlank() || this.variants.isEmpty()) {
            throw new IllegalArgumentException("A command needs a name and at least one complete signature.");
        }
        LinkedHashMap<String, String> formats = new LinkedHashMap<>();
        for (EbiCommandVariant variant : this.variants) {
            String previous = formats.putIfAbsent(variant.outputPortType(), variant.outputFormat());
            if (previous != null && !previous.equals(variant.outputFormat())) {
                throw new IllegalArgumentException("Ambiguous format for output port " + variant.outputPortType());
            }
        }
        outputFormats = Collections.unmodifiableMap(formats);
        validatePortMetadata(this);
    }

    public boolean hasNoPrimitiveInputs() { return inputs.stream().allMatch(p -> p.isPort); }
    public int inputPortCount() { return (int)inputs.stream().filter(p -> p.isPort).count(); }
    public String outputFormat(final String portType) {
        String format = outputFormats.get(portType);
        if (format == null) throw new IllegalArgumentException("Unsupported output " + portType + " for " + commandName);
        return format;
    }
    public boolean supportsSignature(final List<String> inputTypes, final String outputType) {
        return variants.stream().anyMatch(v -> v.inputPortTypes().equals(inputTypes) && v.outputPortType().equals(outputType));
    }

    public static void validatePortMetadata(final EbiCommandMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (!metadata.output.isPort) throw new IllegalArgumentException("Output must be a port.");
        for (EbiCommandMetadataParameter p : metadata.inputs) {
            if (p.isPort && !PORT_ORDER.containsAll(p.supportedPortTypes))
                throw new IllegalArgumentException("Unsupported input port types: " + p.supportedPortTypes);
        }
        if (!PORT_ORDER.containsAll(metadata.output.supportedPortTypes))
            throw new IllegalArgumentException("Unsupported output port types: " + metadata.output.supportedPortTypes);
        List<EbiCommandMetadataParameter> ports = metadata.inputs.stream().filter(p -> p.isPort).toList();
        for (EbiCommandVariant v : metadata.variants) {
            if (v.inputPortTypes().size() != ports.size()) throw new IllegalArgumentException("Signature arity differs for " + metadata.commandName);
            for (int i = 0; i < ports.size(); i++) {
                if (!ports.get(i).supportedPortTypes.contains(v.inputPortTypes().get(i)))
                    throw new IllegalArgumentException("Signature uses an undeclared input alternative.");
            }
            if (!metadata.output.supportedPortTypes.contains(v.outputPortType()))
                throw new IllegalArgumentException("Signature uses an undeclared output alternative.");
        }
        if (!metadata.outputFormats.keySet().containsAll(metadata.output.supportedPortTypes))
            throw new IllegalArgumentException("Every output alternative needs a format.");
    }

    private static EbiCommandMetadata parse(final String block) {
        Objects.requireNonNull(block, "metadataBlock");
        int separator = block.indexOf("==");
        if (separator < 0) throw new IllegalArgumentException("Metadata block has no == separator.");
        String command = block.substring(0, separator).trim();
        if (command.startsWith("// == command ")) command = command.substring(14).trim();
        Matcher matcher = METHOD.matcher(block);
        List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        List<String> parameterLists = new ArrayList<>();
        while (matcher.find()) { starts.add(matcher.start()); ends.add(matcher.end()); parameterLists.add(matcher.group(1)); }
        if (starts.isEmpty()) throw new IllegalArgumentException("No callable Java declarations for " + command);
        List<EbiCommandMetadataParameter> firstInputs = null;
        List<LinkedHashSet<String>> alternatives = new ArrayList<>();
        List<EbiCommandVariant> variants = new ArrayList<>();
        LinkedHashSet<String> outputTypes = new LinkedHashSet<>();
        String title = null, help = null, firstOutput = null;
        List<String> rejected = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            String declaration = block.substring(ends.get(i), i + 1 < starts.size() ? starts.get(i + 1) : block.length());
            if (!declaration.contains("@Plugin(")) continue;
            Matcher call = CALL.matcher(declaration);
            if (!call.find()) throw new IllegalArgumentException("Missing native call for " + command);
            if (!command.equals(unescape(call.group(1)))) throw new IllegalArgumentException("Command declaration/call mismatch: " + command);
            String outputLabel = attribute(declaration, "returnLabels");
            String outPort;
            try { outPort = outputPortType(outputLabel); }
            catch (IllegalArgumentException ex) { rejected.add(ex.getMessage()); continue; }
            List<EbiCommandMetadataParameter> params = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            Matcher panel = PANEL.matcher(declaration);
            while (panel.find()) labels.add(unescape(panel.group(1)));
            int settingIndex = 0;
            boolean unsupported = false;
            for (String p : parameterLists.get(i).split(",")) {
                String parameter = p.trim().replaceFirst("^final\\s+", "");
                if (parameter.isEmpty()) continue;
                String type = parameter.split("\\s+")[0];
                type = type.substring(type.lastIndexOf('.') + 1);
                if (type.equals("PluginContext") || type.equals("UIPluginContext")) continue;
                if (PRIMITIVES.contains(type)) {
                    if (settingIndex >= labels.size()) throw new IllegalArgumentException("Missing setting label for " + command + " argument " + params.size());
                    params.add(new EbiCommandMetadataParameter(type, "", labels.get(settingIndex++), false));
                } else {
                    try { params.add(new EbiCommandMetadataParameter(type, inputPortType(type), "", true)); }
                    catch (IllegalArgumentException ex) { rejected.add(ex.getMessage()); unsupported = true; break; }
                }
            }
            if (unsupported) continue;
            if (firstInputs == null) {
                firstInputs = params;
                for (EbiCommandMetadataParameter p : params) alternatives.add(new LinkedHashSet<>(p.supportedPortTypes));
                title = attribute(declaration, "name").replaceFirst("\\s*\\(input:.*$", "").trim();
                help = attribute(declaration, "help").replaceFirst("\\s*\\(calls Ebi\\)\\s*$", "").trim();
                firstOutput = outputLabel;
            } else {
                if (params.size() != firstInputs.size()) throw new IllegalArgumentException("Variant argument count differs for " + command);
                for (int j = 0; j < params.size(); j++) {
                    EbiCommandMetadataParameter p = params.get(j), f = firstInputs.get(j);
                    if (p.isPort != f.isPort || (!p.isPort && !p.type.equals(f.type)))
                        throw new IllegalArgumentException("Variant argument order/type differs for " + command);
                    alternatives.get(j).addAll(p.supportedPortTypes);
                }
            }
            outputTypes.add(outPort);
            String format = switch (outPort) {
                case "PetriNetPortObject" -> ".pnml";
                case "ProcessTreePortObject" -> ".ptml";
                default -> unescape(call.group(2));
            };
            variants.add(new EbiCommandVariant(params.stream().filter(p -> p.isPort).map(p -> p.portType).toList(), outPort, format));
        }
        if (firstInputs == null) throw new IllegalArgumentException("No supported variants for " + command + ": " + rejected);
        List<EbiCommandMetadataParameter> merged = new ArrayList<>();
        for (int i = 0; i < firstInputs.size(); i++) {
            EbiCommandMetadataParameter p = firstInputs.get(i);
            List<String> allowed = sortedPorts(alternatives.get(i));
            merged.add(new EbiCommandMetadataParameter(p.type, p.isPort ? allowed.get(0) : "",
                p.typeDescription, p.isPort, allowed));
        }
        List<String> outputs = sortedPorts(outputTypes);
        return new EbiCommandMetadata(command, title, help, merged,
            new EbiCommandMetadataParameter(firstOutput, outputs.get(0), "", true, outputs), variants);
    }

    static String attribute(final String declaration, final String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(?:\\{\\s*)?" + QUOTED).matcher(declaration);
        if (!m.find()) throw new IllegalArgumentException("Missing @Plugin attribute: " + name);
        return unescape(m.group(1));
    }

    static String unescape(final String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') { result.append(c); continue; }
            if (++i == text.length()) throw new IllegalArgumentException("Incomplete Java string escape.");
            c = text.charAt(i);
            switch (c) {
                case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                case 'b' -> result.append('\b'); case 'f' -> result.append('\f'); case '"' -> result.append('"');
                case '\\' -> result.append('\\'); case '\'' -> result.append('\'');
                case 'u' -> {
                    if (i + 4 >= text.length()) throw new IllegalArgumentException("Incomplete unicode escape.");
                    result.append((char)Integer.parseInt(text.substring(i + 1, i + 5), 16)); i += 4;
                }
                default -> throw new IllegalArgumentException("Unsupported Java string escape: " + c);
            }
        }
        return result.toString();
    }

    public static List<String> sortedPorts(final Set<String> ports) {
        return ports.stream().sorted(Comparator.comparingInt(PORT_ORDER::indexOf)).toList();
    }
    public static boolean inputIsPrimitive(final String type) { return PRIMITIVES.contains(type); }
    public static String inputPortType(final String type) {
        return switch (type) {
            case "XLog" -> "XLogPortObject";
            case "AcceptingPetriNet", "PetriNet", "Petrinet" -> "PetriNetPortObject";
            case "EfficientTree", "ProcessTree" -> "ProcessTreePortObject";
            case "StochasticLabelledPetriNetSimpleWeights", "StochasticLabelledPetriNet" -> "EbiDocumentPortObject";
            case "DirectlyFollowsGraph", "DfgMsd" -> "DfgMsdPortObject";
            case "DirectlyFollowsModel" -> "DFMPortObject";
            case "BusinessProcessModelAndNotation", "BPMNDiagram" -> "BpmnPortObject";
            default -> throw new IllegalArgumentException("Unsupported Ebi Java object type: " + type);
        };
    }
    public static String outputPortType(final String type) {
        return EbiTableOutputType.isTableCompatible(type) ? "BufferedDataTable" : inputPortType(type);
    }
    public static String defaultOutputFormat(final String type, final String portType) {
        return switch (portType) {
            case "XLogPortObject" -> ".xes"; case "PetriNetPortObject" -> ".pnml";
            case "ProcessTreePortObject" -> ".ptml"; case "BpmnPortObject" -> ".bpmn";
            case "DfgMsdPortObject" -> ".dfg"; case "DFMPortObject" -> ".dfm";
            case "EbiDocumentPortObject" -> ".slpn";
            case "BufferedDataTable" -> switch (type.toLowerCase(Locale.ROOT)) {
                case "fraction" -> ".frac"; case "boolean" -> ".bool"; case "containsroot_html" -> ".croot";
                case "rootlogdiv" -> ".rldiv"; case "logdiv" -> ".logdiv"; case "string" -> ".txt";
                default -> throw new IllegalArgumentException("No verified scalar exporter for " + type);
            };
            default -> throw new IllegalArgumentException("Unsupported output port " + portType);
        };
    }
    @Override public String toString() { return commandName + " " + inputs + " -> " + outputFormats; }
}
