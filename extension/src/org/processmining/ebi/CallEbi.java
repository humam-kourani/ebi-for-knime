package org.processmining.ebi;

/**
 * Stable Ebi JNI entry point: the package, class and native method names are ABI.
 * Each call runs the unchanged upstream JNI binary in an isolated Java worker.
 * Native panics and stdout remain outside KNIME; workers support cancellation.
 */
public final class CallEbi {
    private CallEbi() { }

    static native String call_ebi_internal(String commandName, String outputFormat, String[] inputs);

    @FunctionalInterface
    public interface CancellationChecker {
        void checkCanceled() throws Exception;
    }

    public static String call_ebi(final String commandName, final String outputFormat,
            final String[] inputs) {
        try {
            return call_ebi(commandName, outputFormat, inputs, () -> { });
        } catch (RuntimeException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ebi execution was interrupted.", error);
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    /** KNIME cancellation exceptions propagate unchanged to the node executor. */
    public static String call_ebi(final String commandName, final String outputFormat,
            final String[] inputs, final CancellationChecker cancellation) throws Exception {
        validateRequest(commandName, outputFormat, inputs);
        if (cancellation == null) {
            throw new IllegalArgumentException("An Ebi cancellation checker is required.");
        }
        return IsolatedEbiRunner.invoke(commandName, outputFormat, inputs.clone(), cancellation);
    }

    /** Diagnostic with no native load or user data. */
    public static String runtimeDescription() {
        return NativeLibraryLoader.description();
    }

    static void validateRequest(final String commandName, final String outputFormat, final String[] inputs) {
        if (commandName == null || commandName.isBlank()) {
            throw new IllegalArgumentException("An Ebi command is required.");
        }
        if (outputFormat == null || outputFormat.isBlank()) {
            throw new IllegalArgumentException("An Ebi output format is required.");
        }
        if (inputs == null) {
            throw new IllegalArgumentException("The Ebi input array must not be null.");
        }
        for (int index = 0; index < inputs.length; index++) {
            if (inputs[index] == null) {
                throw new IllegalArgumentException("Ebi input " + (index + 1) + " must not be null.");
            }
        }
    }

    static String validateResult(final String commandName, final String result) {
        if (result == null) {
            throw new IllegalStateException("Ebi returned no result for " + commandName + ".");
        }
        String diagnostic = result.stripLeading();
        if (diagnostic.startsWith("\uFEFF")) {
            diagnostic = diagnostic.substring(1).stripLeading();
        }
        if (diagnostic.startsWith("Ebi: error:")) {
            throw new IllegalStateException(commandName + " failed: " + diagnostic.strip());
        }
        return result; // Whitespace can be meaningful in successful output.
    }
}
