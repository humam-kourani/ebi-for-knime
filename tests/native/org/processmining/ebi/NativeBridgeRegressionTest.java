package org.processmining.ebi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

/** Dependency-free regression tests. Native smoke tests use real upstream Ebi. */
public final class NativeBridgeRegressionTest {
    private static int passed;
    private NativeBridgeRegressionTest() { }

    @FunctionalInterface
    interface Checked { void run() throws Exception; }

    private static void check(final String name, final boolean condition) {
        if (!condition) { throw new AssertionError(name); }
        passed++;
    }

    private static void rejects(final String name, final Class<? extends Throwable> type,
            final String message, final Checked operation) throws Exception {
        try { operation.run(); }
        catch (Throwable error) {
            if (!type.isInstance(error) || !error.getMessage().contains(message)) {
                throw new AssertionError(name + ": unexpected exception", error);
            }
            passed++;
            return;
        }
        throw new AssertionError(name + ": expected rejection");
    }

    public static void main(final String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--smoke")) { smoke(); }
        else if (args.length > 0 && args[0].equals("--packaged")) {
            check("Native extraction and worker classes from JAR", CallEbi.call_ebi("Ebi itself java", ".txt", new String[0])
                    .contains("Ebi_discover_non_stochastic_inductive_miner"));
        }
        else { unit(Path.of(args[0])); }
        System.out.println("NATIVE_TESTS_PASSED=" + passed);
    }

