package org.ryoo.knimeEbi.util;

import java.util.List;
import org.ryoo.knimeEbi.scaffolder.EbiCommandMetadata;

/** KNIME-independent validation of complete typed command signatures. */
public final class EbiPortContract {
    private EbiPortContract() { }

    public static void validate(final EbiCommandMetadata metadata, final List<String> inputs,
            final String output) {
        if (inputs.size() != metadata.inputPortCount())
            throw new IllegalArgumentException(metadata.commandName + " requires exactly "
                + metadata.inputPortCount() + " input port" + (metadata.inputPortCount() == 1 ? "" : "s")
                + "; received " + inputs.size()
                + ". Add a port for each required input using the node's port controls.");
        if (inputs.stream().anyMatch(t -> t == null || t.isBlank()))
            throw new IllegalArgumentException("Connect every required Ebi input port.");
        if (!metadata.supportsSignature(inputs, output))
            throw new IllegalArgumentException("Unsupported port combination for " + metadata.commandName
                + ": " + inputs + " -> " + output + ". Choose compatible port types in port configuration.");
    }

    public static void validateDocumentFormat(final List<String> acceptedFormats, final String actual,
            final int inputIndex) {
        if (!acceptedFormats.contains(actual))
            throw new IllegalArgumentException("Input " + (inputIndex + 1) + " does not accept an Ebi "
                + actual + " document. Accepted formats: " + acceptedFormats);
    }
}
