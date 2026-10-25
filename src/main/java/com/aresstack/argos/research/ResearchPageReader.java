package com.aresstack.argos.research;

/**
 * Port: open a URL in the browser and hand back what it shows. Implementations must stay invisible to
 * the page (navigation plus serialized DOM; no init scripts, bindings or DOM writes).
 */
public interface ResearchPageReader {
    ResearchPage read(String url) throws Exception;
}
