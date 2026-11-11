package org.processmining.ebi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small command driver; production worker isolation/validation remains active. */
public final class CommandRegressionProbe {
    private CommandRegressionProbe() { }
    public static void main(final String[] args) throws Exception {
        Path result = Path.of(args[2]);
        try {
            String[] inputs = new String[args.length - 3];
            for (int index = 3; index < args.length; index++) {
                inputs[index - 3] = Files.readString(Path.of(args[index]), StandardCharsets.UTF_8);
            }
            Files.writeString(result, CallEbi.call_ebi(args[0], args[1], inputs), StandardCharsets.UTF_8);
        } catch (Exception error) {
            Files.writeString(result.resolveSibling(result.getFileName() + ".error"),
                    error.toString(), StandardCharsets.UTF_8);
            System.exit(2);
        }
    }
}
