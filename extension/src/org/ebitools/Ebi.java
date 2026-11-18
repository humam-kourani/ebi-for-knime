package org.ebitools;

/** JNI signature from the upstream Ebi JAR. The worker loads a verified native
 *  library by absolute path BEFORE invoking this method. Do not copy the
 *  upstream wrapper's System.loadLibrary()/classpath extraction initializer. */
public final class Ebi {
    private Ebi() { }

    public static native String callEbi(String commandName, String outputFormat, String[] inputs);
}
