package org.ryoo.knimeEbi.port;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import javax.swing.JComponent;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortObjectSpecZipInputStream;
import org.knime.core.node.port.PortObjectSpecZipOutputStream;

/** The native file extension is known before execution and participates in validation. */
public final class EbiDocumentPortObjectSpec implements PortObjectSpec {
    private static final String ENTRY = "ebi-document-spec-v1";
    private final String format;
    public EbiDocumentPortObjectSpec(final String format) { this.format = EbiDocument.validateFormat(format); }
    public String getFormat() { return format; }
    @Override public JComponent[] getViews() { return new JComponent[0]; }
    @Override public boolean equals(Object other) { return other instanceof EbiDocumentPortObjectSpec s && format.equals(s.format); }
    @Override public int hashCode() { return Objects.hash(format); }

    public static final class Serializer extends PortObjectSpecSerializer<EbiDocumentPortObjectSpec> {
        @Override public void savePortObjectSpec(final EbiDocumentPortObjectSpec spec,
                final PortObjectSpecZipOutputStream out) throws IOException {
            out.putNextEntry(new ZipEntry(ENTRY));
            final DataOutputStream data = new DataOutputStream(out);
            data.writeUTF(spec.format);
            data.flush();
            out.closeEntry();
        }
        @Override public EbiDocumentPortObjectSpec loadPortObjectSpec(final PortObjectSpecZipInputStream in)
                throws IOException {
            ZipEntry entry = in.getNextEntry();
            if (entry == null || !ENTRY.equals(entry.getName())) throw new IOException("Missing Ebi document specification.");
            try { return new EbiDocumentPortObjectSpec(new DataInputStream(in).readUTF()); }
            catch (IllegalArgumentException ex) { throw new IOException("Invalid Ebi document format.", ex); }
        }
    }
}
