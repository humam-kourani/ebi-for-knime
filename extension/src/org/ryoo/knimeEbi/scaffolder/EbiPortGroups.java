package org.ryoo.knimeEbi.scaffolder;

/** Stable port-group identifiers shared by the factory, runtime and workflow migration. */
public final class EbiPortGroups {
    private EbiPortGroups() { }

    // These readable IDs are persisted in workflows from extension version 1.1.1 onward.
    // Changes to role labels or these prefixes require an explicit workflow migration;
    // they must not be renamed casually as if they were presentation-only strings.
    public static String inputGroupId(final EbiCommandMetadata metadata, final int logicalIndex) {
        return "Input: " + EbiNodeText.inputName(metadata, logicalIndex);
    }

    public static String outputGroupId(final EbiCommandMetadata metadata) {
        return "Output: " + EbiNodeText.outputName(metadata);
    }
}
