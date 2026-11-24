package io.github.izakyl.jpi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import io.github.izakyl.jpi.ai.internal.StrictJson;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Incremental tolerant previews; finish() checks the complete input strictly before tool execution.
 */
public final class StreamingJson {

    private static final class Frame {

        final JsonElement value;
        String key;
        boolean wantsKey;

        Frame(JsonElement value) {
            this.value = value;
            wantsKey = value.isJsonObject();
        }
    }

    private final StringBuilder raw = new StringBuilder();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder scalar = new StringBuilder();

    private JsonElement root;
    private boolean inString;
    private boolean keyString;
    private boolean escaped;
    private boolean done;
    private String unicode;
    private Frame stringParent;
    private Frame scalarParent;
    private String stringKey;
    private String scalarKey;
    private int stringIndex;
    private int scalarIndex;

    public JsonObject append(String delta) {
        raw.append(delta);
        for (int i = 0; i < delta.length() && !done; i++) {
            char character = delta.charAt(i);
            if (inString) {
                stringCharacter(character);
                continue;
            }
            if (!scalar.isEmpty()) {
                if (!isDelimiter(character)) {
                    scalar.append(character);
                    continue;
                }
                flushScalar();
            }
            if (Character.isWhitespace(character)) {
                continue;
            }
            structuralCharacter(character);
        }
        if (inString && !keyString) {
            place(stringParent, stringKey, stringIndex, new JsonPrimitive(text.toString()));
        }
        return snapshot();
    }

    private void structuralCharacter(char character) {
        var parent = stack.peek();
        switch (character) {
            case '"' -> startString(parent);
            case '{', '[' -> openContainer(parent, character);
            case '}', ']' -> closeContainer();
            case ':' -> {
                if (parent != null) {
                    parent.wantsKey = false;
                }
            }
            case ',' -> {
                if (parent != null) {
                    parent.wantsKey = parent.value.isJsonObject();
                }
            }
            default -> startScalar(parent, character);
        }
    }

    private void startString(Frame parent) {
        inString = true;
        keyString = parent != null && parent.value.isJsonObject() && parent.wantsKey;
        text.setLength(0);
        stringParent = parent;
        stringKey = parent == null ? null : parent.key;
        stringIndex = parent != null && parent.value.isJsonArray() ? parent.value.getAsJsonArray().size() : 0;
        if (!keyString) {
            place(parent, stringKey, stringIndex, new JsonPrimitive(""));
        }
    }

    private void openContainer(Frame parent, char character) {
        var value = character == '{' ? new JsonObject() : new JsonArray();
        placeNext(parent, value);
        if (stack.size() >= 256) {
            done = true;
            return;
        }
        stack.push(new Frame(value));
    }

