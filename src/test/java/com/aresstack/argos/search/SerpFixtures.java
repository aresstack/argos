package com.aresstack.argos.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Hand-written search pages that mirror the real engines' markup structure. */
final class SerpFixtures {
    private SerpFixtures() {
    }

    static String html(String name) {
        try (InputStream in = SerpFixtures.class.getResourceAsStream("/serp/" + name)) {
            if (in == null) throw new IllegalArgumentException("Missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static SerpDocument parse(String name, String url, String adapterSelector) {
        return new SerpDocumentParser(SearchAnalysisSettings.defaults()).parse(html(name), url, "", adapterSelector, 1);
    }
}
