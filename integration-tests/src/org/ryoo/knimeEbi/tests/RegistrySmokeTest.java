package org.ryoo.knimeEbi.tests;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.NodeSettings;
import org.knime.core.node.port.PortTypeRegistry;
import org.knime.core.node.port.PortUtil;
import org.knime.node.DefaultNodeFactory;
import org.osgi.framework.FrameworkUtil;
import org.ryoo.knimeEbi.port.EbiDocumentPortObject;
import org.ryoo.knimeEbi.port.EbiDocumentPortObjectSpec;
import org.ryoo.knimeEbi.scaffolder.EbiCatalog;
import org.ryoo.knimeEbi.scaffolder.EbiCommandMetadata;
import org.ryoo.knimeEbi.scaffolder.EbiPortGroups;
import org.ryoo.knimeEbi.util.EbiPortAdapters;
import org.processmining.ebi.CallEbi;
import org.w3c.dom.Element;

/** Tests real extension registration and KNIME APIs inside the Equinox runtime. */
public class RegistrySmokeTest {
    @Test
    public void allRegisteredFactoriesCreateRequiredTypedPortConfigurations() throws Exception {
        var bundle = FrameworkUtil.getBundle(EbiDocumentPortObject.class);
        assertNotNull("Test must run in OSGi", bundle);
        var parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Set<String> commands = new HashSet<>();
        int testedSignatures = 0;
        try (var in = bundle.getEntry("plugin.xml").openStream()) {
            var entries = parser.newDocumentBuilder().parse(in).getElementsByTagName("node");
            assertEquals("Every catalog command needs exactly one registered factory", EbiCatalog.commands().size(), entries.getLength());
            for (int i = 0; i < entries.getLength(); i++) {
                String name = ((Element)entries.item(i)).getAttribute("factory-class");
                Class<?> type = bundle.loadClass(name);
                var factory = (DefaultNodeFactory)type.getConstructor().newInstance();
                assertFalse(factory.getNodeName().isBlank());
                Field field = type.getDeclaredField("COMMAND_METADATA");
                field.setAccessible(true);
                var metadata = (EbiCommandMetadata)field.get(null);
                assertTrue("Duplicate command factory " + metadata.commandName, commands.add(metadata.commandName));
                var configuration = factory.createNodeCreationConfig().getPortConfig().orElseThrow();
                assertEquals(metadata.commandName, metadata.inputPortCount(), configuration.getInputPorts().length);
                assertEquals(metadata.commandName, 1, configuration.getOutputPorts().length);
                long expectedOptional = metadata.inputs.stream().filter(p -> p.isPort && p.supportedPortTypes.size() > 1).count()
                    + (metadata.output.supportedPortTypes.size() > 1 ? 1 : 0);
                assertEquals(expectedOptional, configuration.getExtendablePorts().size());
                assertTrue("Exchangeable-only groups have no visible editor controls", configuration.getExchangeablePorts().isEmpty());
                for (var group : configuration.getExtendablePorts().values()) {
                    assertEquals(1, group.getConfiguredPorts().length);
                    assertFalse("Each logical slot is bounded to one port", group.canAddPort());
                }
                for (int port = 0; port < metadata.inputPortCount(); port++) {
                    assertFalse("Logical inputs must be required", configuration.getInputPorts()[port].isOptional());
                    assertArrayEquals(new int[] {port}, configuration.getInputPortLocation().get(EbiPortGroups.inputGroupId(metadata, port)));
                }
                assertArrayEquals(new int[] {0}, configuration.getOutputPortLocation().get(EbiPortGroups.outputGroupId(metadata)));
                assertEquals(EbiPortAdapters.resolvePortType(metadata.output.portType), configuration.getOutputPorts()[0]);
                for (var variant : metadata.variants) {
                    var selected = factory.createNodeCreationConfig().getPortConfig().orElseThrow();
                    for (int port = 0; port < variant.inputPortTypes().size(); port++) {
                        var portType = EbiPortAdapters.resolvePortType(variant.inputPortTypes().get(port));
                        var group = selected.getExtendablePorts().get(EbiPortGroups.inputGroupId(metadata, port));
                        if (group != null) {
                            group.removeLastPort();
                            assertTrue(group.canAddPort());
                            group.addPort(portType);
                            assertFalse(group.canAddPort());
                        }
                        assertEquals(portType, selected.getInputPorts()[port]);
                    }
                    var outputType = EbiPortAdapters.resolvePortType(variant.outputPortType());
                    var outputGroup = selected.getExtendablePorts().get(EbiPortGroups.outputGroupId(metadata));
                    if (outputGroup != null) {
                        outputGroup.removeLastPort();
                        assertTrue(outputGroup.canAddPort());
                        outputGroup.addPort(outputType);
                        assertFalse(outputGroup.canAddPort());
                    }
                    assertEquals(outputType, selected.getOutputPorts()[0]);
                    var saved = new NodeSettings("selected-port-types");
                    selected.saveSettingsTo(saved);
                    var restored = factory.createNodeCreationConfig().getPortConfig().orElseThrow();
                    restored.loadSettingsFrom(saved);
                    assertArrayEquals(metadata.commandName, selected.getInputPorts(), restored.getInputPorts());
                    assertArrayEquals(metadata.commandName, selected.getOutputPorts(), restored.getOutputPorts());
                    testedSignatures++;
                }
            }
        }
        assertEquals(EbiCatalog.commands().keySet(), commands);
        assertEquals(EbiCatalog.commands().values().stream().mapToInt(c -> c.variants.size()).sum(), testedSignatures);
        System.out.println("Verified " + commands.size() + " factories and " + testedSignatures + " complete typed port configurations in OSGi.");
    }

