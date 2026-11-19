package org.ryoo.knimeEbi.scaffolder;

/** Stable port-group identifiers shared by the factory, runtime and workflow migration. */
public final class EbiPortGroups {
    private EbiPortGroups() { }

    // These IDs are persisted in workflows from version 1.1.1 onward. Changing the
    // user-facing spelling must never silently change a saved group's identifier.
    public static String inputGroupId(final EbiCommandMetadata metadata, final int logicalIndex) {
        return "Input: " + persistedLabel(EbiNodeText.inputName(metadata, logicalIndex));
    }

    public static String outputGroupId(final EbiCommandMetadata metadata) {
        return "Output: " + persistedLabel(EbiNodeText.outputName(metadata));
    }

    private static String persistedLabel(final String visibleLabel) {
        // Keep the 1.1.1 identifiers while displaying "Directly-Follows Graph".
        return visibleLabel.replace("Directly-Follows Graph", "Directly-follows graph")
            .replace("directly-Follows Graph", "directly-follows graph");
    }
}
