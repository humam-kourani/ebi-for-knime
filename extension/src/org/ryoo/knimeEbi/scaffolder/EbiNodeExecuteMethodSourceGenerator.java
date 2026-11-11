package org.ryoo.knimeEbi.scaffolder;

public final class EbiNodeExecuteMethodSourceGenerator {
    private EbiNodeExecuteMethodSourceGenerator() { }
    public static String generate(final EbiCommandMetadata metadata, final String settingsClassName) {
        EbiCommandMetadata.validatePortMetadata(metadata);
        if (!metadata.hasNoPrimitiveInputs() && !JavaSourceUtil.isJavaIdentifier(settingsClassName))
            throw new IllegalArgumentException("A valid settings class is required.");
        return "    public static void execute(final DefaultModel.ExecuteInput input,\n"
            + "            final DefaultModel.ExecuteOutput output) throws org.knime.core.node.CanceledExecutionException, org.knime.core.node.KNIMEException {\n"
            + (metadata.hasNoPrimitiveInputs() ? "" : "        final " + settingsClassName + " settings = input.getParameters();\n")
            + "        EbiNodeRuntime.execute(COMMAND_METADATA, input, output, "
            + EbiNodeFactorySourceGenerator.primitiveValues(metadata) + ");\n    }\n";
    }
}
