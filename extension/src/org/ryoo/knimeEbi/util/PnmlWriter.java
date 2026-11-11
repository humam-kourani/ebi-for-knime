package org.ryoo.knimeEbi.util;

import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

/** Writes ordinary weighted P/T nets with escaped Unicode names and all markings. */
public final class PnmlWriter {
    public record Place(String id, String label, int initialTokens) { }
    public record Transition(String id, String label, boolean invisible) { }
    public record Arc(String source, String target, int weight) { }
    private PnmlWriter() { }

    public static String write(String name, List<Place> places, List<Transition> transitions,
            List<Arc> arcs, List<Map<String, Integer>> finalMarkings) throws Exception {
        Set<String> placeIds = new HashSet<>(), transitionIds = new HashSet<>();
        for (Place place : places) {
            if (place.id() == null || place.id().isBlank() || !placeIds.add(place.id()) || place.initialTokens() < 0)
                throw new IllegalArgumentException("Invalid PNML place or initial marking.");
        }
        for (Transition transition : transitions) {
            if (transition.id() == null || transition.id().isBlank() || placeIds.contains(transition.id()) || !transitionIds.add(transition.id()))
                throw new IllegalArgumentException("Invalid PNML transition identifier.");
        }
        for (Arc arc : arcs) {
            boolean bipartite = placeIds.contains(arc.source()) && transitionIds.contains(arc.target())
                || transitionIds.contains(arc.source()) && placeIds.contains(arc.target());
            if (!bipartite || arc.weight() <= 0) throw new IllegalArgumentException("Invalid PNML arc or weight.");
        }
        for (Map<String, Integer> marking : finalMarkings) for (Map.Entry<String, Integer> entry : marking.entrySet())
            if (!placeIds.contains(entry.getKey()) || entry.getValue() < 0) throw new IllegalArgumentException("Invalid PNML final marking.");
        StringWriter text = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(text);
        try {
            writer.writeStartDocument("UTF-8", "1.0"); writer.writeStartElement("pnml");
            writer.writeStartElement("net"); writer.writeAttribute("id", "ebi-net");
            writer.writeAttribute("type", "http://www.pnml.org/version-2009/grammar/ptnet");
            annotation(writer, "name", name == null ? "" : name);
            writer.writeStartElement("page"); writer.writeAttribute("id", "page0");
            for (Place place : places) {
                writer.writeStartElement("place"); writer.writeAttribute("id", place.id());
                annotation(writer, "name", place.label() == null ? "" : place.label());
                if (place.initialTokens() > 0) annotation(writer, "initialMarking", Integer.toString(place.initialTokens()));
                writer.writeEndElement();
            }
            for (Transition transition : transitions) {
                writer.writeStartElement("transition"); writer.writeAttribute("id", transition.id());
                annotation(writer, "name", transition.label() == null ? "" : transition.label());
                if (transition.invisible()) {
                    writer.writeEmptyElement("toolspecific"); writer.writeAttribute("tool", "ProM");
                    writer.writeAttribute("version", "6.4"); writer.writeAttribute("activity", "$invisible$");
                }
                writer.writeEndElement();
            }
            int index = 0;
            for (Arc arc : arcs) {
                writer.writeStartElement("arc"); writer.writeAttribute("id", "a" + index++);
                writer.writeAttribute("source", arc.source()); writer.writeAttribute("target", arc.target());
                annotation(writer, "inscription", Integer.toString(arc.weight())); writer.writeEndElement();
            }
            writer.writeEndElement(); // page
            if (!finalMarkings.isEmpty()) {
                writer.writeStartElement("finalmarkings");
                for (Map<String, Integer> marking : finalMarkings) {
                    writer.writeStartElement("marking");
                    for (Map.Entry<String, Integer> entry : marking.entrySet()) {
                        if (entry.getValue() == 0) continue;
                        writer.writeStartElement("place"); writer.writeAttribute("idref", entry.getKey());
                        writer.writeStartElement("text"); writer.writeCharacters(entry.getValue().toString());
                        writer.writeEndElement(); writer.writeEndElement();
                    }
                    writer.writeEndElement();
                }
                writer.writeEndElement();
            }
            writer.writeEndElement(); writer.writeEndElement(); writer.writeEndDocument(); writer.flush();
        } finally { writer.close(); }
        return XmlText.utf8(text.toString(), "pnml");
    }
    private static void annotation(XMLStreamWriter writer, String tag, String value) throws Exception {
        writer.writeStartElement(tag); writer.writeStartElement("text"); writer.writeCharacters(value);
        writer.writeEndElement(); writer.writeEndElement();
    }
}
