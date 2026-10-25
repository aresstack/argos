package com.aresstack.argos.web;

/** Pluggable web search behind the web_search tool. */
public interface WebSearchPort {
    SearchResults search(String query) throws Exception;
}
