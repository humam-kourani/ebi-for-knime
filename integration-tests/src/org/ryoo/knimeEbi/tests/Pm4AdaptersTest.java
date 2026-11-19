package org.ryoo.knimeEbi.tests;

import static org.junit.Assert.*;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.image.ImagePortObject;
import org.knime.core.node.port.image.ImagePortObjectSpec;
import org.knime.core.data.image.ImageValue;
import org.pm4knime.portobject.BpmnPortObject;
import org.pm4knime.portobject.DfgMsdPortObject;
import org.pm4knime.portobject.PetriNetPortObject;
import org.pm4knime.portobject.ProcessTreePortObject;
import org.pm4knime.portobject.XLogPortObject;
import org.ryoo.knimeEbi.port.EbiDocumentPortObject;
import org.ryoo.knimeEbi.util.DfgCodec;
import org.ryoo.knimeEbi.util.EbiPortAdapters;
import org.ryoo.knimeEbi.util.PnmlWriter;

/** Real PM4KNIME/ProM objects under Equinox; no substitute implementations. */
public class Pm4AdaptersTest {
    private static String fixture(final String name) throws Exception {
        try (InputStream in = Pm4AdaptersTest.class.getResourceAsStream("fixtures/" + name)) {
            assertNotNull("Missing fixture " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static Object call(final Object value, final String method) throws Exception {
        try { return value.getClass().getMethod(method).invoke(value); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception e) throw e;
            throw ex;
        }
    }
    private static Set<String> transitionLabels(final PortObject value) throws Exception {
        Object net = call(call(value, "getANet"), "getNet");
        Set<String> result = new HashSet<>();
        for (Object transition : (Collection<?>)call(net, "getTransitions")) result.add((String)call(transition, "getLabel"));
        return result;
    }

    @Test public void pnmlRoundTripRetainsUnicodeLabelsAndInitialMarking() throws Exception {
        PortObject port = EbiPortAdapters.deserialize("PetriNetPortObject", ".pnml", fixture("unicode.pnml"));
        assertTrue(port instanceof PetriNetPortObject);
        assertEquals(Set.of("Ä東", "B & C"), transitionLabels(port));
        assertEquals(1, ((Collection<?>)call(call(port, "getANet"), "getInitialMarking")).size());
        String xml = EbiPortAdapters.serialize(port);
        assertTrue(xml.contains("encoding=\"UTF-8\""));
        PortObject restored = EbiPortAdapters.deserialize("PetriNetPortObject", ".pnml", xml);
        assertEquals(transitionLabels(port), transitionLabels(restored));
        assertEquals(1, ((Collection<?>)call(call(restored, "getANet"), "getInitialMarking")).size());
    }

    @Test public void xesRoundTripRetainsTraceEventAndUnicode() throws Exception {
        String xes = """
            <?xml version="1.0" encoding="UTF-8"?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
              <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
              <trace><string key="concept:name" value="case-東"/>
                <event><string key="concept:name" value="Ä &amp; 東"/></event>
                <event><string key="concept:name" value="B"/></event>
              </trace>
            </log>
            """;
        PortObject port = EbiPortAdapters.deserialize("XLogPortObject", ".xes", xes);
        assertTrue(port instanceof XLogPortObject);
        List<?> log = (List<?>)call(port, "getLog");
        assertEquals(1, log.size()); assertEquals(2, ((List<?>)log.get(0)).size());
        String serialized = EbiPortAdapters.serialize(port);
        assertTrue(serialized.contains("case-東")); assertTrue(serialized.contains("Ä &amp; 東"));
        PortObject restored = EbiPortAdapters.deserialize("XLogPortObject", ".xes", serialized);
        assertEquals(1, ((List<?>)call(restored, "getLog")).size());
    }

    @Test public void pnmlRetainsArcMultiplicityInvisibleTransitionsAndFinalMarkings() throws Exception {
        String xml = PnmlWriter.write("Net & 東", List.of(new PnmlWriter.Place("p0", "start", 2),
            new PnmlWriter.Place("p1", "end", 0)), List.of(new PnmlWriter.Transition("t0", "τ & 東", true)),
            List.of(new PnmlWriter.Arc("p0", "t0", 2), new PnmlWriter.Arc("t0", "p1", 3)),
            List.of(Map.of("p1", 3)));
        PortObject port = EbiPortAdapters.deserialize("PetriNetPortObject", ".pnml", xml);
        for (int pass = 0; pass < 2; pass++) {
            Object anet = call(port, "getANet"); Object net = call(anet, "getNet");
            assertEquals(2, ((Collection<?>)call(anet, "getInitialMarking")).size());
            Collection<?> finals = (Collection<?>)call(anet, "getFinalMarkings"); assertEquals(1, finals.size());
            assertEquals(3, ((Collection<?>)finals.iterator().next()).size());
            Collection<?> transitions = (Collection<?>)call(net, "getTransitions"); assertEquals(1, transitions.size());
            assertEquals(true, call(transitions.iterator().next(), "isInvisible"));
            Set<Integer> weights = new HashSet<>();
            for (Object arc : (Collection<?>)call(net, "getEdges")) weights.add((Integer)call(arc, "getWeight"));
            assertEquals(Set.of(2, 3), weights);
            port = EbiPortAdapters.deserialize("PetriNetPortObject", ".pnml", EbiPortAdapters.serialize(port));
        }
    }

    @Test public void flowerRepairProducesTheNativeLoopLanguage() throws Exception {
        PortObject port = EbiPortAdapters.deserialize("ProcessTreePortObject", ".ptml", fixture("ebi-flower-broken.ptml"));
        assertTrue(port instanceof ProcessTreePortObject);
        Object root = call(call(port, "getTree"), "getRoot");
        assertEquals("XorLoop", root.getClass().getSimpleName());
        List<?> children = (List<?>)call(root, "getChildren");
        assertEquals(3, children.size());
        assertEquals("Automatic", children.get(0).getClass().getSimpleName());
        assertEquals("Xor", children.get(1).getClass().getSimpleName());
        assertEquals("Automatic", children.get(2).getClass().getSimpleName());
        Set<String> redoLabels = new HashSet<>();
        for (Object activity : (List<?>)call(children.get(1), "getChildren")) {
            assertEquals("Manual", activity.getClass().getSimpleName()); redoLabels.add((String)call(activity, "getName"));
        }
        assertEquals(Set.of("A", "B"), redoLabels);
        // Ebi's native tree is loop(tau, A, B): body tau, either activity as redo.
        assertTrue(fixture("ebi-flower-native.ptree").startsWith("process tree\nloop\n"));
        String ptml = EbiPortAdapters.serialize(port);
        PortObject restored = EbiPortAdapters.deserialize("ProcessTreePortObject", ".ptml", ptml);
        assertEquals("XorLoop", call(call(restored, "getTree"), "getRoot").getClass().getSimpleName());
    }

    @Test public void directlyFollowsRoundTripPreservesBoundaryDirectionAndWeights() throws Exception {
        String dfg = fixture("ebi-directly-follows.dfg");
        DfgCodec.Graph expected = DfgCodec.parse(dfg);
        assertEquals(List.of("A"), expected.starts()); assertEquals(List.of("B"), expected.ends());
        PortObject port = EbiPortAdapters.deserialize("DfgMsdPortObject", ".dfg", dfg);
        assertTrue(port instanceof DfgMsdPortObject);
        assertEquals(expected, DfgCodec.parse(EbiPortAdapters.serialize(port)));
    }

    @Test public void directlyFollowsReconstructsAndRoundTripsNonUnitBoundaryCounts() throws Exception {
        String dfg = "{\"activities\":{\"A\":\"3\",\"B\":\"2\",\"C\":\"3\"},"
            + "\"directly_follows_relations\":[[[\"A\",\"B\"],\"2\"],[[\"A\",\"C\"],\"1\"],[[\"B\",\"C\"],\"2\"]],"
            + "\"start_activities\":[\"A\"],\"end_activities\":[\"C\"]}";
        PortObject port = EbiPortAdapters.deserialize("DfgMsdPortObject", ".dfg", dfg);
        Object model = call(port, "getDfgMsd");
        String[] labels = (String[])call(model, "getAllActivities");
        int a = java.util.Arrays.asList(labels).indexOf("A");
        int c = java.util.Arrays.asList(labels).indexOf("C");
        Object starts = call(model, "getStartActivities");
        Object ends = call(model, "getEndActivities");
        assertEquals(3L, ((Number)starts.getClass().getMethod("getCardinalityOf", int.class).invoke(starts, a)).longValue());
        assertEquals(3L, ((Number)ends.getClass().getMethod("getCardinalityOf", int.class).invoke(ends, c)).longValue());
        var restored = DfgCodec.parse(EbiPortAdapters.serialize(port));
        assertEquals(Map.of("A", "3", "B", "2", "C", "3"), restored.activities());
        assertEquals(Set.of(new DfgCodec.Edge("A", "B", "2"), new DfgCodec.Edge("A", "C", "1"),
            new DfgCodec.Edge("B", "C", "2")), Set.copyOf(restored.edges()));
        assertEquals(Map.of("A", 3L), DfgCodec.boundaryCounts(restored).starts());
        assertEquals(Map.of("C", 3L), DfgCodec.boundaryCounts(restored).ends());
    }

    @Test public void directlyFollowsSelfLoopsAndMixedBoundariesSurviveBothDirections() throws Exception {
        String dfg = "{\"activities\":{\"A\":\"4\",\"B\":\"3\"},"
            + "\"directly_follows_relations\":[[[\"A\",\"A\"],\"1\"],[[\"A\",\"B\"],\"2\"],[[\"B\",\"A\"],\"1\"]],"
            + "\"start_activities\":[\"A\",\"B\"],\"end_activities\":[\"A\",\"B\"]}";
        PortObject port = EbiPortAdapters.deserialize("DfgMsdPortObject", ".dfg", dfg);
        Object model = call(port, "getDfgMsd");
        String[] labels = (String[])call(model, "getAllActivities");
        int a = java.util.Arrays.asList(labels).indexOf("A");
        int b = java.util.Arrays.asList(labels).indexOf("B");
        Object starts = call(model, "getStartActivities");
        Object ends = call(model, "getEndActivities");
        var startCount = starts.getClass().getMethod("getCardinalityOf", int.class);
        var endCount = ends.getClass().getMethod("getCardinalityOf", int.class);
        assertEquals(2L, ((Number)startCount.invoke(starts, a)).longValue());
        assertEquals(1L, ((Number)startCount.invoke(starts, b)).longValue());
        assertEquals(1L, ((Number)endCount.invoke(ends, a)).longValue());
        assertEquals(2L, ((Number)endCount.invoke(ends, b)).longValue());
        DfgCodec.Graph roundTrip = DfgCodec.parse(EbiPortAdapters.serialize(port));
        assertEquals(Map.of("A", "4", "B", "3"), roundTrip.activities());
        assertEquals(Set.of(new DfgCodec.Edge("A", "A", "1"), new DfgCodec.Edge("A", "B", "2"),
            new DfgCodec.Edge("B", "A", "1")), Set.copyOf(roundTrip.edges()));
        assertEquals(Map.of("A", 2L, "B", 1L), DfgCodec.boundaryCounts(roundTrip).starts());
        assertEquals(Map.of("A", 1L, "B", 2L), DfgCodec.boundaryCounts(roundTrip).ends());
    }

    @Test public void directlyFollowsReconstructsOccurrencesAfterPm4KnimeReload() throws Exception {
        // A self-loop contributes to both incoming and outgoing counts. PM4KNIME's
        // port importer can restore each activity with cardinality one.
        String dfg = "{\"activities\":{\"A\":\"18\",\"B\":\"12\",\"C\":\"12\"},"
            + "\"directly_follows_relations\":[[[\"A\",\"A\"],\"6\"],[[\"A\",\"B\"],\"12\"],[[\"B\",\"C\"],\"12\"]],"
            + "\"start_activities\":[\"A\"],\"end_activities\":[\"C\"]}";
        PortObject port = EbiPortAdapters.deserialize("DfgMsdPortObject", ".dfg", dfg);
        Object model = call(port, "getDfgMsd");
        String[] labels = (String[])call(model, "getAllActivities");
        Object counts = call(model, "getActivities");
        var remove = counts.getClass().getMethod("remove", int.class);
        var add = counts.getClass().getMethod("add", int.class, long.class);
        // Reproduce the loss of activity frequencies without changing edges or boundaries.
        for (int i = 0; i < labels.length; i++) {
            remove.invoke(counts, i);
            add.invoke(counts, i, 1L);
        }
        DfgCodec.Graph expected = DfgCodec.parse(dfg);
        DfgCodec.Graph actual = DfgCodec.parse(EbiPortAdapters.serialize(port));
        assertEquals(expected.activities(), actual.activities());
        assertEquals(Set.copyOf(expected.edges()), Set.copyOf(actual.edges()));
        assertEquals(expected.starts(), actual.starts());
        assertEquals(expected.ends(), actual.ends());
    }

    @Test public void directlyFollowsRejectsInconsistentPm4KnimeBoundaryCountsBeforeEbi() throws Exception {
        PortObject port = EbiPortAdapters.deserialize("DfgMsdPortObject", ".dfg", fixture("ebi-directly-follows.dfg"));
        Object model = call(port, "getDfgMsd");
        int a = java.util.Arrays.asList((String[])call(model, "getAllActivities")).indexOf("A");
        Object starts = call(model, "getStartActivities");
        starts.getClass().getMethod("add", int.class, long.class).invoke(starts, a, 1L);
        try {
            EbiPortAdapters.serialize(port);
            fail("Inconsistent boundary count must not be exported to Ebi.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("flow imbalance"));
        }
    }

    @Test public void csvSpecWarnsAboutPositionalDefaultsWithoutRejectingColumns() throws Exception {
        var unexpected = new org.knime.core.data.DataTableSpec(
            new org.knime.core.data.DataColumnSpecCreator("cost", org.knime.core.data.def.StringCell.TYPE).createSpec(),
            new org.knime.core.data.DataColumnSpecCreator("concept:name", org.knime.core.data.def.StringCell.TYPE).createSpec(),
            new org.knime.core.data.DataColumnSpecCreator("case:concept:name", org.knime.core.data.def.StringCell.TYPE).createSpec());
        List<String> warnings = EbiPortAdapters.csvHeaderWarnings(unexpected);
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("column 1 ('cost') as case ID"));
        assertTrue(warnings.get(1).contains("column 3 ('case:concept:name') as resource"));
        var standard = new org.knime.core.data.DataTableSpec(
            new org.knime.core.data.DataColumnSpecCreator("case:concept:name", org.knime.core.data.def.StringCell.TYPE).createSpec(),
            new org.knime.core.data.DataColumnSpecCreator("concept:name", org.knime.core.data.def.StringCell.TYPE).createSpec(),
            new org.knime.core.data.DataColumnSpecCreator("org:resource", org.knime.core.data.def.StringCell.TYPE).createSpec(),
            new org.knime.core.data.DataColumnSpecCreator("time:timestamp", org.knime.core.data.def.StringCell.TYPE).createSpec());
        assertTrue(EbiPortAdapters.csvHeaderWarnings(standard).isEmpty());
    }

    @Test public void processTreeRetainsSupplementaryUnicodeThroughPromExporter() throws Exception {
        String xml = "<ptml><processTree root=\"a\"><manualTask id=\"a\" name=\"Ä &amp; 😀東\"/></processTree></ptml>";
        PortObject port = EbiPortAdapters.deserialize("ProcessTreePortObject", ".ptml", xml);
        String serialized = EbiPortAdapters.serialize(port);
        PortObject restored = EbiPortAdapters.deserialize("ProcessTreePortObject", ".ptml", serialized);
        assertEquals("Ä & 😀東", call(call(call(restored, "getTree"), "getRoot"), "getName"));
    }

    @Test public void oldDfmPortIsNotAccepted() throws Exception {
        try {
            EbiPortAdapters.resolvePortType("DFMPortObject");
            fail("Deprecated DFM port is still offered.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported port type"));
        }
    }

    @Test public void fractionalGraphIsAvailableLosslesslyButNotSilentlyRounded() throws Exception {
        String dfg = fixture("ebi-directly-follows.dfg").replace("\"4\"", "\"6/5\"");
        PortObject raw = EbiPortAdapters.deserialize("EbiDocumentPortObject", ".dfg", dfg);
        assertTrue(raw instanceof EbiDocumentPortObject); assertEquals(dfg, EbiPortAdapters.serialize(raw));
        try { EbiPortAdapters.deserialize("DfgMsdPortObject", ".dfg", dfg); fail("Fractional weights were rounded."); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("integer counts")); }
    }

