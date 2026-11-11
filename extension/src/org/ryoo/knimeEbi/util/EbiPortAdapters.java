package org.ryoo.knimeEbi.util;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.def.StringCell;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.pm4knime.portobject.*;
import org.ryoo.knimeEbi.port.EbiDocumentPortObject;
import org.ryoo.knimeEbi.port.EbiDocumentPortObjectSpec;

/** Conversions are explicit, typed and checked for format/representation loss. */
public final class EbiPortAdapters {
    private EbiPortAdapters() { }
    public static PortType resolvePortType(final String name) {
        return switch (name) {
            case "XLogPortObject" -> XLogPortObject.TYPE;
            case "PetriNetPortObject" -> PetriNetPortObject.TYPE;
            case "ProcessTreePortObject" -> ProcessTreePortObject.TYPE;
            case "DfgMsdPortObject" -> DfgMsdPortObject.TYPE;
            case "DFMPortObject" -> DFMPortObject.TYPE;
            case "BpmnPortObject" -> BpmnPortObject.TYPE;
            case "EbiDocumentPortObject" -> EbiDocumentPortObject.TYPE;
            case "BufferedDataTable" -> BufferedDataTable.TYPE;
            default -> throw new IllegalArgumentException("Unsupported port type: " + name);
        };
    }
    public static String typeName(final PortType type) {
        for (String name : org.ryoo.knimeEbi.scaffolder.EbiCommandMetadata.PORT_ORDER)
            if (resolvePortType(name).equals(type)) return name;
        throw new IllegalArgumentException("Unsupported Ebi port type: " + type);
    }
    public static String typeName(final PortObjectSpec spec) {
        if (spec == null) throw new IllegalArgumentException("Connect every required Ebi input port.");
        if (spec instanceof XLogPortObjectSpec) return "XLogPortObject";
        if (spec instanceof PetriNetPortObjectSpec) return "PetriNetPortObject";
        if (spec instanceof ProcessTreePortObjectSpec) return "ProcessTreePortObject";
        if (spec instanceof DfgMsdPortObjectSpec) return "DfgMsdPortObject";
        if (spec instanceof DFMPortObjectSpec) return "DFMPortObject";
        if (spec instanceof BpmnPortObjectSpec) return "BpmnPortObject";
        if (spec instanceof EbiDocumentPortObjectSpec) return "EbiDocumentPortObject";
        if (spec instanceof DataTableSpec) return "BufferedDataTable";
        throw new IllegalArgumentException("Unsupported Ebi input specification: " + spec.getClass().getName());
    }
    public static String typeName(final PortObject object) {
        if (object == null) throw new IllegalArgumentException("Missing required Ebi input data.");
        if (object instanceof XLogPortObject) return "XLogPortObject";
        if (object instanceof PetriNetPortObject) return "PetriNetPortObject";
        if (object instanceof ProcessTreePortObject) return "ProcessTreePortObject";
        if (object instanceof DfgMsdPortObject) return "DfgMsdPortObject";
        if (object instanceof DFMPortObject) return "DFMPortObject";
        if (object instanceof BpmnPortObject) return "BpmnPortObject";
        if (object instanceof EbiDocumentPortObject) return "EbiDocumentPortObject";
        if (object instanceof BufferedDataTable) return "BufferedDataTable";
        throw new IllegalArgumentException("Unsupported Ebi input data: " + object.getClass().getName());
    }
    public static PortObjectSpec outputSpec(String portType, String format, String command, String label) {
        return switch (portType) {
            case "XLogPortObject" -> new XLogPortObjectSpec();
            case "PetriNetPortObject" -> new PetriNetPortObjectSpec();
            case "ProcessTreePortObject" -> new ProcessTreePortObjectSpec();
            case "DfgMsdPortObject" -> new DfgMsdPortObjectSpec();
            case "DFMPortObject" -> new DFMPortObjectSpec();
            case "BpmnPortObject" -> new BpmnPortObjectSpec();
            case "EbiDocumentPortObject" -> new EbiDocumentPortObjectSpec(format);
            case "BufferedDataTable" -> TableUtil.createOutputSpec(command, label, StringCell.TYPE);
            default -> throw new IllegalArgumentException("Unsupported Ebi output: " + portType);
        };
    }

