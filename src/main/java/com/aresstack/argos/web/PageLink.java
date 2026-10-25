package com.aresstack.argos.web;

/** One followable link of the current page: id (link-N), visible text and absolute URL. */
public record PageLink(String id, String text, String url) {
}
