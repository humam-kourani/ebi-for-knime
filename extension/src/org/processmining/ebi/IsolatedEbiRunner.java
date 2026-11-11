package org.processmining.ebi;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Isolates upstream output and native panics using the current Java runtime. */
final class IsolatedEbiRunner {
    static final String TIMEOUT_PROPERTY = "knime.ebi.timeoutMillis";
    static final long DEFAULT_TIMEOUT_MILLIS = 900000;
    private static Path workerJar;
    private IsolatedEbiRunner() { }

    static String invoke(final String command, final String format, final String[] inputs,
            final CallEbi.CancellationChecker cancellation) throws Exception {
        cancellation.checkCanceled();
        final long timeout = timeoutMillis();
        final Path library = NativeLibraryLoader.resolveLibrary();
        final Path jar = workerJar();
        final Path directory = Files.createTempDirectory("knime-ebi-call-");
        Process worker = null;
        Throwable failure = null;
        try {
            try (DataOutputStream request = new DataOutputStream(Files.newOutputStream(directory.resolve("request.bin")))) {
                request.writeInt(EbiNativeWorker.PROTOCOL);
                EbiNativeWorker.writeString(request, command);
                EbiNativeWorker.writeString(request, format);
                request.writeInt(inputs.length);
                for (String input : inputs) { EbiNativeWorker.writeString(request, input); }
            }
            String executable = NativePlatform.current().id.startsWith("windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            if (!Files.isRegularFile(java)) {
                throw new IOException("Cannot find the KNIME Java runtime executable: " + java);
            }
            List<String> invocation = List.of(java.toString(),
                    "-D" + NativeLibraryLoader.OVERRIDE_PROPERTY + "=" + library,
                    "-Djava.io.tmpdir=" + directory,
                    "-cp", jar.toString(), EbiNativeWorker.class.getName(), directory.toString());
            worker = new ProcessBuilder(invocation).directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(directory.resolve("stderr.txt").toFile()).start();
            final long started = System.nanoTime();
            while (true) {
                cancellation.checkCanceled();
                if (Thread.currentThread().isInterrupted()) { throw new InterruptedException("Ebi execution interrupted."); }
                if (worker.waitFor(Math.min(50, timeout), TimeUnit.MILLISECONDS)) { break; }
                if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeout)) {
                    throw new TimeoutException("Ebi exceeded " + timeout + " ms and its worker was stopped. "
                            + "Set -D" + TIMEOUT_PROPERTY + "=<milliseconds> to change the limit.");
                }
            }
            cancellation.checkCanceled();
            if (worker.exitValue() != 0) {
                Path errorFile = directory.resolve("error.txt");
                String detail = diagnostic(Files.exists(errorFile) ? errorFile : directory.resolve("stderr.txt"));
                throw new IllegalStateException("Ebi worker failed (exit " + worker.exitValue() + "): " + detail);
            }
            Path result = directory.resolve("result.txt");
            if (!Files.isRegularFile(result)) { throw new IOException("Ebi worker produced no result."); }
            return CallEbi.validateResult(command, Files.readString(result, StandardCharsets.UTF_8));
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            stop(worker);
            try { removeDirectory(directory); }
            catch (IOException cleanup) {
                if (failure != null) { failure.addSuppressed(cleanup); }
                else { throw new IOException("Ebi completed but private temporary files could not be removed.", cleanup); }
            }
        }
    }

    static long timeoutMillis() {
        final String configured = System.getProperty(TIMEOUT_PROPERTY);
        if (configured == null) { return DEFAULT_TIMEOUT_MILLIS; }
        try {
            long value = Long.parseLong(configured);
            if (value > 0 && value <= Long.MAX_VALUE / 1000000) { return value; }
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(TIMEOUT_PROPERTY + " must be a positive integer in milliseconds.");
    }

    private static synchronized Path workerJar() throws IOException {
        if (workerJar != null) { return workerJar; }
        Path jar = Files.createTempFile("knime-ebi-worker-", ".jar");
        jar.toFile().deleteOnExit();
        // Bytecode resources also work from a nested Eclipse/PDE bundle JAR.
        List<String> classes = List.of("CallEbi", "CallEbi$CancellationChecker", "NativePlatform",
                "NativeLibraryLoader", "EbiNativeWorker", "IsolatedEbiRunner");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : classes) {
                String entry = "org/processmining/ebi/" + name + ".class";
                try (InputStream bytes = CallEbi.class.getResourceAsStream("/" + entry)) {
                    if (bytes == null) { throw new IOException("Missing worker bytecode resource: " + entry); }
                    output.putNextEntry(new JarEntry(entry));
                    bytes.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        workerJar = jar.toAbsolutePath();
        return workerJar;
    }

    private static String diagnostic(final Path file) throws IOException {
        if (!Files.isRegularFile(file)) { return "Native process ended without an error report."; }
        try (InputStream input = Files.newInputStream(file)) {
            return new String(input.readNBytes(8192), StandardCharsets.UTF_8).strip();
        }
    }

    private static void stop(final Process process) {
        if (process == null || !process.isAlive()) { return; }
        boolean interrupted = Thread.interrupted();
        try {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            interrupted = true;
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static void removeDirectory(final Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(file); }
        }
    }
}
