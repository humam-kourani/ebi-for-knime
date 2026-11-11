package org.ryoo.knimeEbi.entryPoint;

import java.nio.file.Files;
import java.nio.file.Path;
import org.ryoo.knimeEbi.scaffolder.EbiNodeGenerator;

/** Run from the repository root; no KNIME or JNI is needed for offline generation. */
public final class Main {
    private Main() { }
    public static void main(final String[] args) throws Exception {
        Path root = Path.of(".").toAbsolutePath().normalize();
        boolean check = false, capture = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> { if (++i == args.length) throw new IllegalArgumentException("--root needs a path"); root = Path.of(args[i]).toAbsolutePath().normalize(); }
                case "--check" -> check = true;
                case "--capture" -> capture = true;
                default -> throw new IllegalArgumentException("Usage: Main [--root REPOSITORY] [--check | --capture]");
            }
        }
        if (check && capture) throw new IllegalArgumentException("Capture and check are separate actions.");
        Path metadata = root.resolve("metadata");
        if (capture) { EbiNodeGenerator.capture(metadata); System.out.println("Captured native metadata; review and regenerate explicitly."); return; }
        int count = EbiNodeGenerator.generate(root.resolve("extension"),
            Files.readString(metadata.resolve("ebi-java.txt")), Files.readString(metadata.resolve("ebi-manual.tex")),
            check, metadata.resolve("port-group-migration-1.1.1.json"));
        System.out.println((check ? "Verified " : "Generated ") + count + " Ebi command factories.");
    }
}
