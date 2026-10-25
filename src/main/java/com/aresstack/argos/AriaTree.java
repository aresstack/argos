package com.aresstack.argos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed form of Playwright's aria snapshot YAML ({@code page.ariaSnapshot()}).
 *
 * <p>The snapshot is computed by Playwright's injected script in its isolated utility world, so reading it
 * never runs code in the website's own JavaScript realm. This class only parses the resulting text.
 */
public final class AriaTree {
    private static final Pattern LINE = Pattern.compile("^(\\s*)- (.*)$");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\[([a-z-]+)(?:=([^\\]]*))?\\]");

    private final List<Node> roots;

    private AriaTree(List<Node> roots) {
        this.roots = List.copyOf(roots);
    }

    /** One accessibility node. {@code ref} is set only in {@code AriaSnapshotMode.AI} snapshots. */
    public static final class Node {
        private final String role;
        private final String name;
        private final Map<String, String> attributes;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final List<Node> children = new ArrayList<>();
        private String text;

        Node(String role, String name, Map<String, String> attributes, String text) {
            this.role = role;
            this.name = name == null ? "" : name;
            this.attributes = attributes;
            this.text = text == null ? "" : text;
        }

        public String role() { return role; }
        public String name() { return name; }
        /** Inline text after the colon, for example the value of a textbox or a paragraph's text. */
        public String text() { return text; }
        public String ref() { return attributes.getOrDefault("ref", ""); }
        public boolean has(String attribute) { return attributes.containsKey(attribute); }
        public String attribute(String attribute) { return attributes.getOrDefault(attribute, ""); }
        /** Snapshot properties such as {@code /url} (stored without the slash). */
        public String property(String key) { return properties.getOrDefault(key, ""); }
        public List<Node> children() { return children; }

        /** Accessible name, else inline text, else the joined text of all descendants. */
        public String label() {
            if (!name.isBlank()) return name;
            if (!text.isBlank()) return text;
            StringBuilder joined = new StringBuilder();
            for (Node child : children) {
                String part = child.label();
                if (!part.isBlank()) joined.append(joined.isEmpty() ? "" : " ").append(part);
            }
            return joined.toString();
        }
    }

    public List<Node> roots() {
        return roots;
    }

    /** Depth-first visit of every node in document order. */
    public void visit(Consumer<Node> visitor) {
        roots.forEach(root -> visit(root, visitor));
    }

    private static void visit(Node node, Consumer<Node> visitor) {
        visitor.accept(node);
        node.children.forEach(child -> visit(child, visitor));
    }

    public List<Node> findAll(java.util.function.Predicate<Node> condition) {
        List<Node> found = new ArrayList<>();
        visit(node -> { if (condition.test(node)) found.add(node); });
        return found;
    }

    public static AriaTree parse(String snapshot) {
        List<Node> roots = new ArrayList<>();
        // Stack of (indent, node); a line belongs to the closest less-indented node.
        List<Integer> indents = new ArrayList<>();
        List<Node> stack = new ArrayList<>();
        if (snapshot == null) return new AriaTree(roots);
        for (String raw : snapshot.split("\n")) {
            Matcher line = LINE.matcher(raw);
            if (!line.matches()) continue;
            int indent = line.group(1).length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            Node parent = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            String content = line.group(2);
            if (content.startsWith("/")) {
                int colon = content.indexOf(':');
                if (parent != null && colon > 1) {
                    parent.properties.put(content.substring(1, colon), unquote(content.substring(colon + 1).trim()));
                }
                continue;
            }
            Node node = parseNode(content);
            if (node == null) continue;
            if (parent == null) roots.add(node); else parent.children.add(node);
            indents.add(indent);
            stack.add(node);
        }
        return new AriaTree(roots);
    }

    private static Node parseNode(String content) {
        String key;
        String rest;
        if (content.startsWith("'")) {
            int end = closingSingleQuote(content);
            if (end < 0) return null;
            key = content.substring(1, end).replace("''", "'");
            rest = content.substring(end + 1);
        } else {
            int split = keyEnd(content);
            key = content.substring(0, split);
            rest = content.substring(split);
        }
        String text = "";
        rest = rest.trim();
        if (rest.startsWith(":")) text = unquote(rest.substring(1).trim());

        if (key.equals("text")) return new Node("text", "", Map.of(), text);
        int space = key.indexOf(' ');
        String role = (space < 0 ? key : key.substring(0, space)).toLowerCase(Locale.ROOT);
        String remainder = space < 0 ? "" : key.substring(space + 1).trim();
        String name = "";
        if (remainder.startsWith("\"")) {
            int end = closingDoubleQuote(remainder);
            if (end > 0) {
                name = unquote(remainder.substring(0, end + 1));
                remainder = remainder.substring(end + 1);
            }
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher attribute = ATTRIBUTE.matcher(remainder);
        while (attribute.find()) {
            attributes.put(attribute.group(1), attribute.group(2) == null ? "" : attribute.group(2));
        }
        return new Node(role, name, attributes, text);
    }

    /** End of the YAML key: the first colon outside a double-quoted name. */
    private static int keyEnd(String content) {
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && quoted) { i++; continue; }
            if (c == '"') quoted = !quoted;
            if (c == ':' && !quoted) return i;
        }
        return content.length();
    }

    private static int closingSingleQuote(String content) {
        for (int i = 1; i < content.length(); i++) {
            if (content.charAt(i) != '\'') continue;
            if (i + 1 < content.length() && content.charAt(i + 1) == '\'') { i++; continue; }
            return i;
        }
        return -1;
    }

    private static int closingDoubleQuote(String content) {
        for (int i = 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                if (c == '\\' && i + 1 < value.length() - 1) {
                    char next = value.charAt(++i);
                    switch (next) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (i + 4 < value.length()) {
                                out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                                i += 4;
                            }
                        }
                        default -> out.append(next);
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }
}
