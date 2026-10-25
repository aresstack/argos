package com.aresstack.argos.web;

/** One search hit as a small model sees it. */
public record SearchHit(String title, String url, String snippet) {
}
