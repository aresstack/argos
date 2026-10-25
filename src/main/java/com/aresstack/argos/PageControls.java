package com.aresstack.argos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numbered list of the operable controls of one page, built from an AI-mode aria snapshot, and the rules that
 * turn a target such as {@code 3}, {@code e12}, {@code role=button name="Suchen"} or {@code Suchen} into exactly
 * one control (after the Enterprise {@code ControlLocator}: equal name first, then a unique partial match).
 */
public final class PageControls {
    /** Roles a person can operate; links are listed separately by link tools but are still valid targets. */
    static final Set<String> CONTROL_ROLES = Set.of(
            "button", "textbox", "searchbox", "combobox", "checkbox", "radio", "switch", "slider",
            "spinbutton", "tab", "menuitem", "menuitemcheckbox", "menuitemradio", "option", "listbox", "treeitem");
    static final Set<String> EDITABLE_ROLES = Set.of("textbox", "searchbox", "combobox", "spinbutton");

    private static final Pattern NUMBER = Pattern.compile("\\[?#?(\\d{1,4})]?");
    private static final Pattern REF = Pattern.compile("(?:ref=|aria-ref=)?(e\\d+|f\\d+e\\d+)");
    private static final Pattern ROLE = Pattern.compile("role\\s*=\\s*([a-zA-Z]+)(?:\\s+name\\s*=\\s*\"(.*)\")?\\s*");
    private static final Pattern NAMED = Pattern.compile("(label|text|placeholder|name)\\s*=\\s*\"(.*)\"\\s*");

    /** One control; {@code number} is what compact tools show, {@code ref} the Playwright aria ref. */
    public record Control(int number, String role, String name, String value, String ref, List<String> states) {
        /** The semantic locator a model can reuse: {@code role=button name="Suchen"}. */
        public String locator() {
            return name.isBlank() ? "ref=" + ref : "role=" + role + " name=\"" + name.replace("\"", "\\\"") + "\"";
        }

        /** Compact line for small models: {@code [3] searchbox "Suchen" = Wien (required)}. */
        public String line() {
            StringBuilder out = new StringBuilder("[").append(number).append("] ").append(role);
            if (!name.isBlank()) out.append(" \"").append(name).append('"');
            if (!value.isBlank()) out.append(" = ").append(value);
            if (!states.isEmpty()) out.append(" (").append(String.join(", ", states)).append(')');
            return out.toString();
        }
    }

    /** The resolved target: an aria ref, or a CSS / text fallback the caller resolves through Playwright. */
    public record Target(String ref, String role, String name, String fallbackKind, String fallbackValue) {
        static Target ofRef(String ref, String role, String name) {
            return new Target(ref, role, name, "", "");
        }

        static Target fallback(String kind, String value) {
            return new Target("", "", value, kind, value);
        }

        boolean hasRef() {
            return !ref.isEmpty();
        }
    }

    private final String url;
    private final List<Control> controls;
    private final AriaTree tree;

    private PageControls(String url, AriaTree tree, List<Control> controls) {
        this.url = url;
        this.tree = tree;
        this.controls = List.copyOf(controls);
    }

    public static PageControls from(String url, AriaTree tree) {
        List<Control> found = new ArrayList<>();
        tree.roots().forEach(root -> collect(root, found));
        return new PageControls(url, tree, found);
    }

    private static void collect(AriaTree.Node node, List<Control> found) {
        // Options of a native select carry no ref; options of a listbox stay operable.
        boolean operable = CONTROL_ROLES.contains(node.role()) && !node.has("disabled") && !node.ref().isEmpty();
        if (operable) {
            List<String> states = new ArrayList<>();
            for (String state : List.of("checked", "selected", "expanded", "pressed", "required")) {
                if (node.has(state)) {
                    String value = node.attribute(state);
                    states.add(value.isEmpty() || value.equals("true") ? state : state + "=" + value);
                }
            }
            String value = node.text();
            if (node.role().equals("combobox") && value.isBlank()) {
                value = node.children().stream().filter(o -> o.role().equals("option") && o.has("selected"))
                        .map(AriaTree.Node::name).findFirst().orElse("");
            }
            found.add(new Control(found.size() + 1, node.role(), node.name(), value, node.ref(), states));
        }
        node.children().forEach(child -> collect(child, found));
    }

    /** The same snapshot with the values of {@code refs} (password fields) replaced. */
    public PageControls withHiddenValues(Set<String> refs) {
        List<Control> masked = controls.stream().map(c -> refs.contains(c.ref())
                ? new Control(c.number(), c.role(), c.name(), "(hidden)", c.ref(), c.states()) : c).toList();
        return new PageControls(url, tree, masked);
    }

    public String url() { return url; }
    public List<Control> all() { return controls; }
    public AriaTree tree() { return tree; }