    @Test
    public void optionalPortSlotsPersistChosenTypesWithoutChangingCompletedArity() throws Exception {
        var factory = new org.ryoo.knimeEbi.node.EbiInformationNodeFactory();
        var metadata = EbiCatalog.command("Ebi information");
        var config = factory.createNodeCreationConfig().getPortConfig().orElseThrow();
        var replacement = EbiPortAdapters.resolvePortType("ProcessTreePortObject");
        var group = config.getExtendablePorts().get(EbiPortGroups.inputGroupId(metadata, 0));
        group.removeLastPort();
        group.addPort(replacement);
        assertEquals(1, config.getInputPorts().length);
        assertEquals(replacement, config.getInputPorts()[0]);
        var saved = new NodeSettings("ports");
        config.saveSettingsTo(saved);
        var restored = factory.createNodeCreationConfig().getPortConfig().orElseThrow();
        restored.loadSettingsFrom(saved);
        assertEquals(replacement, restored.getInputPorts()[0]);
        assertArrayEquals(config.getOutputPorts(), restored.getOutputPorts());
    }

    @Test
    public void documentSerializersAreRegisteredAndUseRealKnimePersistence() throws Exception {
        var registry = PortTypeRegistry.getInstance();
        assertTrue(registry.getObjectSerializer(EbiDocumentPortObject.class).isPresent());
        assertTrue(registry.getSpecSerializer(EbiDocumentPortObjectSpec.class).isPresent());
        String text = "stochastic labelled Petri net\n# Überprüfung 😀\n" + "x".repeat(100000);
        var original = new EbiDocumentPortObject(".slpn", text);
        var bytes = new ByteArrayOutputStream();
        PortUtil.writeObjectToStream(original, bytes, new ExecutionMonitor());
        var restored = PortUtil.readObjectFromStream(new ByteArrayInputStream(bytes.toByteArray()), new ExecutionMonitor());
        assertEquals(original, restored);
        assertEquals(original.getSpec(), restored.getSpec());
        assertEquals(text, ((EbiDocumentPortObject)restored).getText());
    }

    @Test
    public void nativeWorkerRunsFromTheOsgiBundleClasspath() throws Exception {
        String xes = "<?xml version=\"1.0\"?><log xes.version=\"1.0\" xmlns=\"http://www.xes-standard.org/\"><trace><event><string key=\"concept:name\" value=\"A\"/></event></trace></log>";
        String result = CallEbi.call_ebi("Ebi convert log", ".xes", new String[] {xes});
        assertTrue(result.contains("<trace>"));
        assertTrue(result.contains("value=\"A\""));
    }
}
