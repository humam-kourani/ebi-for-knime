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
import org.pm4knime.portobject.BpmnPortObject;
import org.pm4knime.portobject.DFMPortObject;
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

    @Test public void processTreeRetainsSupplementaryUnicodeThroughPromExporter() throws Exception {
        String xml = "<ptml><processTree root=\"a\"><manualTask id=\"a\" name=\"Ä &amp; 😀東\"/></processTree></ptml>";
        PortObject port = EbiPortAdapters.deserialize("ProcessTreePortObject", ".ptml", xml);
        String serialized = EbiPortAdapters.serialize(port);
        PortObject restored = EbiPortAdapters.deserialize("ProcessTreePortObject", ".ptml", serialized);
        assertEquals("Ä & 😀東", call(call(call(restored, "getTree"), "getRoot"), "getName"));
    }

    @Test public void dfmProducesCorrectUnweightedBoundaryIndices() throws Exception {
        PortObject port = EbiPortAdapters.deserialize("DFMPortObject", ".dfg", fixture("ebi-directly-follows.dfg"));
        assertTrue(port instanceof DFMPortObject);
        Object model = call(port, "getDfm");
        for (Object activity : (Object[])call(model, "getActivities")) assertEquals(4, call(activity, "size"));
        for (Object id : (Iterable<?>)call(model, "getDirectlyFollowsEdges"))
            assertEquals(4L, model.getClass().getMethod("getDirectlyFollowsEdgeCardinality", long.class).invoke(model, id));
        String nativeDfm = EbiPortAdapters.serialize(port);
        assertEquals("directly follows model\nfalse\n2\nA\nB\n1\n0\n1\n1\n1\n0>1\n", nativeDfm);
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
