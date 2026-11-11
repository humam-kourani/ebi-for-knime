package org.ryoo.knimeEbi.scaffolder;

import java.util.List;
import java.util.Objects;

/** One ordered Ebi argument, or the single logical output. */
public final class EbiCommandMetadataParameter {
    public final String type;
    /** Default type; alternatives are in supportedPortTypes. */
    public final String portType;
    public final String typeDescription;
    public final boolean isPort;
    public final List<String> supportedPortTypes;
    /** Formats accepted by an Ebi document input; never a wildcard. */
    public final List<String> acceptedDocumentFormats;

    public EbiCommandMetadataParameter(final String type, final String portType,
            final String description, final boolean isPort) {
        this(type, portType, description, isPort,
            isPort ? List.of(Objects.requireNonNull(portType, "portType")) : List.of(),
            "EbiDocumentPortObject".equals(portType) ? List.of(".slpn") : List.of());
    }

    public EbiCommandMetadataParameter(final String type, final String portType,
            final String description, final boolean isPort, final List<String> supportedPortTypes) {
        this(type, portType, description, isPort, supportedPortTypes,
            supportedPortTypes.contains("EbiDocumentPortObject") ? List.of(".slpn") : List.of());
    }

    public EbiCommandMetadataParameter(final String type, final String portType,
            final String description, final boolean isPort, final List<String> supportedPortTypes,
            final List<String> acceptedDocumentFormats) {
        this.type = Objects.requireNonNull(type, "type");
        this.isPort = isPort;
        this.portType = isPort ? Objects.requireNonNull(portType, "portType") : "";
        this.typeDescription = Objects.requireNonNullElse(description, "");
        this.supportedPortTypes = isPort ? List.copyOf(supportedPortTypes) : List.of();
        this.acceptedDocumentFormats = isPort ? List.copyOf(acceptedDocumentFormats) : List.of();
        if (type.isBlank() || (isPort && (portType.isBlank()
                || this.supportedPortTypes.isEmpty() || !this.supportedPortTypes.contains(portType)))) {
            throw new IllegalArgumentException("An argument needs a type and a valid default port type.");
        }
        if (this.supportedPortTypes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("An empty port alternative is not allowed.");
        }
    }

    @Override
    public String toString() {
        return type + ": " + (isPort ? supportedPortTypes : typeDescription);
    }
}
