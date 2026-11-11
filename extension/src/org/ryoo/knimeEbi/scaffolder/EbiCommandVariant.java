package org.ryoo.knimeEbi.scaffolder;

import java.util.List;
import java.util.Objects;

/** A complete admissible port signature, not independent unvalidated unions. */
public record EbiCommandVariant(List<String> inputPortTypes, String outputPortType, String outputFormat) {
    public EbiCommandVariant {
        inputPortTypes = List.copyOf(inputPortTypes);
        Objects.requireNonNull(outputPortType, "outputPortType");
        Objects.requireNonNull(outputFormat, "outputFormat");
        if (outputPortType.isBlank() || !outputFormat.matches("\\.[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Invalid output type or Ebi format.");
        }
    }
}