    @Test public void bpmnRoundTripRetainsDefinitionAndUnicode() throws Exception {
        String bpmn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
              <process id="p"><startEvent id="s"/><task id="a" name="Ä東"/><endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="a"/>
                <sequenceFlow id="f2" sourceRef="a" targetRef="e"/>
              </process>
            </definitions>
            """;
        PortObject port = EbiPortAdapters.deserialize("BpmnPortObject", ".bpmn", bpmn);
        assertTrue(port instanceof BpmnPortObject);
        assertEquals(bpmn, EbiPortAdapters.serialize(port));
    }

    @Test public void svgOutputCreatesARealKnimeImagePort() throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"32\">"
            + "<rect width=\"64\" height=\"32\"/></svg>";
        PortObject result = EbiPortAdapters.deserialize("ImagePortObject", ".svg", svg);
        assertTrue(result instanceof ImagePortObject);
        assertEquals("ImagePortObject", EbiPortAdapters.typeName(result));
        assertTrue(result.getSpec() instanceof ImagePortObjectSpec);
        assertEquals(((ImagePortObjectSpec)EbiPortAdapters.outputSpec(
            "ImagePortObject", ".svg", "Ebi visualise graph", "svg")).getDataType(),
            ((ImagePortObjectSpec)result.getSpec()).getDataType());
        var cell = ((ImagePortObject)result).toDataCell();
        assertTrue(cell instanceof ImageValue);
        assertTrue(((ImageValue)cell).getImageExtension().toLowerCase(java.util.Locale.ROOT).endsWith("svg"));
        try {
            EbiPortAdapters.deserialize("ImagePortObject", ".svg", "<ptml/>");
            fail("Accepted a non-SVG image.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Expected <svg>"));
        }
        try {
            EbiPortAdapters.deserialize("ImagePortObject", ".pdf", svg);
            fail("Accepted a non-SVG image format.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("SVG exporter"));
        }
    }

    /** Regression: Ebi's SVG 2 textPath and paint syntax must work with KNIME's Batik renderer. */
    @Test public void svg2OutputIsRenderSafeAndDocumentRemainsOriginal() throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"40\">"
            + "<defs><marker id=\"m\"><path d=\"M0 0L5 5\" fill=\"context-stroke\"/></marker></defs>"
            + "<path id=\"edge\" d=\"M0 10L100 10\" stroke=\"#000000ff\" fill=\"transparent\"/>"
            + "<text><textPath href=\"#edge\">Ebi label</textPath></text></svg>";
        var document = (EbiDocumentPortObject)EbiPortAdapters.deserialize("EbiDocumentPortObject", ".svg", svg);
        assertEquals("Ebi document must retain native SVG", svg, document.getText());
        var image = (ImagePortObject)EbiPortAdapters.deserialize("ImagePortObject", ".svg", svg);
        assertTrue(image.toDataCell() instanceof ImageValue);
    }

    @Test public void nativeSnfaSyntaxRepairMakesInitialStateNumeric() throws Exception {
        String text = fixture("ebi-snfa-broken.snfa");
        PortObject port = EbiPortAdapters.deserialize("EbiDocumentPortObject", ".snfa", text);
        String normalized = EbiPortAdapters.serialize(port);
        assertFalse(normalized.contains("AutomatonState("));
        assertTrue(normalized.startsWith("stochastic non-deterministic finite automaton\n# initial state\n0\n"));
        assertEquals(text.replace("AutomatonState(0)", "0"), normalized);
    }

    @Test public void wrongNativeFallbackIsRejectedBeforeModelImport() throws Exception {
        try { EbiPortAdapters.deserialize("PetriNetPortObject", ".pnml", "<ptml/>"); fail("Wrong format accepted."); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Expected <pnml>")); }
    }
}
