package org.ryoo.knimeEbi.tests;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.knime.core.node.NodeSettings;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.context.ModifiableNodeCreationConfiguration;
import org.knime.core.node.port.PortType;
import org.knime.core.node.workflow.NativeNodeContainer;
import org.knime.core.node.workflow.FileNativeNodeContainerPersistor;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.WorkflowCreationHelper;
import org.knime.core.node.workflow.WorkflowManager;
import org.knime.core.node.workflow.contextv2.WorkflowContextV2;
import org.knime.gateway.api.util.VersionId;
import org.knime.gateway.api.webui.entity.NativeNodeEnt;
import org.knime.gateway.api.webui.util.EntityFactory;
import org.knime.gateway.api.webui.util.WorkflowBuildContext;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.osgi.framework.FrameworkUtil;
import org.ryoo.knimeEbi.node.EbiConvertLabelledPetriNetNodeFactory;
import org.ryoo.knimeEbi.port.EbiDocumentPortObject;
import org.ryoo.knimeEbi.scaffolder.EbiCatalog;
import org.ryoo.knimeEbi.scaffolder.EbiCommandMetadata;
import org.ryoo.knimeEbi.scaffolder.EbiNodeText;
import org.ryoo.knimeEbi.scaffolder.EbiPortGroups;
import org.ryoo.knimeEbi.util.EbiPortAdapters;
import org.w3c.dom.Element;

/**
 * Exercises the actual workflow containers and the gateway entities consumed by the modern editor.
 * Merely changing an ExchangeablePortGroup in memory does not prove that an editor offers a control.
 */
