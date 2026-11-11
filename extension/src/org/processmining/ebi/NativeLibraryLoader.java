package org.processmining.ebi;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lazy extraction, integrity/architecture verification and native loading. */
final class NativeLibraryLoader {
    static final String OVERRIDE_PROPERTY = "knime.ebi.library";
    private static Path loadedLibrary;
    private static Path bundledLibrary;

    private NativeLibraryLoader() { }

    static synchronized void ensureLoaded() {
        if (loadedLibrary != null) {
            return;
        }
        Path library = resolveLibrary();
        try {
            System.load(library.toString());
            loadedLibrary = library;
        } catch (UnsatisfiedLinkError error) {
            throw new IllegalStateException("Unable to load the Ebi Java JNI library " + library
                    + ". Check JVM architecture, JNI exports, glibc and libgcc. "
                    + "A CLI executable or Python library cannot replace the Java library. Cause: "
                    + error.getMessage(), error);
        }
    }

    /** Resolve and verify without loading native code into KNIME's JVM. */
    static synchronized Path resolveLibrary() {
        final NativePlatform platform = NativePlatform.current();
        Path library = null;
        try {
            final String override = System.getProperty(OVERRIDE_PROPERTY);
            if (override != null && !override.isBlank()) {
                library = validateOverride(override);
            } else {
                if (bundledLibrary == null) {
                    bundledLibrary = extractBundled(platform);
                }
                library = bundledLibrary;
            }
            verifyBinary(library, platform);
            return library;
        } catch (IOException | UnsatisfiedLinkError error) {
            throw new IllegalStateException("Unable to load Ebi JNI for " + platform.id + ". "
                    + "Use the supplied Java native library for your KNIME/JVM architecture. "
                    + "On Linux, compatible glibc and libgcc are required. "
                    + (library == null ? "" : "Library: " + library + ". ")
                    + "An existing upstream library may be selected with -D" + OVERRIDE_PROPERTY
                    + "=/absolute/path/to/" + platform.fileName + ". Cause: " + error.getMessage(), error);
        }
    }

    static String description() {
        return "Ebi JNI snapshot 0.3.14-alpha.1; " + System.getProperty("os.name") + "/"
                + System.getProperty("os.arch") + "; "
                + (System.getProperty(OVERRIDE_PROPERTY) == null ? "bundled upstream binary"
                        : "explicit library override (check version separately)");
    }

    static Path validateOverride(final String override) throws IOException {
        final Path path = Path.of(override);
        if (!path.isAbsolute()) {
            throw new IOException("The " + OVERRIDE_PROPERTY + " path must be absolute.");
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("The configured Ebi library is not a readable regular file: " + path);
        }
        return path.toRealPath();
    }

    private static Path extractBundled(final NativePlatform platform) throws IOException {
        final Path directory = Files.createTempDirectory("knime-ebi-native-");
        directory.toFile().deleteOnExit();
        final Path library = directory.resolve(platform.fileName);
        library.toFile().deleteOnExit();
        try (InputStream stream = openResource(platform.resourcePath())) {
            if (stream == null) {
                throw new IOException("Missing bundled resource " + platform.resourcePath());
            }
            Files.copy(stream, library);
        }
        verifyDigest(library, platform.sha256);
        return library.toAbsolutePath();
    }

    /** Supports flat classpaths, nested PDE JARs, and standalone source trees. */
    private static InputStream openResource(final String name) throws IOException {
        final InputStream classpath = CallEbi.class.getResourceAsStream("/" + name);
        if (classpath != null) {
            return classpath;
        }
        // Public OSGi APIs via reflection keep standalone generation JDK-only.
        try {
            final ClassLoader loader = CallEbi.class.getClassLoader();
            final Class<?> framework = Class.forName("org.osgi.framework.FrameworkUtil", false, loader);
            final Object bundle = framework.getMethod("getBundle", Class.class).invoke(null, CallEbi.class);
            if (bundle != null) {
                final Class<?> bundleApi = Class.forName("org.osgi.framework.Bundle", false, loader);
                final URL resource = (URL)bundleApi.getMethod("getEntry", String.class).invoke(bundle, name);
                return resource == null ? null : resource.openStream();
            }
        } catch (ClassNotFoundException ignored) {
            // Eclipse/OSGi is intentionally optional outside KNIME.
        } catch (ReflectiveOperationException error) {
            final Throwable cause = error instanceof InvocationTargetException
                    ? ((InvocationTargetException)error).getTargetException() : error;
            throw new IOException("Cannot resolve Ebi's native resource from the OSGi bundle.", cause);
        }
        final Path sourceTree = Path.of(name);
        return Files.isRegularFile(sourceTree) ? Files.newInputStream(sourceTree) : null;
    }

    static void verifyDigest(final Path file, final String expected) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("This Java runtime does not support SHA-256.", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
            throw new IOException("Ebi native-library checksum mismatch. Reinstall the extension; "
                    + "do not rename a CLI binary as a library.");
        }
    }

    /** Reject wrong architecture, executable files, and the mislabeled upstream PIE. */
    static void verifyBinary(final Path file, final NativePlatform platform) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            final ByteBuffer header = read(channel, 0, 64);
            if (platform.id.startsWith("linux")) {
                if (header.getInt(0) != 0x464c457f || header.get(4) != 2 || header.get(5) != 1
                        || header.getShort(16) != 3 || header.getShort(18) != 62) {
                    throw new IOException("Expected a Linux x86-64 ELF shared library.");
                }
                final long table = header.getLong(32);
                final int entrySize = Short.toUnsignedInt(header.getShort(54));
                final int count = Short.toUnsignedInt(header.getShort(56));
                if (entrySize < 56 || count == 0 || table < 64
                        || table + (long)entrySize * count > channel.size()) {
                    throw new IOException("Invalid ELF program headers in Ebi native library.");
                }
                for (int index = 0; index < count; index++) {
                    if (read(channel, table + (long)index * entrySize, 4).getInt() == 3) {
                        throw new IOException("The supplied ELF contains an executable interpreter "
                                + "(PIE/CLI binary), not a loadable JNI shared library.");
                    }
                }
            } else {
                if (header.getShort(0) != 0x5a4d) {
                    throw new IOException("Expected a Windows x86-64 DLL.");
                }
                final ByteBuffer pe = read(channel, Integer.toUnsignedLong(header.getInt(60)), 24);
                if (pe.getInt(0) != 0x00004550 || Short.toUnsignedInt(pe.getShort(4)) != 0x8664
                        || (Short.toUnsignedInt(pe.getShort(22)) & 0x2000) == 0) {
                    throw new IOException("Expected a Windows x86-64 DLL, not an executable.");
                }
            }
        }
    }

    private static ByteBuffer read(final FileChannel channel, final long offset, final int size)
            throws IOException {
        if (offset < 0 || offset > channel.size() - size) {
            throw new IOException("Truncated Ebi native library.");
        }
        final ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) > 0) {
            // Positional reads tolerate short reads without changing channel state.
        }
        if (buffer.hasRemaining()) {
            throw new IOException("Truncated Ebi native library.");
        }
        return buffer.flip();
    }
}
