package com.aresstack.argos.page;

import java.util.Optional;

/**
 * Port to the current top-level document. Implementations must obtain data
 * without running scripts in the page's own JavaScript world: protocol data
 * (response headers/body) or Playwright's isolated utility world only.
 */
public interface PageSource {
    String url();

    String title();

    /** HTTP status of the main document response, 0 when unknown. */
    int status();

    /** Content-Type header of the main document response, or null when unknown. */
    String contentType();

    /** Main document response body as delivered by the browser, if the protocol exposes it. */
    Optional<String> responseText();

    /** Whether the rendered document has an HTML {@code body} element. */
    boolean hasBody();

    /** Rendered, visible text of the HTML {@code body}. Only call when {@link #hasBody()}. */
    String bodyText();

    /** Text content of the document element; works for HTML and XML documents. */
    String rootText();

    /** Serialized source of the rendered document (HTML or XML markup). */
    String documentSource();
}
