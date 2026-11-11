package org.processmining.ebi;

import java.util.Locale;

/** Platforms with upstream Java binaries included in this extension. */
final class NativePlatform {
    final String id;
    final String fileName;
    final String sha256;

    private NativePlatform(final String id, final String fileName, final String sha256) {
        this.id = id;
        this.fileName = fileName;
        this.sha256 = sha256;
    }

    static NativePlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static NativePlatform detect(final String osName, final String architecture) {
        final String os = osName.toLowerCase(Locale.ROOT);
        final String arch = architecture.toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            throw unsupported(osName, architecture);
        }
        if (os.startsWith("windows")) {
            return new NativePlatform("windows-x86_64", "ebi.dll",
                    "af5bbe281aa7d9a486308b93d288aff3b7d9d9555cff5bf348a0ef37126d17f8");
        }
        if (os.equals("linux")) {
            return new NativePlatform("linux-x86_64", "libebi.so",
                    "e87a2a7a74f9599787d3aa3e52257cb0aa34a5c15ce910c2f0bac1865c63a846");
        }
        throw unsupported(osName, architecture);
    }

    String resourcePath() {
        return "lib/" + id + "/" + fileName;
    }

    private static UnsupportedOperationException unsupported(final String os, final String arch) {
        return new UnsupportedOperationException("Ebi JNI is not supplied for " + os + " / " + arch
                + ". This extension uses upstream Windows x86-64 and Linux x86-64 binaries. "
                + "Upstream macOS documentation requires self-compilation; this extension does not "
                + "build Ebi or claim macOS/ARM support. Use a supported 64-bit KNIME installation.");
    }
}
