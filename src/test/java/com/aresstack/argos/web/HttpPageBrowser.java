package com.aresstack.argos.web;

import org.jsoup.Jsoup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;

/** Test double for the browser: fetches pages over plain HTTP and keeps a history. */
final class HttpPageBrowser implements BrowserPagePort {
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(HttpClient.Builder.NO_PROXY).build();
    private final Deque<String> history = new ArrayDeque<>();
    String lastReferer;

    @Override
    public PageCapture open(String url, String referer) {
        lastReferer = referer;
        PageCapture page = fetch(url, referer);
        history.push(page.url());
        return page;
    }

    @Override
    public PageCapture current() {
        return fetch(history.peek(), null);
    }

    @Override
    public PageCapture back() {
        if (history.size() < 2) return null;
        history.pop();
        return fetch(history.peek(), null);
    }

    private PageCapture fetch(String url, String referer) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
            if (referer != null) request.header("Referer", referer);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            var document = Jsoup.parse(response.body(), response.uri().toString());
            return new PageCapture(response.uri().toString(), document.title(),
                    document.body().text(), response.body());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
