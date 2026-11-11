package org.ryoo.knimeEbi.port;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.port.AbstractPortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortObjectZipInputStream;
import org.knime.core.node.port.PortObjectZipOutputStream;
import org.knime.core.node.port.PortType;
import org.knime.core.node.port.PortTypeRegistry;

/** Carries native stochastic models without converting away their weights. */
public final class EbiDocumentPortObject extends AbstractPortObject {
    public static final PortType TYPE = PortTypeRegistry.getInstance().getPortType(EbiDocumentPortObject.class);
    private static final String ENTRY = "ebi-document-v1";
    private EbiDocument document;
    public EbiDocumentPortObject() { }
    public EbiDocumentPortObject(final String format, final String text) { document = new EbiDocument(format, text); }
    public EbiDocument getDocument() { return Objects.requireNonNull(document, "Uninitialized Ebi document"); }
    public String getText() { return getDocument().text(); }
    public String getFormat() { return getDocument().format(); }
    @Override public EbiDocumentPortObjectSpec getSpec() { return new EbiDocumentPortObjectSpec(getFormat()); }
    @Override public String getSummary() { return "Ebi " + getFormat() + " document (" + getText().length() + " characters)"; }
    @Override public JComponent[] getViews() {
        String content = getText();
        if (content.length() > 20000) content = content.substring(0, 20000) + "\n[Preview truncated; the complete document is retained.]";
        JTextArea area = new JTextArea(content); area.setEditable(false);
        JScrollPane pane = new JScrollPane(area); pane.setName("Ebi document");
        return new JComponent[] {pane};
    }
    @Override public boolean equals(Object other) { return other instanceof EbiDocumentPortObject p && Objects.equals(document, p.document); }
    @Override public int hashCode() { return Objects.hashCode(document); }
    @Override protected void save(final PortObjectZipOutputStream out, final ExecutionMonitor exec)
            throws IOException, CanceledExecutionException {
        exec.checkCanceled();
        out.putNextEntry(new ZipEntry(ENTRY));
        DataOutputStream data = new DataOutputStream(out); getDocument().write(data); data.flush(); out.closeEntry();
    }
    @Override protected void load(final PortObjectZipInputStream in, final PortObjectSpec spec,
            final ExecutionMonitor exec) throws IOException, CanceledExecutionException {
        exec.checkCanceled();
        ZipEntry entry = in.getNextEntry();
        if (entry == null || !ENTRY.equals(entry.getName())) throw new IOException("Missing Ebi document data.");
        EbiDocument loaded = EbiDocument.read(new DataInputStream(in));
        if (!(spec instanceof EbiDocumentPortObjectSpec s) || !s.getFormat().equals(loaded.format()))
            throw new IOException("Ebi document format does not match its port specification.");
        document = loaded;
    }
    public static final class Serializer extends AbstractPortObjectSerializer<EbiDocumentPortObject> { }
}
