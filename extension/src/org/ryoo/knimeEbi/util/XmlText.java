package org.ryoo.knimeEbi.util;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/** Checks text XML before handing it to third-party model readers. */
public final class XmlText {
    private XmlText() { }

    /** ProM PTML escapes UTF-16 code units separately; combine only valid surrogate pairs. */
    public static String repairPm4SurrogateReferences(final String xml) {
        final var pair = java.util.regex.Pattern.compile("&#([0-9]{5});&#([0-9]{5});").matcher(xml);
        StringBuilder repaired = new StringBuilder();
        while (pair.find()) {
            int high = Integer.parseInt(pair.group(1)), low = Integer.parseInt(pair.group(2));
            if (high >= Character.MIN_HIGH_SURROGATE && high <= Character.MAX_HIGH_SURROGATE
                    && low >= Character.MIN_LOW_SURROGATE && low <= Character.MAX_LOW_SURROGATE) {
                pair.appendReplacement(repaired, java.util.regex.Matcher.quoteReplacement(
                    new String(Character.toChars(Character.toCodePoint((char)high, (char)low)))));
            } else {
                pair.appendReplacement(repaired, java.util.regex.Matcher.quoteReplacement(pair.group()));
            }
        }
        pair.appendTail(repaired);
        return repaired.toString();
    }

    public static String utf8(final String xml, final String expectedRoot) {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("Empty " + expectedRoot + " document.");
        final XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        if (factory.isPropertySupported(XMLConstants.ACCESS_EXTERNAL_DTD))
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        boolean rootFound = false;
        try {
            final XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                        throw new IllegalArgumentException("DTD and entity declarations are not allowed in model XML.");
                    if (!rootFound && event == XMLStreamConstants.START_ELEMENT) {
                        if (!expectedRoot.equals(reader.getLocalName()))
                            throw new IllegalArgumentException("Expected <" + expectedRoot + ">, received <"
                                + reader.getLocalName() + ">. Ebi may have fallen back to another output format.");
                        rootFound = true;
                    }
                }
            } finally { reader.close(); }
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid " + expectedRoot + " XML.", ex); }
        if (!rootFound) throw new IllegalArgumentException("Missing " + expectedRoot + " document root.");
        return xml.replaceFirst("(?s)^\\s*<\\?xml[^?]*\\?>", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    }
}
