package com.aresstack.argos.history;

import com.aresstack.argos.web.BrowserPagePort;
import com.aresstack.argos.web.PageCapture;
import com.aresstack.argos.web.PageText;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small in-memory pages for the Search History tests; no browser. */
final class HistoryFixtures {
    private HistoryFixtures() {
    }

    static SearchHistory.PageRead page(String url, String title, String text, String... links) {
        return new SearchHistory.PageRead(url, url, title, 200, text, List.of(links));
    }

    /** A browser that serves fixed HTML per URL, with history for back(). */
    static final class FakeBrowser implements BrowserPagePort {
        final Map<String, String> pages = new HashMap<>();
        final Deque<String> history = new ArrayDeque<>();
        String shown = "about:blank";

        @Override
        public PageCapture open(String url, String referer) {
            if (!shown.equals("about:blank")) history.push(shown);
            shown = url;
            return current();
        }

        @Override
        public PageCapture current() {
            return PageText.capture(shown, pages.getOrDefault(shown, "<title>Missing</title>"), 200);
        }

        @Override
        public String currentUrl() {
            return shown;
        }

        @Override
        public PageCapture back() {
            if (history.isEmpty()) return null;
            shown = history.pop();
            return current();
        }

        @Override
        public void clear() {
            shown = "about:blank";
        }
    }
}