    /** Controls whose role, name or value contains {@code filter} (ignoring case); all when blank. */
    public List<Control> filtered(String filter, int limit) {
        String needle = normalize(filter);
        return controls.stream()
                .filter(c -> needle.isEmpty() || normalize(c.role() + " " + c.name() + " " + c.value()).contains(needle))
                .limit(Math.max(1, limit)).toList();
    }

    /**
     * Resolves {@code target} against this snapshot.
     *
     * @throws IllegalArgumentException with a short, model-readable hint when nothing or several things match
     */
    public Target resolve(String target) {
        String wanted = target == null ? "" : target.trim();
        if (wanted.isEmpty()) throw new IllegalArgumentException("Missing target. Example: 3 or \"Suchen\"");
        if (wanted.startsWith("css=")) return Target.fallback("css", wanted.substring(4).trim());

        Matcher number = NUMBER.matcher(wanted);
        if (number.matches()) {
            int index = Integer.parseInt(number.group(1));
            return controls.stream().filter(c -> c.number() == index).findFirst()
                    .map(c -> Target.ofRef(c.ref(), c.role(), c.name()))
                    .orElseThrow(() -> new IllegalArgumentException("No control [" + index
                            + "] on this page; " + controls.size() + " controls are listed."));
        }
        Matcher ref = REF.matcher(wanted);
        if (ref.matches()) {
            String wantedRef = ref.group(1);
            List<AriaTree.Node> nodes = tree.findAll(node -> node.ref().equals(wantedRef));
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("Unknown ref " + wantedRef + "; list the controls again.");
            }
            return Target.ofRef(wantedRef, nodes.get(0).role(), nodes.get(0).name());
        }

        String role = null;
        String name;
        Matcher roleMatch = ROLE.matcher(wanted);
        Matcher named = NAMED.matcher(wanted);
        if (roleMatch.matches()) {
            role = roleMatch.group(1).toLowerCase(Locale.ROOT);
            name = roleMatch.group(2) == null ? "" : roleMatch.group(2).replace("\\\"", "\"");
        } else if (named.matches()) {
            name = named.group(2);
        } else {
            name = stripQuotes(wanted);
        }
        String needle = normalize(name);
        String expectedRole = role;
        // Controls and links of the page; headings etc. only when a role was asked for explicitly.
        List<AriaTree.Node> candidates = tree.findAll(node -> !node.ref().isEmpty() && !node.has("disabled")
                && (expectedRole != null ? node.role().equals(expectedRole)
                : CONTROL_ROLES.contains(node.role()) || node.role().equals("link")));
        if (expectedRole != null && needle.isEmpty()) {
            return unique(candidates, wanted);
        }
        List<AriaTree.Node> exact = candidates.stream().filter(n -> normalize(n.name()).equals(needle)).toList();
        if (exact.size() == 1) return Target.ofRef(exact.get(0).ref(), exact.get(0).role(), exact.get(0).name());
        if (exact.size() > 1) {
            List<AriaTree.Node> controlsOnly = exact.stream().filter(n -> !n.role().equals("link")).toList();
            if (controlsOnly.size() == 1) return Target.ofRef(controlsOnly.get(0).ref(),
                    controlsOnly.get(0).role(), controlsOnly.get(0).name());
            throw ambiguous(wanted, exact);
        }
        List<AriaTree.Node> partial = needle.isEmpty() ? List.of()
                : candidates.stream().filter(n -> normalize(n.name()).contains(needle)).toList();
        if (partial.size() == 1) return Target.ofRef(partial.get(0).ref(), partial.get(0).role(), partial.get(0).name());
        if (partial.size() > 1) throw ambiguous(wanted, partial);
        // Plain text without an accessible control, e.g. a clickable <div>: Playwright's text engine decides.
        if (roleMatch.matches()) {
            throw new IllegalArgumentException("Nothing matches " + wanted + "; list the controls to see what the page offers.");
        }
        String kind = named.matches() ? named.group(1) : "text";
        return Target.fallback(kind.equals("name") ? "text" : kind, name);
    }

    private static Target unique(List<AriaTree.Node> candidates, String wanted) {
        if (candidates.size() == 1) {
            return Target.ofRef(candidates.get(0).ref(), candidates.get(0).role(), candidates.get(0).name());
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("Nothing matches " + wanted + ".");
        throw ambiguous(wanted, candidates);
    }

    private static IllegalArgumentException ambiguous(String wanted, List<AriaTree.Node> matches) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < matches.size() && i < 3; i++) {
            AriaTree.Node node = matches.get(i);
            names.append(i == 0 ? "" : ", ").append(node.role()).append(" \"").append(node.name())
                    .append("\" ref=").append(node.ref());
        }
        return new IllegalArgumentException(matches.size() + " elements match " + wanted + " (" + names
                + (matches.size() > 3 ? ", ..." : "") + "); use the number or ref instead.");
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