    public static String serialize(final PortObject port) throws Exception {
        if (port instanceof EbiDocumentPortObject doc) return doc.getText();
        if (port instanceof XLogPortObject) return XESUtil.writeLogToXesString(port);
        if (port instanceof ProcessTreePortObject tree) {
            if (Pm4Reflection.call(tree, "getTree") == null) throw new IllegalArgumentException("The process-tree port is empty.");
            return XmlText.utf8(XmlText.repairPm4SurrogateReferences(tree.toText()), "ptml");
        }
        if (port instanceof PetriNetPortObject) return writePetriNet(port);
        if (port instanceof BpmnPortObject bpmn) return XmlText.utf8(bpmn.getBPMN(), "definitions");
        if (port instanceof DfgMsdPortObject) return DfgCodec.write(readDfgMsd(port));
        if (port instanceof DFMPortObject) return writeDfm(port);
        throw new IllegalArgumentException("No Ebi serializer for " + port.getClass().getName());
    }

    private static String writePetriNet(Object port) throws Exception {
        Object anet = Pm4Reflection.call(port, "getANet");
        if (anet == null) throw new IllegalArgumentException("The Petri-net port is empty.");
        Object net = Pm4Reflection.call(anet, "getNet");
        Object initial = Pm4Reflection.call(anet, "getInitialMarking");
        Map<Object, String> ids = new java.util.IdentityHashMap<>();
        List<PnmlWriter.Place> places = new ArrayList<>();
        List<PnmlWriter.Transition> transitions = new ArrayList<>();
        List<PnmlWriter.Arc> arcs = new ArrayList<>();
        List<Object> placeObjects = new ArrayList<>();
        int index = 0;
        for (Object place : iterable(Pm4Reflection.call(net, "getPlaces"))) {
            String id = "p" + index++; ids.put(place, id); placeObjects.add(place);
            places.add(new PnmlWriter.Place(id, (String)Pm4Reflection.call(place, "getLabel"),
                ((Number)Pm4Reflection.call(initial, "occurrences", place)).intValue()));
        }
        index = 0;
        for (Object transition : iterable(Pm4Reflection.call(net, "getTransitions"))) {
            String id = "t" + index++; ids.put(transition, id);
            transitions.add(new PnmlWriter.Transition(id, (String)Pm4Reflection.call(transition, "getLabel"),
                (Boolean)Pm4Reflection.call(transition, "isInvisible")));
        }
        Class<?> ordinaryArc = Pm4Reflection.type("org.processmining.models.graphbased.directed.petrinet.elements.Arc");
        for (Object arc : iterable(Pm4Reflection.call(net, "getEdges"))) {
            if (!ordinaryArc.isInstance(arc)) throw new IllegalArgumentException("Ebi PNML does not support reset or inhibitor arcs.");
            arcs.add(new PnmlWriter.Arc(ids.get(Pm4Reflection.call(arc, "getSource")),
                ids.get(Pm4Reflection.call(arc, "getTarget")), ((Number)Pm4Reflection.call(arc, "getWeight")).intValue()));
        }
        List<Map<String, Integer>> finals = new ArrayList<>();
        for (Object marking : iterable(Pm4Reflection.call(anet, "getFinalMarkings"))) {
            Map<String, Integer> tokens = new LinkedHashMap<>();
            for (Object place : placeObjects) {
                int count = ((Number)Pm4Reflection.call(marking, "occurrences", place)).intValue();
                if (count > 0) tokens.put(ids.get(place), count);
            }
            finals.add(tokens);
        }
        return PnmlWriter.write((String)Pm4Reflection.call(net, "getLabel"), places, transitions, arcs, finals);
    }