public class PortEditorIntegrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void allFactoriesExposeSemanticHoverNamesAndModernEditorPortControls() throws Exception {
        var workflow = newWorkflow("Ebi port descriptions");
        try {
            Map<NodeID, EbiCommandMetadata> commands = new LinkedHashMap<>();
            for (var factory : registeredFactories().values()) {
                var metadata = EbiCatalog.command(factory.getNodeName());
                var id = workflow.createAndAddNode(factory);
                commands.put(id, metadata);
                var node = (NativeNodeContainer)workflow.getNodeContainer(id);
                for (int port = 0; port < metadata.inputPortCount(); port++) {
                    assertEquals(metadata.commandName, EbiNodeText.inputName(metadata, port),
                        node.getInPort(port + 1).getPortName());
                }
                assertEquals(metadata.commandName, EbiNodeText.outputName(metadata), node.getOutPort(1).getPortName());
                String description = descriptionText(node.getXMLDescription());
                assertFalse(metadata.commandName, description.contains("\\hyperref"));
                assertFalse(metadata.commandName, description.contains("filehandler:"));
                assertFalse(metadata.commandName, description.contains("portable network graphics"));
                assertFalse(metadata.commandName, description.contains("portable document format"));
                for (String tag : new String[] {"dynInPort", "dynOutPort"}) {
                    var dynamic = factory.getXMLDescription().getElementsByTagNameNS("*", tag);
                    for (int index = 0; index < dynamic.getLength(); index++) {
                        assertEquals("true", ((Element)dynamic.item(index)).getAttribute("configurable-via-menu"));
                    }
                }
            }
            var entities = EntityFactory.Workflow.buildWorkflowEnt(workflow,
                WorkflowBuildContext.builder().includeInteractionInfo(true).setVersion(VersionId.currentState()));
            assertEquals(EbiCatalog.commands().size(), entities.getNodes().size());
            for (var entry : commands.entrySet()) {
                var metadata = entry.getValue();
                String factoryId = ((NativeNodeContainer)workflow.getNodeContainer(entry.getKey()))
                    .getNode().getFactory().getFactoryId();
                var node = entities.getNodes().values().stream().map(NativeNodeEnt.class::cast)
                    .filter(entity -> entity.getTemplateId().equals(factoryId)).findFirst().orElseThrow();
                assertNotNull("Gateway must expose node " + entry.getKey(), node);
                var inputs = metadata.inputs.stream().filter(parameter -> parameter.isPort).toList();
                long expectedGroups = inputs.stream().filter(parameter -> parameter.supportedPortTypes.size() > 1).count()
                    + (metadata.output.supportedPortTypes.size() > 1 ? 1 : 0);
                assertNotNull(node.getPortGroups());
                assertEquals(metadata.commandName, expectedGroups, node.getPortGroups().size());
                for (int port = 0; port < inputs.size(); port++) {
                    var visible = node.getInPorts().get(port + 1); // zero is KNIME's flow-variable port
                    assertEquals(EbiNodeText.inputName(metadata, port), visible.getName());
                    boolean configurable = inputs.get(port).supportedPortTypes.size() > 1;
                    assertEquals(metadata.commandName, configurable, Boolean.TRUE.equals(visible.isCanRemove()));
                    if (configurable) {
                        String groupId = EbiPortGroups.inputGroupId(metadata, port);
                        assertTrue("The editor uses the ID as its menu label", groupId.startsWith("Input: "));
                        var group = node.getPortGroups().get(groupId);
                        assertNotNull(group);
                        assertEquals(inputs.get(port).supportedPortTypes.size(), group.getSupportedPortTypeIds().size());
                        assertEquals(Boolean.FALSE, group.isCanAddInPort());
                    }
                }
                assertEquals(EbiNodeText.outputName(metadata), node.getOutPorts().get(1).getName());
                assertEquals(metadata.output.supportedPortTypes.size() > 1,
                    Boolean.TRUE.equals(node.getOutPorts().get(1).isCanRemove()));
            }
        } finally {
            WorkflowManager.ROOT.removeProject(workflow.getID());
        }
    }

    @Test
    public void editorRemovalAndReplacementKeepNodesEditableAndEnforceRequiredArity() throws Exception {
        var workflow = newWorkflow("Ebi port changes");
        try {
            var metadata = EbiCatalog.command("Ebi convert labelled-petri-net");
            String inputGroup = EbiPortGroups.inputGroupId(metadata, 0);
            String outputGroup = EbiPortGroups.outputGroupId(metadata);
            var id = workflow.createAndAddNode(new EbiConvertLabelledPetriNetNodeFactory());
            assertEquals(Boolean.TRUE, onlyEditorNode(workflow).getInPorts().get(1).isCanRemove());
            assertEquals(Boolean.TRUE, onlyEditorNode(workflow).getOutPorts().get(1).isCanRemove());
            var config = creationConfig(workflow, id);
            config.getPortConfig().orElseThrow().getExtendablePorts().get(inputGroup).removeLastPort();
            workflow.replaceNode(id, config);
            var missingInput = (NativeNodeContainer)workflow.getNodeContainer(id);
            assertEquals("Only the flow-variable input remains", 1, missingInput.getNrInPorts());
            assertFalse("An incomplete argument list must not configure", missingInput.getNodeContainerState().isConfigured());
            assertFalse("An incomplete argument list must not execute", workflow.canExecuteNode(id));
            assertEquals("Ebi convert labelled-petri-net requires exactly 1 input port; received 0. "
                + "Add a port for each required input using the node's port controls.",
                missingInput.getNodeMessage().getMessage());
            assertEquals(Boolean.TRUE, onlyEditorNode(workflow).getPortGroups().get(inputGroup).isCanAddInPort());

            config = creationConfig(workflow, id);
            config.getPortConfig().orElseThrow().getExtendablePorts().get(outputGroup).removeLastPort();
            workflow.replaceNode(id, config);
            var missingOutput = (NativeNodeContainer)workflow.getNodeContainer(id);
            assertEquals(1, missingOutput.getNrOutPorts());
            assertFalse("A missing result port must not configure", missingOutput.getNodeContainerState().isConfigured());
            assertFalse("A missing result port must not execute", workflow.canExecuteNode(id));
            assertEquals("An Ebi command requires exactly one selected output port. "
                + "Add a supported output using the node's port controls.", missingOutput.getNodeMessage().getMessage());
            assertEquals(Boolean.TRUE, onlyEditorNode(workflow).getPortGroups().get(outputGroup).isCanAddOutPort());

            config = creationConfig(workflow, id);
            var ports = config.getPortConfig().orElseThrow();
            var replacement = EbiPortAdapters.resolvePortType("ProcessTreePortObject");
            ports.getExtendablePorts().get(inputGroup).addPort(replacement);
            ports.getExtendablePorts().get(outputGroup).addPort(EbiDocumentPortObject.TYPE);
            workflow.replaceNode(id, config);
            var restored = (NativeNodeContainer)workflow.getNodeContainer(id);
            assertEquals(replacement, restored.getInPort(1).getPortType());
            assertEquals(EbiDocumentPortObject.TYPE, restored.getOutPort(1).getPortType());
            assertEquals(Boolean.FALSE, onlyEditorNode(workflow).getPortGroups().get(inputGroup).isCanAddInPort());
            assertEquals(Boolean.FALSE, onlyEditorNode(workflow).getPortGroups().get(outputGroup).isCanAddOutPort());
            var saved = new NodeSettings("settings");
            creationConfig(workflow, id).saveSettingsTo(saved);
            var reloaded = new EbiConvertLabelledPetriNetNodeFactory().createNodeCreationConfig();
            reloaded.loadSettingsFrom(saved);
            assertArrayEquals(ports.getInputPorts(), reloaded.getPortConfig().orElseThrow().getInputPorts());
            assertArrayEquals(ports.getOutputPorts(), reloaded.getPortConfig().orElseThrow().getOutputPorts());
        } finally {
            WorkflowManager.ROOT.removeProject(workflow.getID());
        }
    }

    @Test
    public void legacyExchangeableSettingsNeedAndAcceptTheDocumentedMigration() throws Exception {
        var metadata = EbiCatalog.command("Ebi convert labelled-petri-net");
        var oldFactory = new LegacyExchangeableFactory(metadata);
        var defaultConfig = oldFactory.createNodeCreationConfig();
        writeFixture("legacy-default.xml", "org.ryoo.knimeEbi.node.EbiConvertLabelledPetriNetNodeFactory", defaultConfig);
        var oldConfig = oldFactory.createNodeCreationConfig();
        var oldPorts = oldConfig.getPortConfig().orElseThrow();
        oldPorts.getExchangeablePorts().get("ebi-input-0")
            .setSelectedPortType(EbiPortAdapters.resolvePortType("ProcessTreePortObject"));
        oldPorts.getExchangeablePorts().get("ebi-output").setSelectedPortType(EbiDocumentPortObject.TYPE);
        var settings = writeFixture("legacy-selected.xml",
            "org.ryoo.knimeEbi.node.EbiConvertLabelledPetriNetNodeFactory", oldConfig);
        var newFactory = new EbiConvertLabelledPetriNetNodeFactory();
        var unmigrated = newFactory.createNodeCreationConfig();
        unmigrated.loadSettingsFrom(settings);
        assertFalse("Unknown old group IDs silently fall back to defaults; migration is required",
            java.util.Arrays.equals(oldPorts.getInputPorts(), unmigrated.getPortConfig().orElseThrow().getInputPorts()));
        assertFalse(java.util.Arrays.equals(oldPorts.getOutputPorts(), unmigrated.getPortConfig().orElseThrow().getOutputPorts()));

        // The documented migration renames group IDs and wraps object_class in port_0.
        // Both persistence formats here are written/read by real KNIME APIs.
        var creation = settings.getNodeSettings("node_creation_config");
        var migrated = new NodeSettings("settings");
        migrated.addString("factory", settings.getString("factory"));
        var migratedCreation = migrated.addNodeSettings("node_creation_config");
        for (int port = 0; port < metadata.inputPortCount(); port++) {
            var group = migratedCreation.addNodeSettings(EbiPortGroups.inputGroupId(metadata, port));
            PortType.load(creation.getNodeSettings("ebi-input-" + port)).save(group.addNodeSettings("port_0"));
        }
        PortType.load(creation.getNodeSettings("ebi-output")).save(
            migratedCreation.addNodeSettings(EbiPortGroups.outputGroupId(metadata)).addNodeSettings("port_0"));
        var restored = newFactory.createNodeCreationConfig();
        restored.loadSettingsFrom(migrated);
        assertArrayEquals(oldPorts.getInputPorts(), restored.getPortConfig().orElseThrow().getInputPorts());
        assertArrayEquals(oldPorts.getOutputPorts(), restored.getPortConfig().orElseThrow().getOutputPorts());
        writeFixture("migrated-selected.xml", "org.ryoo.knimeEbi.node.EbiConvertLabelledPetriNetNodeFactory", restored);
    }

    @Test
    public void migrationScriptOutputLoadsThroughKnimesWorkflowLoader() throws Exception {
        // This resource is the exact output of scripts/migrate-workflow-1.1.1.py applied to an
        // archive containing the real legacy-selected.xml emitted by the preceding fixture test.
        var factory = new EbiConvertLabelledPetriNetNodeFactory();
        try (var stream = getClass().getResourceAsStream("migrated-1.1.0-settings.xml")) {
            assertNotNull("Include the actual migration-script output in the test bundle", stream);
            var settings = NodeSettings.loadFromXML(stream);
            assertEquals(factory.getClass().getName(), settings.getString("factory"));
            var config = FileNativeNodeContainerPersistor.loadCreationConfig(settings, factory).orElseThrow();
            assertArrayEquals(new PortType[] {EbiPortAdapters.resolvePortType("ProcessTreePortObject")},
                config.getPortConfig().orElseThrow().getInputPorts());
            assertArrayEquals(new PortType[] {EbiDocumentPortObject.TYPE},
                config.getPortConfig().orElseThrow().getOutputPorts());
            var workflow = newWorkflow("Migrated Ebi node");
            try {
                var id = workflow.addNodeAndApplyContext(factory, config, -1);
                var node = (NativeNodeContainer)workflow.getNodeContainer(id);
                assertEquals(EbiPortAdapters.resolvePortType("ProcessTreePortObject"), node.getInPort(1).getPortType());
                assertEquals(EbiDocumentPortObject.TYPE, node.getOutPort(1).getPortType());
                var editorNode = onlyEditorNode(workflow);
                assertEquals(Boolean.TRUE, editorNode.getInPorts().get(1).isCanRemove());
                assertEquals(Boolean.TRUE, editorNode.getOutPorts().get(1).isCanRemove());
            } finally {
                WorkflowManager.ROOT.removeProject(workflow.getID());
            }
        }
    }

    private WorkflowManager newWorkflow(String name) throws Exception {
        var directory = temporary.newFolder().toPath();
        var context = WorkflowContextV2.builder().withAnalyticsPlatformExecutor(executor -> executor
            .withCurrentUserAsUserId().withLocalWorkflowPath(directory)).withLocalLocation().build();
        return WorkflowManager.ROOT.createAndAddProject(name, new WorkflowCreationHelper(context));
    }

    private static ModifiableNodeCreationConfiguration creationConfig(WorkflowManager workflow, NodeID id) {
        return ((NativeNodeContainer)workflow.getNodeContainer(id)).getNode().getCopyOfCreationConfig().orElseThrow();
    }

    private static NativeNodeEnt onlyEditorNode(WorkflowManager workflow) {
        return (NativeNodeEnt)EntityFactory.Workflow.buildWorkflowEnt(workflow,
            WorkflowBuildContext.builder().includeInteractionInfo(true).setVersion(VersionId.currentState()))
            .getNodes().values().iterator().next();
    }

    private static String descriptionText(org.w3c.dom.Node node) {
        if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE) return node.getNodeValue();
        StringBuilder text = new StringBuilder();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            text.append(descriptionText(child));
        }
        return text.toString();
    }

    private static Map<String, DefaultNodeFactory> registeredFactories() throws Exception {
        var bundle = FrameworkUtil.getBundle(EbiDocumentPortObject.class);
        var parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Map<String, DefaultNodeFactory> result = new LinkedHashMap<>();
        try (var in = bundle.getEntry("plugin.xml").openStream()) {
            var nodes = parser.newDocumentBuilder().parse(in).getElementsByTagName("node");
            for (int index = 0; index < nodes.getLength(); index++) {
                String type = ((Element)nodes.item(index)).getAttribute("factory-class");
                result.put(type, (DefaultNodeFactory)bundle.loadClass(type).getConstructor().newInstance());
            }
        }
        return result;
    }

    private static NodeSettingsRO writeFixture(String name, String factory,
            ModifiableNodeCreationConfiguration config) throws Exception {
        var settings = new NodeSettings("settings");
        settings.addString("factory", factory);
        config.saveSettingsTo(settings);
        var bytes = new ByteArrayOutputStream();
        settings.saveToXML(bytes);
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "port-migration-fixtures");
        Files.createDirectories(directory);
        Files.write(directory.resolve(name), bytes.toByteArray());
        // Cross the actual XML read boundary, rather than sharing an in-memory settings tree.
        return NodeSettings.loadFromXML(new ByteArrayInputStream(bytes.toByteArray()));
    }

    /** Reproduces the 1.1.0 factory's released builder calls, solely to test its actual stored format. */
    private static final class LegacyExchangeableFactory extends DefaultNodeFactory {
        LegacyExchangeableFactory(EbiCommandMetadata metadata) {
            super(DefaultNode.create().name("Legacy Ebi persistence fixture").icon("default.png")
                .shortDescription("Legacy settings fixture").fullDescription("<p>Legacy settings fixture.</p>")
                .sinceVersion(5, 12, 0).dynamicPorts(ports -> {
                    int logicalIndex = 0;
                    for (var parameter : metadata.inputs) {
                        if (!parameter.isPort) continue;
                        String key = "ebi-input-" + logicalIndex++;
                        PortType[] types = parameter.supportedPortTypes.stream()
                            .map(EbiPortAdapters::resolvePortType).toArray(PortType[]::new);
                        ports.addInputPortGroup(key, group -> types.length == 1
                            ? group.name(key).description(key).fixed(types[0])
                            : group.name(key).description(key).exchangable(EbiPortAdapters.resolvePortType(parameter.portType))
                                .supportedTypes(types));
                    }
                    PortType[] types = metadata.output.supportedPortTypes.stream()
                        .map(EbiPortAdapters::resolvePortType).toArray(PortType[]::new);
                    ports.addOutputPortGroup("ebi-output", group -> types.length == 1
                        ? group.name("Output").description("Output").fixed(types[0])
                        : group.name("Output").description("Output")
                            .exchangable(EbiPortAdapters.resolvePortType(metadata.output.portType)).supportedTypes(types));
                }).model(model -> model.withoutParameters().configure((input, output) -> { })
                    .execute((input, output) -> { })));
        }
    }
}
