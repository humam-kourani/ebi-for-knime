package org.ryoo.knimeEbi.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataType;
import org.knime.core.data.image.ImageContent;
import org.knime.core.node.port.image.ImagePortObject;
import org.knime.core.node.port.image.ImagePortObjectSpec;

/** Keeps KNIME's non-API SVG implementation behind its public image port interfaces. */
final class EbiSvgImage {
    private static final String SVG_CELL = "org.knime.base.data.xml.SvgCell";
    private static final String SVG_CONTENT = "org.knime.base.data.xml.SvgImageContent";

    private EbiSvgImage() { }

    static ImagePortObjectSpec spec() {
        try {
            Class<? extends DataCell> cell = Class.forName(SVG_CELL).asSubclass(DataCell.class);
            return new ImagePortObjectSpec(DataType.getType(cell));
        } catch (ClassNotFoundException | LinkageError ex) {
            throw new IllegalStateException("KNIME SVG support (org.knime.ext.svg) is unavailable.", ex);
        }
    }

    static ImagePortObject fromText(final String svg) {
        try {
            Class<? extends ImageContent> type = Class.forName(SVG_CONTENT).asSubclass(ImageContent.class);
            ImageContent content = type.getConstructor(InputStream.class).newInstance(
                new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
            ImagePortObjectSpec spec = spec();
            if (!spec.getDataType().isASuperTypeOf(content.toImageCell().getType()))
                throw new IllegalStateException("KNIME SVG content has an incompatible image cell type.");
            return new ImagePortObject(content, spec);
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new IllegalStateException("KNIME SVG support (org.knime.ext.svg) is incompatible.", ex);
        }
    }
}
