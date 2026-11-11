package org.ryoo.knimeEbi.scaffolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Versioned, generated and packaged command contracts; loading does not start JNI. */
public final class EbiCatalog {
    private EbiCatalog() { }
    private static final class Holder {
        static final Map<String, EbiCommandMetadata> COMMANDS = loadResource();
    }
    public static EbiCommandMetadata command(final String name) {
        EbiCommandMetadata m = Holder.COMMANDS.get(name);
        if (m == null) throw new IllegalArgumentException("Unknown generated Ebi command: " + name);
        return m;
    }
    public static Map<String, EbiCommandMetadata> commands() { return Holder.COMMANDS; }
    private static Map<String, EbiCommandMetadata> loadResource() {
        try (InputStream in = EbiCatalog.class.getResourceAsStream("commands.xml")) {
            if (in == null) throw new IOException("Packaged Ebi command catalog is missing.");
            return read(in);
        } catch (Exception ex) { throw new IllegalStateException("Cannot load Ebi command catalog.", ex); }
    }
    public static Map<String, EbiCommandMetadata> read(final InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        Element root = f.newDocumentBuilder().parse(in).getDocumentElement();
        if (!root.getTagName().equals("ebi-commands") || !root.getAttribute("schema").equals("1"))
            throw new IllegalArgumentException("Unsupported Ebi catalog schema.");
        Map<String, EbiCommandMetadata> result = new LinkedHashMap<>();
        for (Element command : children(root, "command")) {
            List<EbiCommandMetadataParameter> inputs = new ArrayList<>();
            for (Element input : children(command, "input")) inputs.add(parameter(input));
            List<Element> outputs = children(command, "output");
            if (outputs.size() != 1) throw new IllegalArgumentException("Expected exactly one output declaration.");
            List<EbiCommandVariant> variants = new ArrayList<>();
            for (Element variant : children(command, "variant")) {
                variants.add(new EbiCommandVariant(split(variant.getAttribute("inputs")),
                    variant.getAttribute("output"), variant.getAttribute("format")));
            }
            EbiCommandMetadata m = new EbiCommandMetadata(command.getAttribute("name"),
                singleText(command, "title"), singleText(command, "description"), inputs, parameter(outputs.get(0)), variants);
            if (result.putIfAbsent(m.commandName, m) != null) throw new IllegalArgumentException("Duplicate command " + m.commandName);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty Ebi catalog.");
        return Collections.unmodifiableMap(result);
    }
    public static Map<String, EbiCommandMetadata> read(final String xml) throws Exception {
        return read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    private static EbiCommandMetadataParameter parameter(final Element e) {
        return new EbiCommandMetadataParameter(e.getAttribute("type"), e.getAttribute("default-port"),
            e.getTextContent(), Boolean.parseBoolean(e.getAttribute("port")), split(e.getAttribute("ports")), split(e.getAttribute("documents")));
    }
    private static List<String> split(final String value) { return value.isBlank() ? List.of() : List.of(value.split(",")); }
    private static String singleText(final Element e, final String tag) {
        List<Element> values = children(e, tag);
        if (values.size() != 1) throw new IllegalArgumentException("Expected one " + tag);
        return values.get(0).getTextContent();
    }
    private static List<Element> children(final Element e, final String tag) {
        List<Element> result = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element child && child.getTagName().equals(tag)) result.add(child);
        return result;
    }
    public static String write(final List<EbiCommandMetadata> commands) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- Generated from pinned Ebi Java metadata and native manual. Do not hand-edit. -->\n<ebi-commands schema=\"1\">\n");
        for (EbiCommandMetadata m : commands) {
            out.append("  <command name=\"").append(xml(m.commandName)).append("\">\n");
            out.append("    <title>").append(xml(m.shortDescription)).append("</title>\n");
            out.append("    <description>").append(xml(m.fullDescription)).append("</description>\n");
            for (EbiCommandMetadataParameter input : m.inputs) appendParameter(out, "input", input);
            appendParameter(out, "output", m.output);
            for (EbiCommandVariant v : m.variants) out.append("    <variant inputs=\"")
                .append(xml(String.join(",", v.inputPortTypes()))).append("\" output=\"")
                .append(xml(v.outputPortType())).append("\" format=\"").append(xml(v.outputFormat())).append("\"/>\n");
            out.append("  </command>\n");
        }
        return out.append("</ebi-commands>\n").toString();
    }
    private static void appendParameter(final StringBuilder out, final String tag, final EbiCommandMetadataParameter p) {
        out.append("    <").append(tag).append(" type=\"").append(xml(p.type)).append("\" default-port=\"")
            .append(xml(p.portType)).append("\" port=\"").append(p.isPort).append("\" ports=\"")
            .append(xml(String.join(",", p.supportedPortTypes))).append("\" documents=\"")
            .append(xml(String.join(",", p.acceptedDocumentFormats))).append("\">").append(xml(p.typeDescription))
            .append("</").append(tag).append(">\n");
    }
    static String xml(final String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
