package org.ryoo.knimeEbi.scaffolder;

import javax.lang.model.SourceVersion;

public final class JavaSourceUtil {
    private JavaSourceUtil() { }
    public static boolean isJavaIdentifier(final String value) {
        return value != null && SourceVersion.isIdentifier(value) && !SourceVersion.isKeyword(value)
            && !value.equals("_") && !value.equals("record") && !value.equals("var") && !value.equals("yield")
            && !value.equals("sealed") && !value.equals("permits");
    }
    public static String escapeJavaString(final String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\"); case '"' -> result.append("\\\"");
                case '\r' -> result.append("\\r"); case '\n' -> result.append("\\n"); case '\t' -> result.append("\\t");
                case '\b' -> result.append("\\b"); case '\f' -> result.append("\\f");
                default -> {
                    if (c < 32 || c == 127) result.append(String.format("\\%03o", (int)c));
                    else result.append(c);
                }
            }
        }
        return result.toString();
    }
}
