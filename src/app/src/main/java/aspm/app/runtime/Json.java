package aspm.app.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal JSON, written rather than depended upon. ADR-057.
 *
 * <p>ADR-057 accepts that routing, content negotiation and body parsing become the platform's code and
 * therefore the platform's defects. This is the body-parsing half of that cost, and it is bounded
 * deliberately: objects, arrays, strings, numbers, booleans and null. No streaming, no binding to types,
 * no reflection.
 *
 * <p><b>The parser rejects rather than coerces.</b> A lenient parser is where "unknown fields rejected, not
 * ignored" ({@code PRD-API-011}) quietly stops holding: a field the parser silently drops never reaches
 * {@link aspm.app.api.RequestValidation#rejectUnknownFields}, so the request is accepted with a field the
 * caller believes was applied. Every limit here fails loudly for that reason.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(out, s);
            case Boolean b -> out.append(b.toString());
            case Number n -> out.append(n.toString());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    writeValue(out, entry.getValue());
                }
                out.append('}');
            }
            case Iterable<?> items -> {
                out.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeValue(out, item);
                }
                out.append(']');
            }
            // A type this writer does not know becomes its toString(), which is how an internal class
            // name reaches a response body. PRD-UIX-025 keeps reconnaissance out of error surfaces and a
            // representation is an output surface too, so this fails instead.
            default -> throw new IllegalArgumentException(
                    "no JSON representation for " + value.getClass().getSimpleName()
                            + ". Convert it at the resource boundary rather than letting toString() decide "
                            + "what a client sees.");
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Escape everything below space, and also U+2028/U+2029: they are valid in JSON and
                    // terminate a line in JavaScript, which turns an embedded finding title into a script
                    // break in any consumer that eval()s the response.
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------------ reading

    /** Parses a JSON object. Anything else at the top level is rejected. */
    public static Map<String, Object> readObject(String text) {
        Objects.requireNonNull(text, "a body is required");
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("trailing content after the JSON value at offset "
                    + parser.position());
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("a JSON object is required at the top level");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    private static final class Parser {

        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        int position() {
            return at;
        }

        boolean atEnd() {
            return at >= text.length();
        }

        void skipWhitespace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        Object value(int depth) {
            // RequestValidation.MAX_DEPTH exists because a deeply nested body is a denial-of-service on
            // any recursive parser, and this one is recursive.
            if (depth > aspm.app.api.RequestValidation.MAX_DEPTH) {
                throw new IllegalArgumentException("body nesting exceeds "
                        + aspm.app.api.RequestValidation.MAX_DEPTH + " levels");
            }
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of body");
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> result = new LinkedHashMap<>();
            at++;
            skipWhitespace();
            if (!atEnd() && text.charAt(at) == '}') {
                at++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                Object v = value(depth + 1);
                // A duplicate key is rejected rather than last-one-wins. Last-one-wins is a request
                // smuggling primitive whenever two components parse the same body: the validator sees one
                // value and the handler another.
                if (result.put(key, v) != null) {
                    throw new IllegalArgumentException("duplicate field '" + key + "' in the body");
                }
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated object");
                }
                if (text.charAt(at) == ',') {
                    at++;
                    continue;
                }
                expect('}');
                return result;
            }
        }

        private List<Object> array(int depth) {
            List<Object> result = new ArrayList<>();
            at++;
            skipWhitespace();
            if (!atEnd() && text.charAt(at) == ']') {
                at++;
                return result;
            }
            while (true) {
                result.add(value(depth + 1));
                if (result.size() > aspm.app.api.RequestValidation.MAX_ELEMENTS) {
                    throw new IllegalArgumentException("body exceeds "
                            + aspm.app.api.RequestValidation.MAX_ELEMENTS + " elements");
                }
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated array");
                }
                if (text.charAt(at) == ',') {
                    at++;
                    continue;
                }
                expect(']');
                return result;
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated escape");
                }
                char esc = text.charAt(at++);
                switch (esc) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw new IllegalArgumentException("truncated unicode escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("unknown escape \\" + esc);
                }
            }
        }

        private Boolean bool() {
            if (text.startsWith("true", at)) {
                at += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", at)) {
                at += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("malformed literal at offset " + at);
        }

        private Object nul() {
            if (text.startsWith("null", at)) {
                at += 4;
                return null;
            }
            throw new IllegalArgumentException("malformed literal at offset " + at);
        }

        private Number number() {
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            String raw = text.substring(start, at);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("unexpected character at offset " + at);
            }
            try {
                if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                    return Double.valueOf(raw);
                }
                return Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed number '" + raw + "'");
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (atEnd() || text.charAt(at) != expected) {
                throw new IllegalArgumentException("expected '" + expected + "' at offset " + at);
            }
            at++;
        }
    }
}
