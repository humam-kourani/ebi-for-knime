package org.ryoo.knimeEbi.scaffolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Executable regression tests run against the real generator and pinned native metadata. */
public final class GeneratorRegressionTest {
    private static int passed;
    private static Map<String, EbiCommandMetadata> catalog;
    private static String javaText, manual;
    private static Path repository;
    private interface Checked { void run() throws Exception; }
    private static void test(final String name, final Checked assertion) throws Exception {
        try { assertion.run(); passed++; }
        catch (Throwable failure) { throw new AssertionError(name, failure); }
    }
    private static void equal(final Object expected, final Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static void yes(final boolean condition) { if (!condition) throw new AssertionError("Condition was false"); }
    private static void rejects(final Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Invalid input was accepted");
    }
    private static void rejectsXml(final Checked action) throws Exception {
        try { action.run(); } catch (org.xml.sax.SAXException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsafe or invalid XML was accepted");
    }
    private static String descriptionText(final String fragment) throws Exception {
        var builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return builder.parse(new java.io.ByteArrayInputStream(("<description>" + fragment + "</description>")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8))).getDocumentElement().getTextContent().strip();
    }
    private static EbiCommandMetadata command(final String suffix) { return catalog.get("Ebi " + suffix); }
    public static void main(final String[] args) throws Exception {
        repository = Path.of(args[0]);
        javaText = Files.readString(repository.resolve("metadata/ebi-java.txt"));
        manual = Files.readString(repository.resolve("metadata/ebi-manual.tex"));
        List<EbiCommandMetadata> commands = EbiNodeGenerator.parseCommands(javaText, manual);
        catalog = EbiCatalog.read(EbiCatalog.write(commands));
        test("all commands regenerate", () -> yes(!commands.isEmpty() && commands.size() == catalog.size()));
        test("stochastic net maps to lossless document", () -> equal("EbiDocumentPortObject", EbiCommandMetadata.inputPortType("StochasticLabelledPetriNetSimpleWeights")));
        test("unknown object does not become table", () -> rejects(() -> EbiCommandMetadata.inputPortType("UnknownModel")));
        test("all Java variants retained", () -> {
            String block = List.of(javaText.split("// == command ")).stream().filter(s -> s.startsWith("Ebi analyse-non-stochastic activities ==")).findFirst().orElseThrow();
            EbiCommandMetadata m = new EbiCommandMetadata(block);
            equal(4, m.inputs.get(0).supportedPortTypes.size()); equal(4, m.variants.size());
        });
        test("native manual enriches BPMN and DFG", () -> {
            var p = command("analyse-non-stochastic activities").inputs.get(0);
            yes(p.supportedPortTypes.containsAll(List.of("BpmnPortObject", "DfgMsdPortObject", "DFMPortObject")));
        });
        test("inductive miner supports real process-tree output", () -> equal(".ptml", command("discover-non-stochastic inductive-miner").outputFormat("ProcessTreePortObject")));
        test("DFG has native typed and lossless outputs", () -> {
            var m = command("discover directly-follows-graph");
            equal("DfgMsdPortObject", m.output.portType); equal(".dfg", m.outputFormat("EbiDocumentPortObject"));
            equal(".dfg", m.outputFormat("DFMPortObject")); yes(!m.outputFormats.containsValue(".dfm")); yes(!m.outputFormats.containsKey("ProcessTreePortObject"));
        });
        test("stochastic tree default keeps weights", () -> {
            var m = command("discover occurrence stochastic-process-tree");
            equal("EbiDocumentPortObject", m.output.portType); equal(".sptree", m.outputFormat(m.output.portType));
        });
        test("stochastic net default keeps weights", () -> equal(".slpn", command("discover uniform stochastic-labelled-Petri-net").outputFormat("EbiDocumentPortObject")));
        test("entropic relevance restored", () -> equal(".logpol", command("conformance entropic-relevance").outputFormat("BufferedDataTable")));
        test("unsupported output rejects before native fallback", () -> rejects(() -> command("discover directly-follows-graph").outputFormat("ProcessTreePortObject")));
        test("filter factory identity retained", () -> equal("EbiFilterTracesLength", EbiNodeGenerator.toClassNamePrefix("Ebi filter log traces length")));
        test("native command rename retained", () -> equal("Ebi filter log traces length", command("filter log traces length").commandName));
        test("primitive parameters do not consume port indices", () -> {
            var m = command("filter log traces length"); equal(3, m.inputs.size()); equal(1, m.inputPortCount());
            yes(EbiNodeFactorySourceGenerator.generate(m, "ExampleFactory", "ExampleSettings").contains("settings.m_input2"));
        });
        test("unknown default port rejected", () -> rejects(() -> new EbiCommandMetadataParameter("XLog", "bogus", "", true, List.of("XLogPortObject"))));
        test("full tuples cannot be replaced with unions", () -> {
            var p = new EbiCommandMetadataParameter("model", "XLogPortObject", "", true, List.of("XLogPortObject", "PetriNetPortObject"));
            var m = new EbiCommandMetadata("Ebi example", "Example", "Example", List.of(p, p),
                new EbiCommandMetadataParameter("string", "BufferedDataTable", "", true),
                List.of(new EbiCommandVariant(List.of("XLogPortObject", "PetriNetPortObject"), "BufferedDataTable", ".txt")));
            yes(m.supportsSignature(List.of("XLogPortObject", "PetriNetPortObject"), "BufferedDataTable"));
            yes(!m.supportsSignature(List.of("XLogPortObject", "XLogPortObject"), "BufferedDataTable"));
        });
        test("signature wrong arity rejected", () -> rejects(() -> new EbiCommandMetadata("Ebi x", "x", "x", List.of(),
            new EbiCommandMetadataParameter("string", "BufferedDataTable", "", true),
            List.of(new EbiCommandVariant(List.of("XLogPortObject"), "BufferedDataTable", ".txt")))));
        test("ambiguous output formats rejected", () -> rejects(() -> new EbiCommandMetadata("Ebi x", "x", "x", List.of(),
            new EbiCommandMetadataParameter("string", "BufferedDataTable", "", true), List.of(
                new EbiCommandVariant(List.of(), "BufferedDataTable", ".txt"), new EbiCommandVariant(List.of(), "BufferedDataTable", ".frac")))));
        test("escaped Java attribute decoding", () -> equal("a \"quote\" and \\ path\nnext", EbiCommandMetadata.attribute("help = \"a \\\"quote\\\" and \\\\ path\\nnext\"", "help")));
        test("unicode decoding", () -> equal("α", EbiCommandMetadata.unescape("\\u03b1")));
        test("missing plugin attribute diagnostic", () -> rejects(() -> EbiCommandMetadata.attribute("name=\"test\"", "help")));
        test("corrupt manual fails explicitly", () -> rejects(() -> new EbiManualMetadata("not metadata")));
        test("metadata command call mismatch rejected", () -> {
            String block = List.of(javaText.split("// == command ")).stream().filter(s -> s.startsWith("Ebi analyse completeness ==")).findFirst().orElseThrow();
            rejects(() -> new EbiCommandMetadata(block.replace("call_ebi(\"Ebi analyse completeness\"", "call_ebi(\"Ebi wrong\"")));
        });
        test("Java keywords rejected", () -> { yes(!JavaSourceUtil.isJavaIdentifier("class")); yes(!JavaSourceUtil.isJavaIdentifier(null)); yes(!JavaSourceUtil.isJavaIdentifier("record")); yes(JavaSourceUtil.isJavaIdentifier("ΔNode")); });
        test("source escaping keeps controls valid", () -> equal("\\\"\\\\\\n\\r\\t\\b\\f\\001", JavaSourceUtil.escapeJavaString("\"\\\n\r\t\b\f\u0001")));
        test("settings descriptions remain valid XML and preserve operator text", () -> {
            var base = command("filter log traces length");
            var inputs = new ArrayList<>(base.inputs);
            String help = "OPERATOR\nminimum <= maximum & value > 0; literal <tag>";
            inputs.set(1, new EbiCommandMetadataParameter("String", "", help, false));
            var metadata = new EbiCommandMetadata(base.commandName, base.shortDescription, base.fullDescription,
                inputs, base.output, base.variants);
            String source = EbiNodeSettingsSourceGenerator.generate(metadata, "ExampleSettings");
            String widget = source.substring(source.indexOf("@Widget"), source.indexOf("\n    String"));
            String fragment = EbiCommandMetadata.attribute(widget, "description");
            var builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
            var document = builder.parse(new java.io.ByteArrayInputStream(("<description>" + fragment + "</description>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            equal("Minimum <= maximum & value > 0; literal <tag>.", document.getDocumentElement().getTextContent().strip());
        });
        test("nested LaTeX hyperlinks retain readable labels", () -> equal(
            "A finite stochastic language (.slang), labelled Petri net & τ.",
            EbiNodeText.toPlainText("A \\hyperref[filehandler:finite stochastic language]{finite \\textbf{stochastic} language (.slang)}, \\href{https://example.invalid/a?b=1&c=2}{labelled \\emph{Petri net}} \\& \\tau.")));
        test("LaTeX grouped fractions and inequalities retain meaning", () -> equal(
            "Threshold (1)/(2) ≤ x ≠ 0 and √(x + 1).",
            EbiNodeText.toPlainText("Threshold \\frac{1}{2} \\leq x \\neq 0 and \\sqrt{x + 1}.")));
        test("HTML entities and Unicode decode once as text", () -> equal(
            "A & B < C 😀 &lt; literal <tag>",
            EbiNodeText.toPlainText("<p>A &amp; <strong>B</strong> &lt; C &#x1F600; &amp;lt; literal <tag></p>")));
        test("event log and model roles have semantic labels", () -> {
            equal("Event log", EbiNodeText.inputName(command("filter log traces length"), 0));
            equal("Event log", EbiNodeText.inputName(command("conformance entropic-relevance"), 0));
            equal("Stochastic model", EbiNodeText.inputName(command("conformance entropic-relevance"), 1));
            equal("Process tree", EbiNodeText.inputName(command("discover occurrence stochastic-process-tree"), 1));
            equal("Log or process model", EbiNodeText.inputName(command("information"), 0));
        });
        test("two log inputs have distinct role labels", () -> {
            equal("First event log", EbiNodeText.inputName(command("test bootstrap-test"), 0));
            equal("Second event log", EbiNodeText.inputName(command("test bootstrap-test"), 1));
            equal("First log or stochastic model", EbiNodeText.inputName(command("conformance chi-squared-sample"), 0));
            equal("Second log or stochastic model", EbiNodeText.inputName(command("conformance chi-squared-sample"), 1));
        });
        test("information description advertises only supported representations", () -> {
            var m = command("information"); String text = descriptionText(EbiNodeText.inputDescription(m, 0));
            yes(!text.contains("FILE") && !text.contains("hyperref") && !text.contains("filehandler:"));
            yes(!text.contains(".png") && !text.contains(".pdf") && !text.contains(".csv") && !text.contains(".ocel"));
            yes(text.contains("Event log") && text.contains("BPMN model") && text.contains("Ebi document"));
            yes(text.contains(".slang") && text.contains(".snfa") && text.contains("Required input"));
        });
        test("all command descriptions are XML-safe and free of backend markup", () -> {
            String original = EbiCatalog.write(commands);
            for (var m : commands) {
                for (String fragment : List.of(EbiNodeText.shortDescription(m), EbiNodeText.fullDescription(m), EbiNodeText.outputDescription(m))) {
                    String text = descriptionText(fragment);
                    yes(!text.isBlank() && !text.contains("\\hyperref") && !text.contains("filehandler:"));
                }
                for (int i = 0; i < m.inputPortCount(); i++) {
                    String name = EbiNodeText.inputName(m, i), text = descriptionText(EbiNodeText.inputDescription(m, i));
                    yes(!name.matches("(?i)(inport|input|port) ?[0-9]+"));
                    yes(!text.contains("\\") && !text.contains("filehandler:") && !text.contains(".png") && !text.contains(".pdf"));
                }
            }
            equal(original, EbiCatalog.write(commands));
        });
        test("output labels distinguish models logs and exact results", () -> {
            equal("Event log", EbiNodeText.outputName(command("filter log traces empty")));
            equal("Stochastic process tree", EbiNodeText.outputName(command("discover occurrence stochastic-process-tree")));
            equal("Directly-follows graph", EbiNodeText.outputName(command("discover directly-follows-graph")));
            equal("Exact result", EbiNodeText.outputName(command("conformance entropic-relevance")));
            equal("Statistical report", EbiNodeText.outputName(command("test bootstrap-test")));
        });
        test("abbreviated settings have consistent human labels", () -> {
            var cohort = command("analyse cohort-analysis");
            equal("Random cohort splits", EbiNodeText.settingTitle(cohort.inputs.get(1)));
            equal("Minimum cohort fraction", EbiParameterRules.title(cohort.inputs.get(2)));
            equal("Comparison operator", EbiNodeText.settingTitle(command("filter log traces length").inputs.get(1)));
            equal("Trace length threshold", EbiNodeText.settingTitle(command("filter log traces length").inputs.get(2)));
            equal("Minimum fitness", EbiNodeText.settingTitle(command("discover directly-follows-graph").inputs.get(1)));
        });
        test("setting descriptions remove machine headers and preserve inclusive bounds", () -> {
            var p = command("analyse cohort-analysis").inputs.get(1);
            String text = descriptionText(EbiNodeText.settingDescription(p));
            yes(!text.contains("RNDSPL") && text.contains("integer at least 0") && text.contains("Set this to 0"));
            EbiParameterRules.validate(p, "0");
            var attribute = command("association trace-attribute").inputs.get(1);
            yes(descriptionText(EbiNodeText.settingDescription(attribute)).contains("the Ebi information node"));
        });
        test("all generated settings have readable parseable XML descriptions", () -> {
            for (var m : commands) {
                if (m.hasNoPrimitiveInputs()) continue;
                String generated = EbiNodeSettingsSourceGenerator.generate(m, "ReadableSettings");
                int cursor = 0, rendered = 0;
                while ((cursor = generated.indexOf("@Widget(", cursor)) >= 0) {
                    int end = generated.indexOf("\n", cursor);
                    String annotation = generated.substring(cursor, end);
                    String text = descriptionText(EbiCommandMetadata.attribute(annotation, "description"));
                    yes(!text.isBlank() && !text.contains("\\hyperref") && !text.contains("filehandler:"));
                    String title = EbiCommandMetadata.attribute(annotation, "title");
                    yes(!title.isBlank() && !title.equals("Rndspl") && !title.equals("Minsiz"));
                    cursor = end; rendered++;
                }
                equal((int)m.inputs.stream().filter(p -> !p.isPort).count(), rendered);
                for (int i = 0; i < m.inputs.size(); i++) if (!m.inputs.get(i).isPort) yes(generated.contains("m_input" + i + " = "));
            }
        });
        test("nonstochastic model descriptions do not claim retained stochastic weights", () -> {
            String text = descriptionText(EbiNodeText.fullDescription(command("discover-non-stochastic inductive-miner")));
            yes(text.contains("native model representation") && !text.contains("native stochastic model"));
            yes(descriptionText(EbiNodeText.outputDescription(command("discover occurrence stochastic-process-tree"))).contains("retain stochastic weights"));
        });
        test("flattened descriptions preserve paragraph spacing", () -> {
            String text = descriptionText(EbiNodeText.inputDescription(command("information"), 0));
            yes(text.contains(".\nSupported KNIME port types: "));
            yes(text.contains(".\nAccepted Ebi document formats: "));
            yes(text.contains(".\nRequired input."));
            String setting = descriptionText(EbiNodeText.settingDescription(command("filter log traces length").inputs.get(1)));
            yes(setting.contains(".\nAccepted values:"));
        });
        test("readable persistent group IDs are unique across every command", () -> {
            for (var m : commands) {
                var ids = new java.util.HashSet<String>();
                for (int i = 0; i < m.inputPortCount(); i++) {
                    String id = EbiPortGroups.inputGroupId(m, i);
                    yes(id.startsWith("Input: ") && ids.add(id));
                }
                yes(ids.add(EbiPortGroups.outputGroupId(m)));
                equal(m.inputPortCount() + 1, ids.size());
            }
            var filter = command("filter log traces length");
            equal("Input: Event log", EbiPortGroups.inputGroupId(filter, 0));
            equal("Output: Event log", EbiPortGroups.outputGroupId(filter));
            equal("Input: First event log", EbiPortGroups.inputGroupId(command("test bootstrap-test"), 0));
            equal("Input: Second event log", EbiPortGroups.inputGroupId(command("test bootstrap-test"), 1));
        });
        test("generated migration map covers all factories and fixed groups", () -> {
            String mapping = EbiNodeGenerator.portGroupMigrationJson(commands);
            equal(mapping, Files.readString(repository.resolve("metadata/port-group-migration-1.1.1.json")));
            for (var m : commands) {
                String factory = "org.ryoo.knimeEbi.node." + EbiNodeGenerator.toClassNamePrefix(m.commandName) + "NodeFactory";
                int start = mapping.indexOf("\"" + factory + "\": {");
                yes(start >= 0);
                String object = mapping.substring(start, mapping.indexOf('}', start));
                for (int i = 0; i < m.inputPortCount(); i++)
                    yes(object.contains("\"ebi-input-" + i + "\": \"" + EbiPortGroups.inputGroupId(m, i) + "\""));
                yes(object.contains("\"ebi-output\": \"" + EbiPortGroups.outputGroupId(m) + "\""));
            }
        });
        test("locale-independent names", () -> {
            java.util.Locale old = java.util.Locale.getDefault();
            try { java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR")); equal("EbiInformation", EbiNodeGenerator.toClassNamePrefix("Ebi information")); }
            finally { java.util.Locale.setDefault(old); }
        });
        test("valid exact fraction", () -> EbiParameterRules.validateSettings(command("discover directly-follows-graph"), new String[]{"1/3"}));
        test("numeric whitespace canonicalized for native parser", () -> equal("1", EbiParameterRules.normalizeSettings(command("discover directly-follows-graph"), new String[]{" 1 / 1 "})[0]));
        test("numeric canonicalization preserves labels", () -> {
            var m = command("association trace-attribute");
            String[] v = EbiParameterRules.normalizeSettings(m, new String[]{" amount ", " 20 "});
            equal(" amount ", v[0]); equal("20", v[1]);
        });
        test("null string is not serialized as the word null", () -> {
            var m = command("filter log traces length");
            rejects(() -> EbiParameterRules.validateSettings(m, new String[]{null, "2"}));
            yes(!EbiNodeFactorySourceGenerator.generate(m, "ExampleFactory", "ExampleSettings").contains("String.valueOf(settings.m_input1)"));
        });
        test("unreasonable decimal exponents rejected", () -> rejects(() -> EbiParameterRules.validateSettings(command("discover directly-follows-graph"), new String[]{"1e-2147483647"})));
        test("zero denominator", () -> rejects(() -> EbiParameterRules.validateSettings(command("discover directly-follows-graph"), new String[]{"1/0"})));
        test("fraction upper bound", () -> rejects(() -> EbiParameterRules.validateSettings(command("discover directly-follows-graph"), new String[]{"4/3"})));
        test("fraction lower bound", () -> rejects(() -> EbiParameterRules.validateSettings(command("discover directly-follows-graph"), new String[]{"-1/3"})));
        test("fraction endpoints accepted", () -> { EbiParameterRules.validateSettings(command("discover directly-follows-graph"), new String[]{"0"}); EbiParameterRules.validateSettings(command("discover directly-follows-graph"), new String[]{"1"}); });
        test("invalid operator caught during configure", () -> rejects(() -> EbiParameterRules.validateSettings(command("filter log traces length"), new String[]{"??", "2"})));
        test("valid operator", () -> EbiParameterRules.validateSettings(command("filter log traces length"), new String[]{">=", "2"}));
        test("negative count rejected", () -> rejects(() -> EbiParameterRules.validateSettings(command("filter log traces length"), new String[]{">=", "-1"})));
        test("sample count zero rejected", () -> rejects(() -> EbiParameterRules.validateSettings(command("conformance earth-movers-sample"), new String[]{"0"})));
        test("sample fold index bounded separately from seed", () -> {
            EbiParameterRules.validateSettings(command("sample folds"), new String[]{"5", "98342", "4"});
            rejects(() -> EbiParameterRules.validateSettings(command("sample folds"), new String[]{"5", "0", "5"}));
        });
        test("missing settings rejected", () -> rejects(() -> EbiParameterRules.validateSettings(command("filter log traces length"), new String[]{">="})));
        test("unbounded double values rejected", () -> rejects(() -> EbiParameterRules.validate(new EbiCommandMetadataParameter("Double", "", "threshold", false), "NaN")));
        test("defaults are valid where user data is not needed", () -> {
            for (var m : commands) {
                List<String> defaults = new ArrayList<>(); boolean needsUser = false;
                for (int i = 0; i < m.inputs.size(); i++) if (!m.inputs.get(i).isPort) {
                    String d = EbiParameterRules.defaultValue(m, i); if (d.isEmpty()) needsUser = true; defaults.add(d);
                }
                if (!needsUser) EbiParameterRules.validateSettings(m, defaults.toArray(String[]::new));
            }
        });
        test("catalog UTF-8 and whitespace roundtrip", () -> {
            var base = command("analyse completeness");
            var m = new EbiCommandMetadata(base.commandName, "München \"β\"", "Line one  two\nnext & <tag>", base.inputs, base.output, base.variants);
            var read = EbiCatalog.read(EbiCatalog.write(List.of(m))).get(m.commandName);
            equal(m.shortDescription, read.shortDescription); equal(m.fullDescription, read.fullDescription); equal(m.variants, read.variants);
        });
        test("catalog immutable", () -> {
            try { catalog.clear(); throw new AssertionError("Mutable catalog"); } catch (UnsupportedOperationException expected) { }
        });
        test("catalog XXE rejected", () -> rejectsXml(() -> EbiCatalog.read("<!DOCTYPE ebi-commands [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><ebi-commands schema=\"1\">&x;</ebi-commands>")));
        test("catalog duplicate command rejected", () -> rejects(() -> EbiCatalog.read(EbiCatalog.write(List.of(commands.get(0), commands.get(0))))));
        test("all accepted raw formats are bounded", () -> {
            for (var m : commands) for (var p : m.inputs) if (p.supportedPortTypes.contains("EbiDocumentPortObject")) yes(!p.acceptedDocumentFormats.isEmpty());
        });
        test("registration synchronises only generated nodes", () -> {
            String xml = "<plugin><extension point=\"other\"><item value=\"keep\"/></extension><extension point=\"org.knime.workbench.repository.nodes\"><node factory-class=\"org.ryoo.knimeEbi.node.Stale\"/><node factory-class=\"some.HandwrittenFactory\"/></extension></plugin>";
            String once = PluginXmlNodeRegistrar.synchronize(xml, List.of("org.ryoo.knimeEbi.node.Fresh"));
            yes(once.contains("some.HandwrittenFactory")); yes(once.contains("value=\"keep\"")); yes(!once.contains("Stale"));
            equal(once, PluginXmlNodeRegistrar.synchronize(once, List.of("org.ryoo.knimeEbi.node.Fresh")));
        });
        test("duplicate registrations rejected", () -> rejects(() -> PluginXmlNodeRegistrar.synchronize("<plugin/>", List.of("org.ryoo.knimeEbi.node.X", "org.ryoo.knimeEbi.node.X"))));
        test("registrar XXE rejected", () -> rejectsXml(() -> PluginXmlNodeRegistrar.synchronize("<!DOCTYPE plugin [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><plugin>&x;</plugin>", List.of())));
        test("checked-in generation is reproducible", () -> equal(commands.size(), EbiNodeGenerator.generate(repository.resolve("extension"),
            javaText, manual, true, repository.resolve("metadata/port-group-migration-1.1.1.json"))));
        test("generation check does not mutate changed source", () -> {
            Path dir = Files.createTempDirectory("ebi-generator-test-");
            try {
                Files.writeString(dir.resolve("plugin.xml"), "<plugin/>");
                EbiNodeGenerator.generate(dir, javaText, manual, false);
                Path file = dir.resolve("src/org/ryoo/knimeEbi/node/EbiAnalyseCompletenessNodeFactory.java");
                Files.writeString(file, "user change");
                rejects(() -> EbiNodeGenerator.generate(dir, javaText, manual, true)); equal("user change", Files.readString(file));
                rejects(() -> EbiNodeGenerator.generate(dir, javaText, "bad manual", false)); equal("user change", Files.readString(file));
            } finally {
                try (var stream = Files.walk(dir)) { for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
            }
        });
        System.out.println("CORE_TESTS_PASSED=" + passed);
    }
}