    private static void unit(final Path project) throws Exception {
        check("Windows amd64", NativePlatform.detect("Windows 11", "amd64").id.equals("windows-x86_64"));
        check("Windows x86_64", NativePlatform.detect("Windows 10", "x86_64").fileName.equals("ebi.dll"));
        check("Linux", NativePlatform.detect("Linux", "amd64").fileName.equals("libebi.so"));
        check("Locale independent", NativePlatform.detect("LINUX", "X86_64").id.equals("linux-x86_64"));
        rejects("Darwin is not Windows", UnsupportedOperationException.class, "not supplied",
                () -> NativePlatform.detect("Darwin", "x86_64"));
        rejects("MacOS", UnsupportedOperationException.class, "macOS/ARM",
                () -> NativePlatform.detect("Mac OS X", "x86_64"));
        rejects("Linux ARM", UnsupportedOperationException.class, "aarch64",
                () -> NativePlatform.detect("Linux", "aarch64"));
        rejects("Windows 32-bit", UnsupportedOperationException.class, "x86",
                () -> NativePlatform.detect("Windows 11", "x86"));
        rejects("Unknown OS", UnsupportedOperationException.class, "FreeBSD",
                () -> NativePlatform.detect("FreeBSD", "amd64"));
        rejects("Null command before load", IllegalArgumentException.class, "command",
                () -> CallEbi.call_ebi(null, ".txt", new String[0]));
        rejects("Empty command", IllegalArgumentException.class, "command",
                () -> CallEbi.validateRequest(" ", ".txt", new String[0]));
        rejects("Null format", IllegalArgumentException.class, "format",
                () -> CallEbi.validateRequest("Ebi information", null, new String[0]));
        rejects("Blank format", IllegalArgumentException.class, "format",
                () -> CallEbi.validateRequest("Ebi information", " ", new String[0]));
        rejects("Null inputs", IllegalArgumentException.class, "array",
                () -> CallEbi.validateRequest("Ebi information", ".txt", null));
        rejects("Null element", IllegalArgumentException.class, "input 2",
                () -> CallEbi.validateRequest("Ebi information", ".txt", new String[]{"x", null}));
        CallEbi.validateRequest("Ebi information", ".txt", new String[]{""});
        passed++; // Empty string parameters are distinct from missing parameters.
        rejects("Native null output", IllegalStateException.class, "no result",
                () -> CallEbi.validateResult("command", null));
        rejects("Native failure", IllegalStateException.class, "native diagnosis",
                () -> CallEbi.validateResult("command", "Ebi: error: native diagnosis"));
        rejects("Failure with whitespace/BOM", IllegalStateException.class, "invalid input",
                () -> CallEbi.validateResult("command", " \n\uFEFFEbi: error: invalid input\n"));
        check("Successful data untrimmed", CallEbi.validateResult("command", "  result\n").equals("  result\n"));
        check("Empty successful string", CallEbi.validateResult("command", "").isEmpty());
        rejects("Relative override", IOException.class, "absolute",
                () -> NativeLibraryLoader.validateOverride("lib/libebi.so"));
        rejects("Missing override", IOException.class, "regular file",
                () -> NativeLibraryLoader.validateOverride(project.resolve("does-not-exist.so").toString()));
        rejects("Directory override", IOException.class, "regular file",
                () -> NativeLibraryLoader.validateOverride(project.toString()));
        System.setProperty(IsolatedEbiRunner.TIMEOUT_PROPERTY, "0");
        try {
            rejects("Invalid timeout", IllegalArgumentException.class, "positive integer",
                    () -> IsolatedEbiRunner.timeoutMillis());
        } finally { System.clearProperty(IsolatedEbiRunner.TIMEOUT_PROPERTY); }
        check("Default timeout", IsolatedEbiRunner.timeoutMillis() == 900000);

        for (String os : new String[]{"Windows 11", "Linux"}) {
            NativePlatform platform = NativePlatform.detect(os, "amd64");
            Path binary = project.resolve("extension").resolve(platform.resourcePath());
            NativeLibraryLoader.verifyDigest(binary, platform.sha256); passed++;
            NativeLibraryLoader.verifyBinary(binary, platform); passed++;
        }
        Path temp = Files.createTempFile("ebi-invalid-", ".so");
        try {
            Files.writeString(temp, "not a native library");
            rejects("Truncated file", IOException.class, "Truncated",
                    () -> NativeLibraryLoader.verifyBinary(temp, NativePlatform.detect("Linux", "amd64")));
            rejects("Bad checksum", IOException.class, "checksum mismatch",
                    () -> NativeLibraryLoader.verifyDigest(temp, "0".repeat(64)));
            ByteBuffer elf = ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN);
            elf.putInt(0, 0x464c457f); elf.put(4, (byte)2); elf.put(5, (byte)1);
            elf.putShort(16, (short)3); elf.putShort(18, (short)62);
            elf.putLong(32, 64); elf.putShort(54, (short)56); elf.putShort(56, (short)1);
            elf.putInt(64, 3); // PT_INTERP: a position independent executable.
            Files.write(temp, elf.array());
            rejects("Mislabeled upstream PIE", IOException.class, "PIE/CLI",
                    () -> NativeLibraryLoader.verifyBinary(temp, NativePlatform.detect("Linux", "amd64")));
            elf.putShort(18, (short)183); Files.write(temp, elf.array());
            rejects("Wrong ELF architecture", IOException.class, "x86-64",
                    () -> NativeLibraryLoader.verifyBinary(temp, NativePlatform.detect("Linux", "amd64")));
        } finally { Files.deleteIfExists(temp); }
    }

    private static void smoke() throws Exception {
        final String log = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><log xes.version=\"1.0\">"
                + "<trace><string key=\"concept:name\" value=\"case-1\"/>"
                + "<event><string key=\"concept:name\" value=\"A\"/></event>"
                + "<event><string key=\"concept:name\" value=\"B\"/></event></trace>"
                + "<trace><string key=\"concept:name\" value=\"case-2\"/>"
                + "<event><string key=\"concept:name\" value=\"A\"/></event>"
                + "<event><string key=\"concept:name\" value=\"B\"/></event></trace></log>";
        // A failed load must not poison CallEbi's class initialization.
        final Path invalid = Files.createTempFile("ebi-invalid-", ".so");
        try {
            System.setProperty(NativeLibraryLoader.OVERRIDE_PROPERTY, invalid.toString());
            rejects("Failed load remains retryable", IllegalStateException.class, "Unable to load",
                    () -> CallEbi.call_ebi("Ebi convert log", ".xes", new String[]{log}));
        } finally {
            System.clearProperty(NativeLibraryLoader.OVERRIDE_PROPERTY);
            Files.deleteIfExists(invalid);
        }
        check("Real JNI log round-trip", CallEbi.call_ebi("Ebi convert log", ".xes", new String[]{log}).contains("<trace>"));
        String pnml = CallEbi.call_ebi("Ebi discover-non-stochastic inductive-miner", ".pnml", new String[]{log});
        check("Real JNI PNML", pnml.startsWith("<pnml>") && pnml.contains("<initialMarking>"));
        String ptml = CallEbi.call_ebi("Ebi discover-non-stochastic inductive-miner", ".ptml", new String[]{log});
        check("Real JNI PTML", ptml.contains("<ptml>") && ptml.contains("<sequence"));
        String fraction = CallEbi.call_ebi("Ebi analyse completeness", ".frac", new String[]{log}).strip();
        check("Real JNI exact fraction", fraction.equals("Approximately 1\n1"));
        check("Real JNI metadata", CallEbi.call_ebi("Ebi itself java", ".txt", new String[0])
                .contains("Ebi_discover_non_stochastic_inductive_miner"));
        rejects("Real JNI native error", IllegalStateException.class, "command not found",
                () -> CallEbi.call_ebi("Ebi no-such-command", ".txt", new String[0]));
        String unicode = log.replace("value=\"A\"", "value=\"Überprüfung\"");
        check("Real JNI UTF-8", CallEbi.call_ebi("Ebi convert log", ".xes", new String[]{unicode}).contains("Überprüfung"));
        System.setProperty(IsolatedEbiRunner.TIMEOUT_PROPERTY, "1");
        try {
            rejects("Worker deadline", TimeoutException.class, "exceeded",
                    () -> CallEbi.call_ebi("Ebi convert log", ".xes", new String[]{log}, () -> { }));
        } finally { System.clearProperty(IsolatedEbiRunner.TIMEOUT_PROPERTY); }
        final IOException cancellation = new IOException("test cancellation");
        final int[] polls = {0};
        try {
            CallEbi.call_ebi("Ebi itself java", ".txt", new String[0], () -> {
                if (++polls[0] == 2) { throw cancellation; }
            });
            throw new AssertionError("Running worker was not cancelled");
        } catch (IOException error) {
            check("Cancellation exception identity preserved", error == cancellation);
        }
        // This upstream documentation command aborts its process when templates
        // are absent. It must fail as a node error, not terminate this test JVM.
        rejects("Native panic isolated", IllegalStateException.class, "Ebi worker failed",
                () -> CallEbi.call_ebi("Ebi itself documentation commands", ".txt", new String[0]));
        check("Survive native panic", CallEbi.call_ebi("Ebi analyse completeness", ".frac", new String[]{log})
                .strip().endsWith("1"));
    }
}
