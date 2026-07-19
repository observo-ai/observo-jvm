package ai.observo.manifest;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON writer — just enough to emit an
 * {@link LinkManifest}. Deliberately not a general JSON library: it only
 * models the value shapes the manifest needs (string, number, null, object,
 * array) so this artifact can be dropped onto any client's test runtime
 * without dragging in Jackson/Gson to clash with theirs.
 *
 * <p>Objects preserve insertion order and render with 2-space indentation to
 * match the CLI's {@code json.Encoder} + {@code SetIndent("", "  ")} shape, so
 * a listener-emitted manifest and a CLI-derived one diff cleanly. The output
 * is valid JSON; exact byte parity with the Go encoder is neither promised nor
 * needed — every consumer parses it.
 */
final class Json {

    private Json() {
    }

    /** A renderable JSON value. */
    interface Value {
        void writeTo(Writer w, int indent) throws IOException;
    }

    static Value string(String s) {
        return (w, indent) -> w.write(quote(s));
    }

    static Value number(long n) {
        return (w, indent) -> w.write(Long.toString(n));
    }

    static final Value NULL = (w, indent) -> w.write("null");

    /** A JSON object with insertion-ordered keys. */
    static final class Obj implements Value {
        private final Map<String, Value> fields = new LinkedHashMap<>();

        Obj put(String key, Value value) {
            fields.put(key, value);
            return this;
        }

        Obj put(String key, String value) {
            return put(key, string(value));
        }

        Obj put(String key, long value) {
            return put(key, number(value));
        }

        /** Emits {@code key: null} when the value is null, else a string. */
        Obj putNullable(String key, String value) {
            return put(key, value == null ? NULL : string(value));
        }

        /** Emits the field only when the value is non-null and non-empty —
         * the JVM-side equivalent of Go's {@code json:",omitempty"}. */
        Obj putOmitEmpty(String key, String value) {
            if (value != null && !value.isEmpty()) {
                put(key, value);
            }
            return this;
        }

        @Override
        public void writeTo(Writer w, int indent) throws IOException {
            if (fields.isEmpty()) {
                w.write("{}");
                return;
            }
            w.write("{\n");
            String pad = "  ".repeat(indent + 1);
            int i = 0;
            for (Map.Entry<String, Value> e : fields.entrySet()) {
                w.write(pad);
                w.write(quote(e.getKey()));
                w.write(": ");
                e.getValue().writeTo(w, indent + 1);
                if (++i < fields.size()) {
                    w.write(",");
                }
                w.write("\n");
            }
            w.write("  ".repeat(indent));
            w.write("}");
        }
    }

    /** A JSON array. */
    static final class Arr implements Value {
        private final List<Value> items = new ArrayList<>();

        Arr add(Value value) {
            items.add(value);
            return this;
        }

        @Override
        public void writeTo(Writer w, int indent) throws IOException {
            if (items.isEmpty()) {
                w.write("[]");
                return;
            }
            w.write("[\n");
            String pad = "  ".repeat(indent + 1);
            for (int i = 0; i < items.size(); i++) {
                w.write(pad);
                items.get(i).writeTo(w, indent + 1);
                if (i + 1 < items.size()) {
                    w.write(",");
                }
                w.write("\n");
            }
            w.write("  ".repeat(indent));
            w.write("]");
        }
    }

    /** Returns {@code s} as a quoted, escaped JSON string literal. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