    public static PortObject deserialize(final String portType, final String format, final String text) throws Exception {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Ebi returned no model data.");
        return switch (portType) {
            case "EbiDocumentPortObject" -> new EbiDocumentPortObject(format, EbiNativeOutputCompatibility.normalize(format, text));
            case "BpmnPortObject" -> new BpmnPortObject(XmlText.utf8(text, "definitions"));
            case "ProcessTreePortObject" -> {
                String repaired = PtmlCompatibilityUtil.toPm4KnimePtml(XmlText.utf8(text, "ptml"));
                ProcessTreePortObject result = new ProcessTreePortObject();
                result.loadFromDefault(new ProcessTreePortObjectSpec(), utf8(repaired));
                if (Pm4Reflection.call(result, "getTree") == null) throw new IllegalArgumentException("PM4KNIME did not load the process tree.");
                yield result;
            }
            case "PetriNetPortObject" -> {
                String xml = XmlText.utf8(text, "pnml");
                Object anet = Pm4Reflection.call(Pm4Reflection.type("org.pm4knime.util.PetriNetUtil"), "importFromStream", utf8(xml));
                if (anet == null) throw new IllegalArgumentException("PM4KNIME rejected Ebi's PNML result.");
                PetriNetPortObject result = new PetriNetPortObject(); Pm4Reflection.call(result, "setANet", anet); yield result;
            }
            case "XLogPortObject" -> {
                String xml = XmlText.utf8(text, "log");
                Object parser = Pm4Reflection.create("org.deckfour.xes.in.XesXmlParser");
                Object parsed = Pm4Reflection.call(parser, "parse", utf8(xml));
                if (!(parsed instanceof List<?> logs) || logs.size() != 1 || logs.get(0) == null)
                    throw new IllegalArgumentException("Ebi must return exactly one XES event log.");
                XLogPortObject result = new XLogPortObject(); Pm4Reflection.call(result, "setLog", logs.get(0)); yield result;
            }
            case "DfgMsdPortObject" -> toDfgMsd(DfgCodec.parse(text));
            case "DFMPortObject" -> toDfm(DfgCodec.parse(text));
            default -> throw new IllegalArgumentException("No model adapter for " + portType);
        };
    }
    private static ByteArrayInputStream utf8(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }

    private static DfgCodec.Graph readDfgMsd(Object port) throws Exception {
        Object model = Pm4Reflection.call(port, "getDfgMsd");
        if (model == null) throw new IllegalArgumentException("The directly-follows graph port is empty.");
        if (((Number)Pm4Reflection.call(model, "getNumberOfEmptyTraces")).longValue() != 0)
            throw new IllegalArgumentException("Ebi .dfg cannot retain empty-trace counts. Use the original event log or a native Ebi document.");
        String[] labels = (String[])Pm4Reflection.call(model, "getAllActivities");
        Map<String, String> activities = new LinkedHashMap<>();
        Object counts = Pm4Reflection.call(model, "getActivities");
        for (Object index : iterable(counts)) {
            int i = ((Number)index).intValue();
            if (activities.putIfAbsent(labels[i], String.valueOf(Pm4Reflection.call(counts, "getCardinalityOf", i))) != null)
                throw new IllegalArgumentException("Ebi .dfg cannot merge distinct activity nodes with identical labels.");
        }
        Object edges = Pm4Reflection.call(model, "getDirectlyFollowsGraph");
        List<DfgCodec.Edge> resultEdges = new ArrayList<>();
        for (Object edge : iterable(Pm4Reflection.call(edges, "getEdges"))) {
            long id = ((Number)edge).longValue(); long weight = ((Number)Pm4Reflection.call(edges, "getEdgeWeight", id)).longValue();
            if (weight <= 0) continue;
            int source = ((Number)Pm4Reflection.call(edges, "getEdgeSource", id)).intValue();
            int target = ((Number)Pm4Reflection.call(edges, "getEdgeTarget", id)).intValue();
            resultEdges.add(new DfgCodec.Edge(labels[source], labels[target], String.valueOf(weight)));
        }
        return new DfgCodec.Graph(activities, resultEdges, boundaryLabels(model, "getStartActivities", labels), boundaryLabels(model, "getEndActivities", labels));
    }
    private static List<String> boundaryLabels(Object model, String method, String[] labels) throws Exception {
        List<String> result = new ArrayList<>();
        for (Object index : iterable(Pm4Reflection.call(model, method))) result.add(labels[((Number)index).intValue()]);
        return result;
    }
    private static Iterable<?> iterable(Object object) {
        if (!(object instanceof Iterable<?> result)) throw new IllegalArgumentException("PM4KNIME object is not iterable.");
        return result;
    }

