package com.aresstack.argos.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The optional resource categories of ONE chat: {@code history/capture-settings.json} holds the chat's overrides
 * only. Effective value = the chat's override, else the server's {@code SEARCH_HISTORY_CAPTURE_*} variable,
 * else false. Shared by all user contexts of the chat; never read for another chat. Changes apply to responses that
 * finish afterwards; nothing is fetched again. The HTML archive is no part of these settings.
 */
public final class CaptureSettings {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Map<ResourceCategory, Boolean> defaults;
    /** Loaded on first use; null until then. */
    private volatile Map<ResourceCategory, Boolean> overrides;

    public CaptureSettings(Path historyDirectory, Map<ResourceCategory, Boolean> defaults) {
        this.file = historyDirectory.toAbsolutePath().normalize().resolve("capture-settings.json");
        this.defaults = Map.copyOf(defaults);
    }

    /** One category's effective value and where it comes from: "chat" (this chat's
     * override) or "server" (the environment variable, else the built-in default false). */
    public record Value(boolean enabled, String source) { }

    public boolean enabled(ResourceCategory category) {
        return category == ResourceCategory.CONTENT || value(category).enabled();
    }

    public Value value(ResourceCategory category) {
        if (category == ResourceCategory.CONTENT) return new Value(true, "built-in");
        Boolean override = overrides().get(category);
        if (override != null) return new Value(override, "chat");
        // The server's start value: the SEARCH_HISTORY_CAPTURE_* variable, else the built-in false.
        return new Value(Boolean.TRUE.equals(defaults.get(category)), "server");
    }

    /** Every optional category with its effective value, in declaration order. */
    public Map<ResourceCategory, Value> effective() {
        Map<ResourceCategory, Value> all = new LinkedHashMap<>();
        for (ResourceCategory category : ResourceCategory.values()) {
            if (category != ResourceCategory.CONTENT) all.put(category, value(category));
        }
        return all;
    }

    /** Stores this chat's overrides (merged into the existing ones). */
    public synchronized void set(Map<ResourceCategory, Boolean> changes) throws IOException {
        Map<ResourceCategory, Boolean> next = new EnumMap<>(ResourceCategory.class);
        next.putAll(overrides());
        changes.forEach((category, value) -> {
            if (category == ResourceCategory.CONTENT) throw new IllegalArgumentException("content is always captured");
            next.put(category, value);
        });
        save(next);
    }

    /** Removes this chat's overrides: the environment (or the built-in default) applies again. */
    public synchronized void reset() throws IOException {
        Files.deleteIfExists(file);
        overrides = Map.of();
    }

    private Map<ResourceCategory, Boolean> overrides() {
        Map<ResourceCategory, Boolean> current = overrides;
        if (current != null) return current;
        synchronized (this) {
            if (overrides == null) overrides = load();
            return overrides;
        }
    }

    private Map<ResourceCategory, Boolean> load() {
        Map<ResourceCategory, Boolean> loaded = new EnumMap<>(ResourceCategory.class);
        if (!Files.isRegularFile(file)) return Map.copyOf(loaded);
        try {
            JsonNode node = JSON.readTree(file.toFile()).path("overrides");
            for (ResourceCategory category : ResourceCategory.values()) {
                JsonNode value = node.get(category.key());
                if (category != ResourceCategory.CONTENT && value != null && value.isBoolean()) {
                    loaded.put(category, value.asBoolean());
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // A damaged file means no overrides; the environment applies.
            System.err.println("[argos-history] capture-settings.json unreadable: "
                    + unreadable.getClass().getSimpleName());
        }
        return Map.copyOf(loaded);
    }

    private void save(Map<ResourceCategory, Boolean> next) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode values = root.putObject("overrides");
        next.forEach((category, value) -> values.put(category.key(), value));
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        overrides = Map.copyOf(next);
    }

    /** {@code images}, {@code IMAGES} or the variable name; null when unknown. */
    public static ResourceCategory category(String name) {
        if (name == null) return null;
        String wanted = name.strip().toLowerCase(Locale.ROOT).replace("argos_search_history_capture_", "");
        for (ResourceCategory category : ResourceCategory.values()) {
            if (category != ResourceCategory.CONTENT && category.key().equals(wanted)) return category;
        }
        return null;
    }
}
