package org.ryoo.knimeEbi.scaffolder;

/** Dialog settings retain exact fractions as strings and use contract-derived defaults. */
public final class EbiNodeSettingsSourceGenerator {
    private EbiNodeSettingsSourceGenerator() { }
    public static String generate(final EbiCommandMetadata metadata, final String settingsClassName) {
        if (!JavaSourceUtil.isJavaIdentifier(settingsClassName)) throw new IllegalArgumentException("Invalid settings class.");
        if (metadata.hasNoPrimitiveInputs()) throw new IllegalArgumentException("No settings to generate.");
        StringBuilder out = new StringBuilder("package org.ryoo.knimeEbi.node;\n\n// Generated from the pinned Ebi command contract.\n");
        out.append("import org.knime.node.parameters.NodeParameters;\nimport org.knime.node.parameters.Widget;\n");
        boolean numeric = metadata.inputs.stream().anyMatch(p -> !p.isPort && numeric(p.type));
        if (numeric) out.append("import org.knime.node.parameters.widget.number.NumberInputWidget;\n");
        out.append("\npublic final class ").append(settingsClassName).append(" implements NodeParameters {\n");
        for (int i = 0; i < metadata.inputs.size(); i++) {
            EbiCommandMetadataParameter p = metadata.inputs.get(i); if (p.isPort) continue;
            out.append("    @Widget(title = \"").append(JavaSourceUtil.escapeJavaString(EbiNodeText.settingTitle(p)))
                .append("\", description = \"").append(JavaSourceUtil.escapeJavaString(EbiNodeText.settingDescription(p))).append("\")\n");
            if (numeric(p.type)) out.append("    @NumberInputWidget\n");
            String type = switch (p.type) {
                case "Integer", "Byte", "Short" -> "int"; case "Double", "Float" -> "double";
                case "Boolean" -> "boolean"; case "String", "Long", "Character", "BigFraction" -> "String";
                default -> throw new IllegalArgumentException("Unsupported setting type: " + p.type);
            };
            String value = EbiParameterRules.defaultValue(metadata, i);
            out.append("    ").append(type).append(" ").append(createSettingsFieldName(i)).append(" = ")
                .append(type.equals("String") ? "\"" + JavaSourceUtil.escapeJavaString(value) + "\"" : value)
                .append(";\n\n");
        }
        return out.append("}\n").toString();
    }
    public static String createSettingsFieldName(final int metadataIndex) {
        if (metadataIndex < 0) throw new IllegalArgumentException("Negative metadata index.");
        return "m_input" + metadataIndex;
    }
    private static boolean numeric(final String type) { return switch (type) {
        case "Integer", "Byte", "Short", "Double", "Float" -> true; default -> false;
    }; }
}
