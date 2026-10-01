import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal JSON writer for API responses (requests arrive form-encoded, so no parser is needed). */
final class Json {
    private Json() {}

    /** Ordered object literal: Json.obj("a", 1, "b", "x"). */
    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            quote(sb, s);
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (!Double.isFinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object item : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
