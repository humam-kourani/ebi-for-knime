package org.processmining.ebi;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Child-JVM protocol endpoint. Never invoke native Ebi in the KNIME JVM. */
public final class EbiNativeWorker {
    static final int PROTOCOL = 0x45424931;
    private EbiNativeWorker() { }

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Ebi worker requires one private request directory.");
        }
        final Path directory = Path.of(args[0]);
        try {
            String command;
            String format;
            String[] inputs;
            try (DataInputStream request = new DataInputStream(Files.newInputStream(directory.resolve("request.bin")))) {
                if (request.readInt() != PROTOCOL) {
                    throw new IOException("Invalid Ebi worker protocol.");
                }
                command = readString(request);
                format = readString(request);
                int count = request.readInt();
                if (count < 0 || count > 10000) {
                    throw new IOException("Invalid Ebi input count.");
                }
                inputs = new String[count];
                for (int i = 0; i < count; i++) { inputs[i] = readString(request); }
                if (request.read() != -1) { throw new IOException("Trailing Ebi request bytes."); }
            }
            CallEbi.validateRequest(command, format, inputs);
            NativeLibraryLoader.ensureLoaded();
            String result = CallEbi.validateResult(command, CallEbi.call_ebi_internal(command, format, inputs));
            Files.writeString(directory.resolve("result.txt"), result, StandardCharsets.UTF_8);
        } catch (Exception | LinkageError error) {
            Files.writeString(directory.resolve("error.txt"), error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()), StandardCharsets.UTF_8);
            System.exit(3);
        }
    }

    static void writeString(final DataOutputStream output, final String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(final DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 1073741824) { throw new IOException("Invalid Ebi string length."); }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) { throw new IOException("Truncated Ebi request."); }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
