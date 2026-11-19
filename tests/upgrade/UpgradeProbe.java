import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;

/** Separate JVM phases deliberately retain the same OSGi and extension-registry storage. */
public final class UpgradeProbe {
    private static final String ID = "test.ebi.upgrade";
    private static final String ACCESS = "org.processmining.ebi.LoaderAccess";
    private UpgradeProbe() { }

    private static void equal(final Object expected, final Object actual, final String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static String resource(final Class<?> loader, final String name) throws Exception {
        return (String)loader.getMethod("resource", String.class).invoke(null, name);
    }

    private static void verify(final Bundle bundle, final Bundle registry, final String marker) throws Exception {
        equal(marker.equals("v1") ? "1.0.0" : "1.1.0", bundle.getVersion().toString(), "Bundle version");
        var loader = bundle.loadClass(ACCESS);
        equal(marker, loader.getMethod("codeMarker").invoke(null), "Loaded bytecode version");
        equal(marker, resource(loader, "lib/shared-marker.txt"), "Production loader's resource revision");
        var context = registry.getBundleContext();
        var reference = context.getServiceReference("org.eclipse.core.runtime.IExtensionRegistry");
        if (reference == null) { throw new AssertionError("Extension registry service is unavailable"); }
        try {
            var service = context.getService(reference);
            var api = registry.loadClass("org.eclipse.core.runtime.IExtensionRegistry");
            var elementApi = registry.loadClass("org.eclipse.core.runtime.IConfigurationElement");
            var elements = (Object[])api.getMethod("getConfigurationElementsFor", String.class)
                    .invoke(service, ID + ".nodes");
            Set<String> actual = new HashSet<>();
            for (var element : elements) {
                actual.add((String)elementApi.getMethod("getAttribute", String.class).invoke(element, "id"));
            }
            equal(marker.equals("v1") ? Set.of("existing-v1") : Set.of("existing-v2", "new-node"),
                    actual, "Live extension registry must include updated and new nodes");
        } finally {
            context.ungetService(reference);
        }
    }

    public static void main(final String[] args) throws Exception {
        String phase = args[0];
        Path work = Path.of(args[1]);
        // The test must never acquire a clean/uncached launch through inherited options.
        for (String flag : List.of("osgi.clean", "osgi.checkConfiguration", "eclipse.noRegistryCache")) {
            if (Boolean.getBoolean(flag)) { throw new AssertionError("Forbidden test launch flag: " + flag); }
        }
        var settings = new HashMap<String, String>();
        settings.put(Constants.FRAMEWORK_STORAGE, work.resolve("storage").toString());
        settings.put("osgi.checkConfiguration", "false");
        Framework framework = new org.eclipse.osgi.launch.EquinoxFactory().newFramework(settings);
        framework.init();
        framework.start();
        try {
            var context = framework.getBundleContext();
            for (int index = 2; index < args.length; index++) {
                context.installBundle(Path.of(args[index]).toUri().toString()).start();
            }
            Bundle registry = Arrays.stream(context.getBundles())
                    .filter(b -> "org.eclipse.equinox.registry".equals(b.getSymbolicName())).findFirst().orElseThrow();
            Bundle bundle = Arrays.stream(context.getBundles()).filter(b -> ID.equals(b.getSymbolicName()))
                    .findFirst().orElse(null);
            if (phase.equals("seed")) {
                if (bundle != null) { throw new AssertionError("Seed phase must start with fresh storage"); }
                bundle = context.installBundle("reference:" + work.resolve("v1.jar").toUri());
                bundle.start();
                // An importing bundle retains the old wiring during the later update.
                var dependent = context.installBundle("reference:" + work.resolve("dependent.jar").toUri());
                dependent.start();
                dependent.loadClass(ACCESS);
            } else if (bundle == null) {
                throw new AssertionError("Cached installation was lost between JVMs");
            }
            if (phase.equals("upgrade")) {
                verify(bundle, registry, "v1");
                Class<?> oldLoader = bundle.loadClass(ACCESS);
                try (var input = Files.newInputStream(work.resolve("v2.jar"))) { bundle.update(input); }
                // A current Bundle entry is v2 while retained bytecode/resources are v1.
                try (var current = bundle.getEntry("lib/new-only-marker.txt").openStream()) {
                    equal("v2", new String(current.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                            "Fixture must expose the new entry through the updated bundle");
                }
                equal("v1", resource(oldLoader, "lib/shared-marker.txt"), "Retained wiring resource");
                equal(null, resource(oldLoader, "lib/new-only-marker.txt"),
                        "Old Java bridge must never fall through to current Bundle.getEntry native bytes");
                var finished = new CountDownLatch(1);
                framework.adapt(FrameworkWiring.class).refreshBundles(List.of(bundle), event -> {
                    if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED) { finished.countDown(); }
                });
                if (!finished.await(30, TimeUnit.SECONDS)) { throw new AssertionError("Bundle refresh timed out"); }
            }
            verify(bundle, registry, phase.equals("seed") ? "v1" : "v2");
            System.out.println("UPGRADE_TEST_PHASE=" + phase + ": passed");
        } finally {
            framework.stop();
            if (framework.waitForStop(30000).getType() == FrameworkEvent.WAIT_TIMEDOUT) {
                throw new AssertionError("Framework did not stop and save its caches");
            }
        }
    }
}
