package org.processmining.ebi;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Calls the actual production loader without loading native machine code. */
public final class LoaderAccess {
    private LoaderAccess() { }

    public static String codeMarker() {
        return "CODE_MARKER";
    }

    public static String resource(final String path) throws Exception {
        var method = NativeLibraryLoader.class.getDeclaredMethod("openResource", String.class);
        method.setAccessible(true);
        try (var input = (InputStream)method.invoke(null, path)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
