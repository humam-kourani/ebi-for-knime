package org.ryoo.knimeEbi.util;

import java.util.HashSet;
import java.util.List;

/** CSV event-log data accepted by Ebi (trace ID in column 1, activity in column 2). */
public final class EbiCsv {
    private EbiCsv() { }

    public static final String CASE_ID_COLUMN = "case:concept:name";
    public static final String ACTIVITY_COLUMN = "concept:name";
    public static final String RESOURCE_COLUMN = "org:resource";
    public static final String TIMESTAMP_COLUMN = "time:timestamp";

    /**
     * Ebi's default CSV importer uses positional columns 0 and 1 for case ID and activity.
     * Event-attribute-aware imports may also use positions 2 and 3 for resource and time.
     * Never change the user's table; report unexpected headers as non-blocking warnings.
     */
    public static List<String> headerWarnings(final List<String> headers) {
        if (headers == null) return List.of();
        List<String> warnings = new java.util.ArrayList<>();
        warnHeader(warnings, headers, 0, CASE_ID_COLUMN, "case ID", false);
        warnHeader(warnings, headers, 1, ACTIVITY_COLUMN, "activity", false);
        warnHeader(warnings, headers, 2, RESOURCE_COLUMN, "resource", true);
        warnHeader(warnings, headers, 3, TIMESTAMP_COLUMN, "timestamp", true);
        return List.copyOf(warnings);
    }

    private static void warnHeader(final List<String> warnings, final List<String> headers,
            final int index, final String expected, final String role, final boolean optional) {
        if (index >= headers.size() || expected.equals(headers.get(index))) return;
        String prefix = optional ? "For event-attribute CSV imports, Ebi may interpret "
            : "By default, Ebi interprets ";
        warnings.add(prefix + "column " + (index + 1) + " ('" + headers.get(index)
            + "') as " + role + "; expected header '" + expected
            + "'. The KNIME table is sent unchanged; check its column order.");
    }

    public static final class Writer {
        private final StringBuilder text = new StringBuilder(4096);
        private final int columnCount;
        private int events;

        public Writer(final List<String> header) {
            if (header == null || header.size() < 2)
                throw new IllegalArgumentException("Ebi CSV requires at least two columns: trace ID and activity.");
            if (header.stream().anyMatch(name -> name == null || name.isBlank())
                    || new HashSet<>(header).size() != header.size())
                throw new IllegalArgumentException("CSV column names must be distinct and nonempty.");
            columnCount = header.size();
            appendFields(header);
        }

        public void append(final List<String> fields) {
            if (fields == null || fields.size() != columnCount)
                throw new IllegalArgumentException("CSV event " + (events + 1) + " has the wrong column count.");
            if (fields.get(0) == null || fields.get(0).isBlank()
                    || fields.get(1) == null || fields.get(1).isBlank())
                throw new IllegalArgumentException("CSV event " + (events + 1) + " needs a trace ID and activity.");
            appendFields(fields);
            events++;
        }

        public String finish() {
            if (events < 2) throw new IllegalArgumentException("Ebi CSV requires at least two event rows.");
            return text.toString();
        }

        private void appendFields(final List<String> fields) {
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) text.append(',');
                String value = fields.get(i);
                text.append('"').append((value == null ? "" : value).replace("\"", "\"\"")).append('"');
            }
            text.append('\n');
        }
    }
}
