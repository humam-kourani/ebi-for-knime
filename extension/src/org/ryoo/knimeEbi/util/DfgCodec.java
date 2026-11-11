package org.ryoo.knimeEbi.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact Ebi .dfg JSON codec. Fractional weights are retained until PM4KNIME conversion. */
public final class DfgCodec {
    private DfgCodec() { }
    public record Edge(String source, String target, String weight) { }
    public record Graph(Map<String, String> activities, List<Edge> edges,
            List<String> starts, List<String> ends) {
        public Graph {
            activities = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(activities));
            edges = List.copyOf(edges); starts = List.copyOf(starts); ends = List.copyOf(ends);
            for (Map.Entry<String, String> e : activities.entrySet()) positiveWeight(e.getValue());
            Set<String> pairs = new java.util.HashSet<>();
            for (Edge edge : edges) {
                requireActivity(activities, edge.source()); requireActivity(activities, edge.target());
                positiveWeight(edge.weight());
                if (!pairs.add(edge.source().length() + ":" + edge.source() + edge.target()))
                    throw new IllegalArgumentException("Duplicate directly-follows relation.");
            }
            for (String start : starts) requireActivity(activities, start);
            for (String end : ends) requireActivity(activities, end);
            if (new LinkedHashSet<>(starts).size() != starts.size() || new LinkedHashSet<>(ends).size() != ends.size())
                throw new IllegalArgumentException("Duplicate DFG boundary activity.");
        }
    }

    private static void requireActivity(Map<String, String> activities, String name) {
        if (!activities.containsKey(name)) throw new IllegalArgumentException("DFG references an unknown activity: " + name);
    }
    private static BigInteger[] positiveWeight(final String weight) {
        if (weight == null || !weight.matches("[0-9]+(?:/[0-9]+)?"))
            throw new IllegalArgumentException("Unsupported DFG weight: " + weight);
        String[] parts = weight.split("/");
        BigInteger numerator = new BigInteger(parts[0]);
        BigInteger denominator = parts.length == 1 ? BigInteger.ONE : new BigInteger(parts[1]);
        if (numerator.signum() < 0 || denominator.signum() <= 0) throw new IllegalArgumentException("Invalid DFG weight: " + weight);
        return new BigInteger[] {numerator, denominator};
    }
    /** PM4KNIME stores integral long counts; refuse lossy rounding and overflow. */
    public static long exactCount(final String weight) {
        BigInteger[] fraction = positiveWeight(weight);
        BigInteger[] result = fraction[0].divideAndRemainder(fraction[1]);
        if (result[1].signum() != 0)
            throw new IllegalArgumentException("DFG weight " + weight + " cannot be represented by PM4KNIME integer counts. Select Ebi document output to preserve it.");
        try { return result[0].longValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("DFG weight exceeds the PM4KNIME long-count range. Select Ebi document output.", ex); }
    }

    public static Graph parse(final String text) {
        Object value = new Parser(text).parse();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected an Ebi .dfg JSON object.");
        if (!map.keySet().equals(Set.of("activities", "directly_follows_relations", "start_activities", "end_activities")))
            throw new IllegalArgumentException("Unexpected Ebi .dfg fields; refusing an incompatible graph format.");
        if (!(map.get("activities") instanceof Map<?, ?> activityMap)) throw new IllegalArgumentException("Missing DFG activities.");
        Map<String, String> activities = new LinkedHashMap<>();
        activityMap.forEach((key, item) -> activities.put(string(key), string(item)));
        List<Edge> edges = new ArrayList<>();
        for (Object item : array(map.get("directly_follows_relations"))) {
            List<?> pair = array(item);
            if (pair.size() != 2) throw new IllegalArgumentException("Invalid directly-follows relation.");
            List<?> endpoints = array(pair.get(0));
            if (endpoints.size() != 2) throw new IllegalArgumentException("Invalid directly-follows endpoints.");
            edges.add(new Edge(string(endpoints.get(0)), string(endpoints.get(1)), string(pair.get(1))));
        }
        return new Graph(activities, edges, strings(map.get("start_activities")), strings(map.get("end_activities")));
    }
    private static List<?> array(Object value) {
        if (!(value instanceof List<?> result)) throw new IllegalArgumentException("Expected a JSON array.");
        return result;
    }
    private static String string(Object value) {
        if (!(value instanceof String result)) throw new IllegalArgumentException("Expected a JSON string.");
        return result;
    }
    private static List<String> strings(Object value) { return array(value).stream().map(DfgCodec::string).toList(); }

    public static String write(final Graph graph) {
        StringBuilder out = new StringBuilder("{\"activities\":{");
        boolean first = true;
        for (Map.Entry<String, String> item : graph.activities().entrySet()) {
            if (!first) out.append(','); first = false;
            out.append(quote(item.getKey())).append(':').append(quote(item.getValue()));
        }
        out.append("},\"directly_follows_relations\":["); first = true;
        for (Edge edge : graph.edges()) {
            if (!first) out.append(','); first = false;
            out.append("[[").append(quote(edge.source())).append(',').append(quote(edge.target()))
                .append("],").append(quote(edge.weight())).append(']');
        }
        out.append("],\"start_activities\":").append(stringArray(graph.starts()))
            .append(",\"end_activities\":").append(stringArray(graph.ends())).append('}');
        return out.toString();
    }
    private static String stringArray(List<String> values) { return "[" + String.join(",", values.stream().map(DfgCodec::quote).toList()) + "]"; }
    static String quote(final String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    /** A deliberately small JSON parser for the all-string Ebi graph schema. */
    private static final class Parser {
        private final String text; private int cursor;
        Parser(String text) { if (text == null) throw new IllegalArgumentException("Null DFG output."); this.text = text; }
        Object parse() { Object result = value(0); ws(); if (cursor != text.length()) fail(); return result; }
        Object value(int depth) {
            if (depth > 8) throw new IllegalArgumentException("DFG JSON nesting exceeds the graph schema.");
            ws(); if (cursor == text.length()) return fail();
            char c = text.charAt(cursor++);
            if (c == '"') return readString();
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>(); ws();
                if (take('}')) return map;
                do {
                    ws(); if (!take('"')) return fail(); String key = readString(); ws();
                    if (!take(':')) return fail(); Object v = value(depth + 1);
                    if (map.putIfAbsent(key, v) != null) throw new IllegalArgumentException("Duplicate DFG JSON key: " + key);
                    ws(); if (take('}')) return map;
                } while (take(','));
                return fail();
            }
            if (c == '[') {
                List<Object> values = new ArrayList<>(); ws(); if (take(']')) return values;
                do { values.add(value(depth + 1)); ws(); if (take(']')) return values; } while (take(','));
                return fail();
            }
            return fail();
        }
        String readString() {
            StringBuilder out = new StringBuilder();
            while (cursor < text.length()) {
                char c = text.charAt(cursor++); if (c == '"') return out.toString();
                if (c < 32) return fail();
                if (c != '\\') { out.append(c); continue; }
                if (cursor == text.length()) return fail();
                switch (text.charAt(cursor++)) {
                    case '"' -> out.append('"'); case '\\' -> out.append('\\'); case '/' -> out.append('/');
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> {
                        if (cursor + 4 > text.length()) return fail();
                        try { out.append((char)Integer.parseInt(text.substring(cursor, cursor + 4), 16)); }
                        catch (NumberFormatException ex) { return fail(); }
                        cursor += 4;
                    }
                    default -> { return fail(); }
                }
            }
            return fail();
        }
        boolean take(char c) { if (cursor < text.length() && text.charAt(cursor) == c) { cursor++; return true; } return false; }
        void ws() { while (cursor < text.length() && " \n\r\t".indexOf(text.charAt(cursor)) >= 0) cursor++; }
        <T> T fail() { throw new IllegalArgumentException("Invalid Ebi .dfg JSON near character " + cursor + "."); }
    }
}
