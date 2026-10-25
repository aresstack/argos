package com.aresstack.argos.page;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One uniform description of what the page currently shows, for every document type.
 * {@code url}, {@code title} and {@code text} keep the original browser_snapshot keys.
 *
 * @param source     where the text came from: {@code dom}, {@code response} or {@code none}
 * @param characters length of the full text before truncation
 * @param hint       guidance when the text is empty or incomplete (e.g. binary documents)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageSnapshot(String url, String title, int status, DocumentType documentType,
                           String contentType, String text, boolean truncated, int characters,
                           String source, String hint) {
}
