package org.ryoo.knimeEbi.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.StringCell;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.context.ports.PortsConfiguration;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.node.DefaultModel;
import org.processmining.ebi.CallEbi;
import org.ryoo.knimeEbi.port.EbiDocumentPortObject;
import org.ryoo.knimeEbi.port.EbiDocumentPortObjectSpec;
import org.ryoo.knimeEbi.scaffolder.EbiCommandMetadata;
import org.ryoo.knimeEbi.scaffolder.EbiCommandMetadataParameter;
import org.ryoo.knimeEbi.scaffolder.EbiParameterRules;
import org.ryoo.knimeEbi.scaffolder.EbiNodeText;
import org.ryoo.knimeEbi.scaffolder.EbiPortGroups;

/** Shared execution path used by every generated factory. */
public final class EbiNodeRuntime {
    private EbiNodeRuntime() { }

    public static void configure(final EbiCommandMetadata metadata, final DefaultModel.ConfigureInput input,
            final DefaultModel.ConfigureOutput output) throws InvalidSettingsException {
        try {
            PortObjectSpec[] specs = input.getInPortSpecs();
            String outputType = selectedOutput(input.getOutPortTypes());
            List<String> inputTypes = Arrays.stream(specs).map(EbiPortAdapters::typeName).toList();
            EbiPortContract.validate(metadata, inputTypes, outputType);
            validateInputSlots(metadata, input.getPortsConfiguration());
            validateDocumentSpecs(metadata, specs);
            output.setOutSpec(0, EbiPortAdapters.outputSpec(outputType, metadata.outputFormat(outputType),
                metadata.commandName, metadata.output.type));
        } catch (IllegalArgumentException ex) { throw new InvalidSettingsException(ex.getMessage(), ex); }
    }

