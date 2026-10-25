package com.aresstack.argos.web;

/** Port for the compact web_search tool: one query in, numbered hits plus short diagnostics out. */
public interface WebSearchPort {
    SearchResults search(String query) throws Exception;
}