    private static PortObject toDfgMsd(DfgCodec.Graph graph) throws Exception {
        String[] labels = graph.activities().keySet().toArray(String[]::new);
        Object model = Pm4Reflection.create("org.processmining.plugins.inductiveminer2.withoutlog.dfgmsd.DfgMsdImpl", (Object)labels);
        Map<String, Integer> index = new LinkedHashMap<>();
        Object counts = Pm4Reflection.call(model, "getActivities");
        for (int i = 0; i < labels.length; i++) {
            index.put(labels[i], i); Pm4Reflection.call(model, "touchActivity", i);
            long count = DfgCodec.exactCount(graph.activities().get(labels[i]));
            if (count == 0) throw new IllegalArgumentException("PM4KNIME DfgMsd cannot retain a zero-count activity. Select Ebi document output.");
            Pm4Reflection.call(counts, "add", i, count);
        }
        for (String name : graph.starts()) Pm4Reflection.call(Pm4Reflection.call(model, "getStartActivities"), "add", index.get(name), 1L);
        for (String name : graph.ends()) Pm4Reflection.call(Pm4Reflection.call(model, "getEndActivities"), "add", index.get(name), 1L);
        for (DfgCodec.Edge edge : graph.edges()) Pm4Reflection.call(Pm4Reflection.call(model, "getDirectlyFollowsGraph"), "addEdge", index.get(edge.source()), index.get(edge.target()), DfgCodec.exactCount(edge.weight()));
        DfgMsdPortObject result = new DfgMsdPortObject(); Pm4Reflection.call(result, "setDfgMsd", model); return result;
    }
    private static PortObject toDfm(DfgCodec.Graph graph) throws Exception {
        Object model = Pm4Reflection.create("org.processmining.plugins.InductiveMiner.dfgOnly.DfgImpl");
        Map<String, Object> activities = new LinkedHashMap<>(); int index = 0;
        for (Map.Entry<String, String> entry : graph.activities().entrySet()) {
            Object activity = Pm4Reflection.create("org.deckfour.xes.classification.XEventClass", entry.getKey(), index++);
            long count = DfgCodec.exactCount(entry.getValue());
            if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("DFM activity counts exceed the PM4KNIME int range; choose Ebi document output.");
            Pm4Reflection.call(activity, "setSize", (int)count); Pm4Reflection.call(model, "addActivity", activity); activities.put(entry.getKey(), activity);
        }
        for (String start : graph.starts()) Pm4Reflection.call(model, "addStartActivity", activities.get(start), 1L);
        for (String end : graph.ends()) Pm4Reflection.call(model, "addEndActivity", activities.get(end), 1L);
        for (DfgCodec.Edge edge : graph.edges()) Pm4Reflection.call(model, "addDirectlyFollowsEdge", activities.get(edge.source()), activities.get(edge.target()), DfgCodec.exactCount(edge.weight()));
        DFMPortObject result = new DFMPortObject(); Pm4Reflection.call(result, "setDfm", model); return result;
    }
    private static String writeDfm(Object port) throws Exception {
        Object model = Pm4Reflection.call(port, "getDfm");
        if (model == null) throw new IllegalArgumentException("The directly-follows model port is empty.");
        int[] indices = (int[])Pm4Reflection.call(model, "getActivityIndices");
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder("directly follows model\n")
            .append(((Number)Pm4Reflection.call(model, "getNumberOfEmptyTraces")).longValue() > 0).append('\n').append(indices.length).append('\n');
        for (int i = 0; i < indices.length; i++) {
            remap.put(indices[i], i);
            String name = (String)Pm4Reflection.call(Pm4Reflection.call(model, "getActivityOfIndex", indices[i]), "getId");
            // The pinned Ebi line reader cannot safely represent comment-prefixed labels.
            if (name.isBlank() || name.startsWith("#") || name.startsWith("$") || name.contains("\n") || name.contains("\r") || !name.equals(name.strip()))
                throw new IllegalArgumentException("This DFM activity label cannot be safely encoded by the pinned Ebi line format. Use an event log or Ebi document: " + name);
            out.append(name).append('\n');
        }
        for (String boundary : List.of("getStartActivityIndices", "getEndActivityIndices")) {
            int[] boundaryIndices = (int[])Pm4Reflection.call(model, boundary); out.append(boundaryIndices.length).append('\n');
            for (int i : boundaryIndices) out.append(remap.get(i)).append('\n');
        }
        List<String> edges = new ArrayList<>();
        for (Object edge : iterable(Pm4Reflection.call(model, "getDirectlyFollowsEdges"))) {
            long id = ((Number)edge).longValue();
            if (((Number)Pm4Reflection.call(model, "getDirectlyFollowsEdgeCardinality", id)).longValue() <= 0) continue;
            int source = ((Number)Pm4Reflection.call(model, "getDirectlyFollowsEdgeSourceIndex", id)).intValue();
            int target = ((Number)Pm4Reflection.call(model, "getDirectlyFollowsEdgeTargetIndex", id)).intValue();
            edges.add(remap.get(source) + ">" + remap.get(target));
        }
        out.append(edges.size()).append('\n'); for (String edge : edges) out.append(edge).append('\n');
        return out.toString();
    }
}