    public static void execute(final EbiCommandMetadata metadata, final DefaultModel.ExecuteInput input,
            final DefaultModel.ExecuteOutput output, final String[] primitiveValues)
            throws CanceledExecutionException, KNIMEException {
        try { executeInternal(metadata, input, output, primitiveValues); }
        catch (CanceledExecutionException ex) { throw ex; }
        catch (Exception ex) {
            throw new KNIMEException("Ebi command failed: " + metadata.commandName
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage()), ex);
        }
    }

    private static void executeInternal(final EbiCommandMetadata metadata, final DefaultModel.ExecuteInput input,
            final DefaultModel.ExecuteOutput output, final String[] primitiveValues) throws Exception {
        input.getExecutionContext().checkCanceled();
        final String[] normalizedValues = EbiParameterRules.normalizeSettings(metadata, primitiveValues);
        PortObject[] ports = input.getInPortObjects();
        String outputType = selectedOutput(input.getOutPortTypes());
        List<String> inputTypes = Arrays.stream(ports).map(EbiPortAdapters::typeName).toList();
        EbiPortContract.validate(metadata, inputTypes, outputType);
        validateInputSlots(metadata, input.getPortsConfiguration());
        String[] arguments = new String[metadata.inputs.size()];
        int portIndex = 0, primitiveIndex = 0;
        List<String> warnings = new ArrayList<>();
        if (metadata.commandName.equals("Ebi test bootstrap-test"))
            warnings.add("Ebi labels its reported tail score as p-value, but this build rejects for a high score (at least 1 minus the configured threshold). Do not apply the conventional small-p rejection rule to this field; the native decision is retained.");
        if (metadata.commandName.equals("Ebi test log-categorical-attribute"))
            warnings.add("This Ebi build reports internal attribute identifiers such as at1. The selected attribute label is used for analysis; consult the node settings for its name.");
        for (int i = 0; i < metadata.inputs.size(); i++) {
            EbiCommandMetadataParameter parameter = metadata.inputs.get(i);
            if (!parameter.isPort) { arguments[i] = normalizedValues[primitiveIndex++]; continue; }
            PortObject port = ports[portIndex];
            if (port instanceof EbiDocumentPortObject document)
                EbiPortContract.validateDocumentFormat(parameter.acceptedDocumentFormats, document.getFormat(), portIndex);
            input.getExecutionContext().checkCanceled();
            arguments[i] = EbiPortAdapters.serialize(port);
            if (inputTypes.get(portIndex).equals("DFMPortObject"))
                warnings.add("DFM input is interpreted as a control-flow model; frequency annotations are not stochastic probabilities.");
            if (inputTypes.get(portIndex).equals("DfgMsdPortObject"))
                warnings.add("DFG input passes activity/edge counts and start/end membership; minimum-self-distance and concurrency annotations are not consumed by Ebi.");
            portIndex++;
        }
        String format = metadata.outputFormat(outputType);
        String result = CallEbi.call_ebi(metadata.commandName, format, arguments,
            () -> input.getExecutionContext().checkCanceled());
        input.getExecutionContext().checkCanceled();
        if (result == null) throw new IllegalStateException("Ebi returned null for " + metadata.commandName + ".");
        if (result.stripLeading().startsWith("Ebi: error:")) throw new IllegalStateException(result.strip());
        if ("BufferedDataTable".equals(outputType)) {
            DataTableSpec spec = TableUtil.createOutputSpec(metadata.commandName, metadata.output.type, StringCell.TYPE);
            String exact = EbiScalarResult.exactText(format, result);
            if (!exact.equals(result)) warnings.add("Exact Ebi expression retained; auxiliary approximation omitted.");
            BufferedDataContainer container = input.getExecutionContext().createDataContainer(spec);
            try { container.addRowToTable(new DefaultRow("Row0", new StringCell(exact))); }
            finally { container.close(); }
            output.setOutData(0, container.getTable());
        } else {
            String normalized = EbiNativeOutputCompatibility.normalize(format, result);
            if (!normalized.equals(result)) warnings.add("Repaired the verified Ebi SNFA initial-state export syntax; state and probabilities are unchanged.");
            output.setOutData(0, EbiPortAdapters.deserialize(outputType, format, normalized));
            if (outputType.equals("DfgMsdPortObject") || outputType.equals("DFMPortObject"))
                warnings.add("Ebi returns start/end membership, not boundary counts. These are represented with unit counts; no minimum-self-distance annotations are inferred.");
            if (!outputType.equals("EbiDocumentPortObject") && metadata.outputFormats.containsKey("EbiDocumentPortObject")) {
                String nativeFormat = metadata.outputFormats.get("EbiDocumentPortObject");
                if (nativeFormat.equals(".slpn") || nativeFormat.equals(".sptree") || nativeFormat.equals(".sdfa") || nativeFormat.equals(".snfa"))
                    warnings.add("The selected PM4KNIME output is a control-flow projection. Choose Ebi document output to retain native stochastic weights.");
            }
        }
        if (!warnings.isEmpty()) output.setWarningMessage(String.join(" ", warnings.stream().distinct().toList()));
    }

    private static String selectedOutput(final PortType[] outputTypes) {
        if (outputTypes.length != 1) throw new IllegalArgumentException("An Ebi command requires exactly one selected output port. "
            + "Add a supported output using the node's port controls.");
        return EbiPortAdapters.typeName(outputTypes[0]);
    }

    private static void validateInputSlots(final EbiCommandMetadata metadata, final PortsConfiguration ports) {
        // Optional groups are bounded in the editor. Also validate loaded configurations, so an
        // overfilled group cannot compensate for a missing argument with the same total arity.
        for (int logical = 0; logical < metadata.inputPortCount(); logical++) {
            int[] positions = ports.getInputPortLocation().get(EbiPortGroups.inputGroupId(metadata, logical));
            if (positions == null || positions.length != 1 || positions[0] != logical) {
                throw new IllegalArgumentException("Select exactly one port for "
                    + EbiNodeText.inputName(metadata, logical) + " using the node's port controls.");
            }
        }
    }
    private static void validateDocumentSpecs(EbiCommandMetadata metadata, PortObjectSpec[] specs) {
        int index = 0;
        for (EbiCommandMetadataParameter parameter : metadata.inputs) {
            if (!parameter.isPort) continue;
            if (specs[index] instanceof EbiDocumentPortObjectSpec document)
                EbiPortContract.validateDocumentFormat(parameter.acceptedDocumentFormats, document.getFormat(), index);
            index++;
        }
    }
}
