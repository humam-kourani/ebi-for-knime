package org.ryoo.knimeEbi.scaffolder;

/** Small factories delegate port semantics and execution to the tested shared runtime. */
public final class EbiNodeFactorySourceGenerator {
    private EbiNodeFactorySourceGenerator() { }
    public static String generate(final EbiCommandMetadata m, final String factory, final String settings) {
        EbiCommandMetadata.validatePortMetadata(m);
        if (!JavaSourceUtil.isJavaIdentifier(factory)) throw new IllegalArgumentException("Invalid factory class: " + factory);
        if (!m.hasNoPrimitiveInputs() && !JavaSourceUtil.isJavaIdentifier(settings)) throw new IllegalArgumentException("Invalid settings class: " + settings);
        String parameters = m.hasNoPrimitiveInputs() ? "withoutParameters()" : "parametersClass(" + settings + ".class)";
        String settingsLine = m.hasNoPrimitiveInputs() ? "" : "        final " + settings + " settings = input.getParameters();\n";
        return """
            package org.ryoo.knimeEbi.node;

            // Generated. Edit scaffolder/runtime and regenerate from the pinned metadata.
            import org.knime.core.node.InvalidSettingsException;
            import org.knime.node.DefaultModel;
            import org.knime.node.DefaultModel.RequireModelParameters;
            import org.ryoo.knimeEbi.defaultNode.EbiDefaultNodeFactory;
            import org.ryoo.knimeEbi.scaffolder.EbiCatalog;
            import org.ryoo.knimeEbi.scaffolder.EbiCommandMetadata;
            import org.ryoo.knimeEbi.scaffolder.EbiParameterRules;
            import org.ryoo.knimeEbi.util.EbiNodeRuntime;

            public final class %s extends EbiDefaultNodeFactory {
                private static final EbiCommandMetadata COMMAND_METADATA = EbiCatalog.command("%s");

                public %s() {
                    super(COMMAND_METADATA, %s::configureModel);
                }

                private static DefaultModel configureModel(final RequireModelParameters model) {
                    return model.%s.configure(%s::configure).execute(%s::execute);
                }

                public static void configure(final DefaultModel.ConfigureInput input,
                        final DefaultModel.ConfigureOutput output) throws InvalidSettingsException {
            %s        try {
                        EbiParameterRules.validateSettings(COMMAND_METADATA, %s);
                    } catch (IllegalArgumentException ex) {
                        throw new InvalidSettingsException(ex.getMessage(), ex);
                    }
                    EbiNodeRuntime.configure(COMMAND_METADATA, input, output);
                }

            %s}
            """.formatted(factory, JavaSourceUtil.escapeJavaString(m.commandName), factory, factory,
                parameters, factory, factory, settingsLine, primitiveValues(m),
                EbiNodeExecuteMethodSourceGenerator.generate(m, settings));
    }
    static String primitiveValues(final EbiCommandMetadata m) {
        StringBuilder values = new StringBuilder("new String[] {");
        boolean first = true;
        for (int i = 0; i < m.inputs.size(); i++) {
            if (m.inputs.get(i).isPort) continue;
            if (!first) values.append(", "); first = false;
            boolean stringField = java.util.Set.of("String", "Long", "Character", "BigFraction").contains(m.inputs.get(i).type);
            if (!stringField) values.append("String.valueOf(");
            values.append("settings.").append(EbiNodeSettingsSourceGenerator.createSettingsFieldName(i));
            if (!stringField) values.append(")");
        }
        return values.append("}").toString();
    }
}
