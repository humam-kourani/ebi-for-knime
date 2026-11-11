package org.ryoo.knimeEbi.port;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

/** Lossless native Ebi text with an explicit format, independent of KNIME. */
public record EbiDocument(String format, String text) {
    private static final int MAGIC = 0x45424931;
    public static final int MAX_SERIALIZED_BYTES = 512 * 1024 * 1024;

    public EbiDocument {
        validateFormat(format);
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) throw new IllegalArgumentException("An Ebi document cannot be empty.");
    }

    public static String validateFormat(final String format) {
        if (format == null || !format.matches("\\.[a-z][a-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("Invalid Ebi document extension: " + format);
        return format;
    }

    public void write(final DataOutput out) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SERIALIZED_BYTES) throw new IOException("Ebi document exceeds the 512 MiB persistence limit.");
        out.writeInt(MAGIC);
        out.writeUTF(format);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static EbiDocument read(final DataInput in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Unsupported Ebi document serialization version.");
        final String format = in.readUTF();
        final int length = in.readInt();
        if (length < 1 || length > MAX_SERIALIZED_BYTES) throw new IOException("Invalid Ebi document byte length: " + length);
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        try { return new EbiDocument(format, StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()); }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid serialized Ebi document.", ex); }
    }
}
