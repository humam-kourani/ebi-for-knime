package org.ryoo.knimeEbi.util;

import java.util.HashSet;
import java.util.List;

/** CSV event-log data accepted by Ebi (trace ID in column 1, activity in column 2). */
public final class EbiCsv {
    private EbiCsv() { }

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
