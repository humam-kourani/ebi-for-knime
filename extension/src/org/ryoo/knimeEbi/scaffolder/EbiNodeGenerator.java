package org.ryoo.knimeEbi.scaffolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.processmining.ebi.CallEbi;

/** Offline, deterministic generation from versioned snapshots; JNI capture is a separate action. */
public final class EbiNodeGenerator {
    private EbiNodeGenerator() { }
    public static List<EbiCommandMetadata> parseCommands(final String javaMetadata, final String manualMetadata) {
        EbiManualMetadata manual = new EbiManualMetadata(manualMetadata);
        List<EbiCommandMetadata> commands = new ArrayList<>();
        for (String block : javaMetadata.split("// == command ")) {
            if (!block.matches("(?s).*@Plugin\\s*\\(.*") || block.startsWith("Ebi itself ")) continue;
            commands.add(manual.enrich(new EbiCommandMetadata(block)));
        }
        commands.add(manual.entropicRelevance());
        commands.sort(Comparator.comparing(m -> m.commandName));
        if (commands.isEmpty()) throw new IllegalArgumentException("No supported Ebi commands.");
        if (commands.stream().map(m -> m.commandName).distinct().count() != commands.size()) throw new IllegalArgumentException("Duplicate command blocks.");
        return List.copyOf(commands);
    }

    public static int generate(final Path bundle, final String javaMetadata, final String manualMetadata, final boolean check) throws Exception {
        return generate(bundle, javaMetadata, manualMetadata, check, null);
    }

    /** Include the repository-level migration map when an explicit destination is supplied. */
    public static int generate(final Path bundle, final String javaMetadata, final String manualMetadata,
            final boolean check, final Path migrationMap) throws Exception {
        List<EbiCommandMetadata> commands = parseCommands(javaMetadata, manualMetadata);
        Map<Path, String> files = new LinkedHashMap<>(); List<String> factories = new ArrayList<>();
        Path source = bundle.resolve("src/org/ryoo/knimeEbi/node");
        for (EbiCommandMetadata m : commands) {
            String prefix = toClassNamePrefix(m.commandName), factory = prefix + "NodeFactory", settings = prefix + "NodeSettings";
            if (files.put(source.resolve(factory + ".java"), EbiNodeFactorySourceGenerator.generate(m, factory, settings)) != null)
                throw new IllegalArgumentException("Factory class-name collision: " + factory);
            if (!m.hasNoPrimitiveInputs()) files.put(source.resolve(settings + ".java"), EbiNodeSettingsSourceGenerator.generate(m, settings));
            factories.add(PluginXmlNodeRegistrar.GENERATED_PACKAGE + factory);
        }
        String catalog = EbiCatalog.write(commands);
        EbiCatalog.read(catalog); // Validate before touching the working tree.
        files.put(bundle.resolve("src/org/ryoo/knimeEbi/scaffolder/commands.xml"), catalog);
        if (migrationMap != null) files.put(migrationMap, portGroupMigrationJson(commands));
        Path plugin = bundle.resolve("plugin.xml");
        files.put(plugin, PluginXmlNodeRegistrar.synchronize(Files.readString(plugin), factories));
        List<String> differences = new ArrayList<>();
        for (Map.Entry<Path, String> e : files.entrySet()) {
            if (!Files.exists(e.getKey()) || !Files.readString(e.getKey()).equals(e.getValue())) differences.add(e.getKey().toString());
        }
        List<Path> stale = new ArrayList<>();
        if (Files.isDirectory(source)) try (var paths = Files.list(source)) {
            paths.filter(p -> p.getFileName().toString().matches("Ebi.*Node(?:Factory|Settings)\\.java"))
                .filter(p -> !files.containsKey(p)).forEach(stale::add);
        }
        stale.forEach(p -> differences.add("stale: " + p));
        if (check && !differences.isEmpty()) throw new IllegalStateException("Generated files differ; run regeneration:\n" + String.join("\n", differences));
        if (!check) {
            for (Map.Entry<Path, String> e : files.entrySet()) atomicWrite(e.getKey(), e.getValue());
            for (Path path : stale) Files.delete(path);
        }
        return commands.size();
    }

    /** Deterministic legacy-to-1.1.1 group mapping, including fixed and temporarily empty groups. */
    public static String portGroupMigrationJson(final List<EbiCommandMetadata> commands) {
        StringBuilder out = new StringBuilder("{\n");
        var factories = new java.util.HashSet<String>();
        for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
            EbiCommandMetadata metadata = commands.get(commandIndex);
            String factory = PluginXmlNodeRegistrar.GENERATED_PACKAGE + toClassNamePrefix(metadata.commandName) + "NodeFactory";
            if (!factories.add(factory)) throw new IllegalArgumentException("Duplicate migration factory: " + factory);
            out.append("  ").append(jsonString(factory)).append(": {\n");
            var identifiers = new java.util.HashSet<String>();
            for (int i = 0; i < metadata.inputPortCount(); i++) {
                String id = EbiPortGroups.inputGroupId(metadata, i);
                if (!identifiers.add(id)) throw new IllegalArgumentException("Duplicate port group for " + factory + ": " + id);
                out.append("    ").append(jsonString("ebi-input-" + i)).append(": ").append(jsonString(id)).append(",\n");
            }
            String output = EbiPortGroups.outputGroupId(metadata);
            if (!identifiers.add(output)) throw new IllegalArgumentException("Duplicate output group for " + factory);
            out.append("    \"ebi-output\": ").append(jsonString(output)).append("\n  }");
            if (commandIndex + 1 < commands.size()) out.append(',');
            out.append('\n');
        }
        return out.append("}\n").toString();
    }

    private static String jsonString(final String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    public static void capture(final Path metadataDirectory) throws IOException {
        Files.createDirectories(metadataDirectory);
        atomicWrite(metadataDirectory.resolve("ebi-java.txt"), CallEbi.call_ebi("Ebi itself java", ".txt", new String[0]));
        atomicWrite(metadataDirectory.resolve("ebi-manual.tex"), CallEbi.call_ebi("Ebi itself manual", ".txt", new String[0]));
    }
    public static void generateEbiNodes() {
        Path bundle = Files.exists(Path.of("plugin.xml")) ? Path.of(".") : Path.of("extension");
        Path metadata = bundle.toAbsolutePath().normalize().getParent().resolve("metadata");
        try {
            int count = generate(bundle, Files.readString(metadata.resolve("ebi-java.txt")), Files.readString(metadata.resolve("ebi-manual.tex")),
                false, metadata.resolve("port-group-migration-1.1.1.json"));
            System.out.println("Generated " + count + " command factories.");
        } catch (Exception ex) { throw new IllegalStateException("Node generation failed; no success was recorded.", ex); }
    }
    public static String toClassNamePrefix(final String command) {
        // Keep the original filter factory identities when the upstream command adds 'log'.
        String compatible = command.replaceFirst("^Ebi filter log traces ", "Ebi filter traces ");
        StringBuilder result = new StringBuilder();
        for (String word : compatible.trim().split("[^A-Za-z0-9]+")) {
            if (word.isEmpty()) continue;
            String lower = word.toLowerCase(Locale.ROOT);
            result.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        if (!JavaSourceUtil.isJavaIdentifier(result.toString())) throw new IllegalArgumentException("Command cannot become a class name: " + command);
        return result.toString();
    }
    private static void atomicWrite(final Path path, final String content) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), ".ebi-generation-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
