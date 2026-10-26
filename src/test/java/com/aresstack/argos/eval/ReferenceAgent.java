package com.aresstack.argos.eval;

import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scripted "model" that solves a task the way a careful agent would, written as plain sequential code: the
 * script runs on its own thread and each {@link Steps#tool} call becomes one model turn. It proves that every
 * fixture task is solvable through the published tools, without a language model.
 */
final class ReferenceAgent implements ChatModel {
    interface Script {
        String solve(Steps steps) throws Exception;
    }

    /** What the script can do: call a tool and read its answer. */
    final class Steps {
        String tool(String name, String arguments) throws InterruptedException {
            actions.put(new ModelTurn("", List.of(new ToolCall("ref-" + (++calls), name, arguments, false)), 0, 0));
            return results.take();
        }
    }

    private final Script script;
    private final SynchronousQueue<ModelTurn> actions = new SynchronousQueue<>();
    private final SynchronousQueue<String> results = new SynchronousQueue<>();
    private Thread runner;
    private int calls;

    ReferenceAgent(Script script) {
        this.script = script;
    }

    @Override
    public ModelTurn complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws Exception {
        if (runner == null) {
            runner = Thread.ofVirtual().start(() -> {
                String answer;
                try {
                    answer = script.solve(new Steps());
                } catch (Exception failure) {
                    answer = "Reference script failed: " + failure;
                }
                try {
                    actions.put(new ModelTurn(answer, List.of(), 0, 0));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        } else {
            results.put(messages.get(messages.size() - 1).content());
        }
        ModelTurn next = actions.poll(120, TimeUnit.SECONDS);
        if (next == null) throw new ModelException("reference script stalled");
        return next;
    }

    @Override
    public String name() {
        return "reference";
    }

    static String find(String text, String regex) {
        Matcher match = Pattern.compile(regex).matcher(text);
        if (!match.find()) throw new IllegalStateException("Not found: " + regex + " in: " + text);
        return match.groupCount() > 0 ? match.group(1) : match.group();
    }

    /** The id (e.g. result-3, link-5) of the listed line whose text contains the given words. */
    static String idOf(String listing, String words) {
        for (String line : listing.split("\n")) {
            if (line.contains(words)) return line.substring(0, line.indexOf(':')).strip();
        }
        throw new IllegalStateException("No entry with '" + words + "' in: " + listing);
    }
}