    private void closeContainer() {
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty() && root != null) {
            done = true;
        }
    }

    private void startScalar(Frame parent, char character) {
        scalar.append(character);
        scalarParent = parent;
        scalarKey = parent == null ? null : parent.key;
        scalarIndex = parent != null && parent.value.isJsonArray() ? parent.value.getAsJsonArray().size() : 0;
    }

    private JsonObject snapshot() {
        var pending = scalar.isEmpty() ? null : scalarValue(scalar.toString(), true);
        if (pending == null || scalarParent == null) {
            return copyRoot();
        }
        if (scalarParent.value.isJsonObject()) {
            if (scalarKey == null) {
                return copyRoot();
            }
            var object = scalarParent.value.getAsJsonObject();
            var previous = object.get(scalarKey);
            object.add(scalarKey, pending);
            try {
                return copyRoot();
            } finally {
                if (previous == null) {
                    object.remove(scalarKey);
                } else {
                    object.add(scalarKey, previous);
                }
            }
        }
        var array = scalarParent.value.getAsJsonArray();
        boolean appended = scalarIndex == array.size();
        var previous = appended ? null : array.get(scalarIndex);
        if (appended) {
            array.add(pending);
        } else {
            array.set(scalarIndex, pending);
        }
        try {
            return copyRoot();
        } finally {
            if (appended) {
                array.remove(scalarIndex);
            } else {
                array.set(scalarIndex, previous);
            }
        }
    }

    private JsonObject copyRoot() {
        return root != null && root.isJsonObject() ? root.getAsJsonObject().deepCopy() : new JsonObject();
    }

    public record Arguments(JsonObject arguments, String error) {
    }

    public Arguments finish() {
        try {
            var value = StrictJson.parse(raw.toString());
            if (!value.isJsonObject()) {
                throw new JsonSyntaxException("Tool arguments must be a JSON object");
            }
            return new Arguments(value.getAsJsonObject(), null);
        } catch (RuntimeException error) {
            return new Arguments(
                    new JsonObject(), "Invalid tool argument JSON: " + raw + " (" + error.getMessage() + ")");
        }
    }

    private void stringCharacter(char character) {
        if (unicode != null) {
            if (Character.digit(character, 16) >= 0) {
                unicode += character;
                if (unicode.length() == 4) {
                    text.append((char) Integer.parseInt(unicode, 16));
                    unicode = null;
                }
                return;
            }
            text.append("\\u").append(unicode);
            unicode = null;
        }
        if (escaped) {
            escapedCharacter(character);
        } else if (character == '\\') {
            escaped = true;
        } else if (character == '"') {
            closeString();
        } else {
            text.append(character);
        }
    }

    private void escapedCharacter(char character) {
        escaped = false;
        if (character == 'u') {
            unicode = "";
            return;
        }
        switch (character) {
            case '"', '\\', '/' -> text.append(character);
            case 'b' -> text.append('\b');
            case 'f' -> text.append('\f');
            case 'n' -> text.append('\n');
            case 'r' -> text.append('\r');
            case 't' -> text.append('\t');
            default -> text.append('\\').append(character);
        }
    }

    private void closeString() {
        inString = false;
        if (keyString) {
            stringParent.key = text.toString();
            stringParent.wantsKey = false;
        } else {
            place(stringParent, stringKey, stringIndex, new JsonPrimitive(text.toString()));
            if (stringParent == null) {
                done = true;
            }
        }
    }

    private void flushScalar() {
        var value = scalarValue(scalar.toString(), false);
        if (value != null) {
            place(scalarParent, scalarKey, scalarIndex, value);
        }
        scalar.setLength(0);
        if (scalarParent == null && root != null) {
            done = true;
        }
    }

    private void placeNext(Frame parent, JsonElement value) {
        place(
                parent,
                parent == null ? null : parent.key,
                parent != null && parent.value.isJsonArray() ? parent.value.getAsJsonArray().size() : 0,
                value);
    }

    private void place(Frame parent, String key, int index, JsonElement value) {
        if (parent == null) {
            root = value;
            return;
        }
        if (parent.value.isJsonObject() && key != null) {
            parent.value.getAsJsonObject().add(key, value);
            parent.wantsKey = true;
        } else if (parent.value.isJsonArray()) {
            var array = parent.value.getAsJsonArray();
            if (index == array.size()) {
                array.add(value);
            } else {
                array.set(index, value);
            }
        }
    }

    private static JsonElement scalarValue(String token, boolean partial) {
        for (var literal : new String[] {"true", "false", "null"}) {
            if (literal.equals(token) || (partial && literal.startsWith(token))) {
                return literal.equals("null") ? JsonNull.INSTANCE : new JsonPrimitive(Boolean.parseBoolean(literal));
            }
        }
        if (partial) {
            token = token.replaceFirst("[eE][+-]?$", "");
        }
        if (!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            return null;
        }
        try {
            return new JsonPrimitive(new BigDecimal(token));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isDelimiter(char character) {
        return Character.isWhitespace(character) || ",:{}[]\"".indexOf(character) >= 0;
    }
}
