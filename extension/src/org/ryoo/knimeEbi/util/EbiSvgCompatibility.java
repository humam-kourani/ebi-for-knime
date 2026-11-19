package org.ryoo.knimeEbi.util;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Adapts Ebi's SVG 2 output to the SVG features supported by KNIME's Batik renderer. */
final class EbiSvgCompatibility {
    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    private EbiSvgCompatibility() { }

    /** Only use for KNIME image ports; Ebi document ports retain their original SVG bytes. */
    static String forKnime(final String svg) {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
            Element root = document.getDocumentElement();
            if (!SVG_NS.equals(root.getNamespaceURI()) || !"svg".equals(root.getLocalName()))
                throw new IllegalArgumentException("Expected a namespaced SVG document.");

            var elements = document.getElementsByTagName("*");
            boolean usesXlink = false;
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element)elements.item(i);
                for (String paint : new String[] {"fill", "stroke"}) adaptPaint(element, paint);
                if (SVG_NS.equals(element.getNamespaceURI()) && "textPath".equals(element.getLocalName())) {
                    // Batik 1.x uses xlink:href for textPath. A bare SVG 2 href is read as
                    // an empty URI, which can crash URI parsing when the document has no base URI.
                    String href = element.getAttribute("href");
                    String reference = element.hasAttributeNS(XLINK_NS, "href")
                        ? element.getAttributeNS(XLINK_NS, "href") : href;
                    if (!reference.startsWith("#") || reference.length() == 1)
                        throw new IllegalArgumentException("SVG textPath must reference a local path (e.g. #arrow0).");
                    if (!href.isEmpty() && !element.hasAttributeNS(XLINK_NS, "href")) {
                        element.setAttributeNS(XLINK_NS, "xlink:href", href);
                        element.removeAttribute("href");
                        usesXlink = true;
                    }
                }
            }
            if (usesXlink) root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xlink", XLINK_NS);
            var transformerFactory = TransformerFactory.newDefaultInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            var result = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(result));
            return result.toString();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot normalize Ebi SVG for the KNIME image port.", ex);
        }
    }

    private static void adaptPaint(final Element element, final String property) {
        if (!element.hasAttribute(property)) return;
        String color = element.getAttribute(property);
        if (color.equals("transparent")) {
            element.setAttribute(property, "none");
        } else if (color.equals("context-stroke")) {
            // Batik does not implement SVG 2 context-stroke; currentColor is a
            // supported fallback (black for Ebi's default stroke color).
            element.setAttribute(property, "currentColor");
        } else if (color.matches("(?i)#[0-9a-f]{8}")) {
            element.setAttribute(property, color.substring(0, 7));
            int alpha = Integer.parseInt(color.substring(7), 16);
            if (alpha != 255) {
                String opacityAttribute = property + "-opacity";
                String previous = element.getAttribute(opacityAttribute);
                double opacity = previous.isEmpty() ? 1.0 : Double.parseDouble(previous);
                if (!Double.isFinite(opacity) || opacity < 0 || opacity > 1)
                    throw new IllegalArgumentException("Invalid SVG " + opacityAttribute + ": " + previous);
                element.setAttribute(opacityAttribute, String.format(Locale.ROOT, "%.8f", opacity * alpha / 255.0));
            }
        }
    }
}
