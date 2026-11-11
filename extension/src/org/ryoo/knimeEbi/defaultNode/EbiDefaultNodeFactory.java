package org.ryoo.knimeEbi.defaultNode;

import java.util.function.Consumer;
import java.util.function.Function;
import org.knime.core.node.port.PortType;
import org.knime.node.DefaultModel;
import org.knime.node.DefaultModel.RequireModelParameters;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.knime.node.RequirePorts.DynamicPortsAdder;
import org.knime.node.RequirePorts.PortsAdder;
import org.ryoo.knimeEbi.scaffolder.EbiCommandMetadata;
import org.ryoo.knimeEbi.scaffolder.EbiCommandMetadataParameter;
import org.ryoo.knimeEbi.scaffolder.EbiNodeText;
import org.ryoo.knimeEbi.scaffolder.EbiPortGroups;
import org.ryoo.knimeEbi.util.EbiPortAdapters;
import org.w3c.dom.Element;

/** One command with a visible, bounded port selector for each polymorphic argument. */
public class EbiDefaultNodeFactory extends DefaultNodeFactory {
    public EbiDefaultNodeFactory(final EbiCommandMetadata metadata,
            final Function<RequireModelParameters, DefaultModel> modelConfigurer) {
        super(DefaultNode.create().name(metadata.commandName).icon("default.png")
            .shortDescription(EbiNodeText.shortDescription(metadata))
            .fullDescription(EbiNodeText.fullDescription(metadata))
            .sinceVersion(5, 12, 0)
            .dynamicPorts(ports -> addDynamicPorts(metadata, ports))
            .model(modelConfigurer).nodeType(NodeType.Manipulator));
        enablePortMenus();
    }

    /** Kept for source compatibility with older generated factories. */
    @Deprecated
    public EbiDefaultNodeFactory(final EbiCommandMetadata metadata,
            final Consumer<PortsAdder> portConfigurer,
            final Function<RequireModelParameters, DefaultModel> modelConfigurer) {
        this(metadata, modelConfigurer);
    }

    private static void addDynamicPorts(final EbiCommandMetadata metadata, final DynamicPortsAdder ports) {
        int logicalPort = 0;
        for (EbiCommandMetadataParameter parameter : metadata.inputs) {
            if (!parameter.isPort) continue;
            final int index = logicalPort++;
            final PortType[] types = parameter.supportedPortTypes.stream()
                .map(EbiPortAdapters::resolvePortType).toArray(PortType[]::new);
            ports.addInputPortGroup(EbiPortGroups.inputGroupId(metadata, index), group -> {
                var mode = group.name(EbiNodeText.inputName(metadata, index))
                    .description(EbiNodeText.inputDescription(metadata, index));
                if (types.length == 1) return mode.fixed(types[0]);
                // The modern workflow editor exposes add/remove controls for optional groups. Its
                // gateway exposes only ExtendablePortGroups, not internal ExchangeablePortGroups.
                // One default port is present; the group permits at most one, and EbiNodeRuntime
                // rejects any missing logical argument before running Ebi.
                return mode.optional().supportedTypes(types).defaultPortType(resolvePortType(parameter.portType));
            });
        }
        final PortType[] outputTypes = metadata.output.supportedPortTypes.stream()
            .map(EbiPortAdapters::resolvePortType).toArray(PortType[]::new);
        ports.addOutputPortGroup(EbiPortGroups.outputGroupId(metadata), group -> {
            var mode = group.name(EbiNodeText.outputName(metadata))
                .description(EbiNodeText.outputDescription(metadata));
            if (outputTypes.length == 1) return mode.fixed(outputTypes[0]);
            return mode.optional().supportedTypes(outputTypes)
                .defaultPortType(resolvePortType(metadata.output.portType));
        });
    }

    /**
     * DefaultNodeFactory 5.12 writes false on every dynamic description, even for interactive
     * optional groups. Keep that description metadata consistent with the interactive groups.
     * This uses the public description DOM, just as KNIME adds bundle information during init.
     */
    private void enablePortMenus() {
        for (String tag : new String[] {"dynInPort", "dynOutPort"}) {
            var ports = getXMLDescription().getElementsByTagNameNS("*", tag);
            for (int index = 0; index < ports.getLength(); index++) {
                ((Element)ports.item(index)).setAttribute("configurable-via-menu", "true");
            }
        }
    }

    protected static PortType resolvePortType(final String name) {
        return EbiPortAdapters.resolvePortType(name);
    }
}
