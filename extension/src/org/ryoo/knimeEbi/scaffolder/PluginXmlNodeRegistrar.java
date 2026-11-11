package org.ryoo.knimeEbi.scaffolder;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Synchronises the owned node entries without erasing unrelated extension registrations. */
public final class PluginXmlNodeRegistrar {
    public static final String GENERATED_PACKAGE = "org.ryoo.knimeEbi.node.";
    public static final String CATEGORY = "/community/processmining/ebi";
    private PluginXmlNodeRegistrar() { }
    public static String synchronize(final String xml, final List<String> factories) throws Exception {
        if (new LinkedHashSet<>(factories).size() != factories.size()) throw new IllegalArgumentException("Duplicate factory registrations.");
        for (String name : factories) {
            if (!name.startsWith(GENERATED_PACKAGE) || !JavaSourceUtil.isJavaIdentifier(name.substring(GENERATED_PACKAGE.length())))
                throw new IllegalArgumentException("Invalid generated factory: " + name);
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        Element plugin = doc.getDocumentElement();
        if (!plugin.getTagName().equals("plugin")) throw new IllegalArgumentException("Expected plugin root.");
        removeWhitespace(plugin);
        Element extension = null;
        List<Node> remove = new ArrayList<>();
        for (int i = 0; i < doc.getElementsByTagName("extension").getLength(); i++) {
            Element e = (Element)doc.getElementsByTagName("extension").item(i);
            if (!e.getAttribute("point").equals("org.knime.workbench.repository.nodes")) continue;
            if (extension == null) extension = e;
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof Element child && child.getTagName().equals("node")
                        && child.getAttribute("factory-class").startsWith(GENERATED_PACKAGE)) remove.add(child);
        }
        for (Node n : remove) n.getParentNode().removeChild(n);
        if (extension == null) { extension = doc.createElement("extension"); extension.setAttribute("point", "org.knime.workbench.repository.nodes"); plugin.appendChild(extension); }
        for (String factory : factories.stream().sorted().toList()) {
            Element node = doc.createElement("node"); node.setAttribute("category-path", CATEGORY);
            node.setAttribute("factory-class", factory); extension.appendChild(node);
        }
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = tf.newTransformer(); transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8"); transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter writer = new StringWriter(); transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString().replace("\r\n", "\n");
    }
    private static void removeWhitespace(final Node n) {
        for (Node child = n.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) n.removeChild(child);
            else removeWhitespace(child);
            child = next;
        }
    }
    /** Kept for old callers; new generation uses a complete batch. */
    public static void register(final String factory) throws java.io.IOException {
        Path file = Path.of("plugin.xml");
        try {
            String xml = Files.readString(file); List<String> factories = new ArrayList<>();
            var pattern = java.util.regex.Pattern.compile("factory-class=\"(" + java.util.regex.Pattern.quote(GENERATED_PACKAGE) + "[^\"]+)\"").matcher(xml);
            while (pattern.find()) factories.add(pattern.group(1));
            if (!factories.contains(factory)) factories.add(factory);
            Files.writeString(file, synchronize(xml, factories), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new java.io.IOException("Cannot register " + factory, ex); }
    }
}
