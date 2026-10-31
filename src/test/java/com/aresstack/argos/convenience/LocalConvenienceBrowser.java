package com.aresstack.argos.convenience;

import com.aresstack.argos.search.ChallengeCheck;
import com.aresstack.argos.web.PageCapture;
import com.aresstack.argos.web.PageText;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A {@link ConvenienceBrowser} over {@link LocalSites} with real parallel loads: the same scheduling rules as
 * CamoufoxBrowser (gate per domain, at most {@code parallel} loads, queue timeout, cooldowns), each load a real HTTP
 * request on its own thread. Tabs are numbered t1, t2, ... per browser and never reused, as in BrowserTabs.
 */
final class LocalConvenienceBrowser implements ConvenienceBrowser {
    private final LocalSites sites;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Deque<TabLoad>> tabs = new LinkedHashMap<>();
    private int nextTab = 1;
    String context = "start";
    final List<String> clicked = new ArrayList<>();
    /** "tab | selector | typed text" per search typed into a field. */
    final List<String> submitted = new ArrayList<>();

    LocalConvenienceBrowser(LocalSites sites) {
        this.sites = sites;
    }

    @Override
    public synchronized List<TabLoad> loadTabs(List<TabLoadRequest> requests, NavigationGate gate, int parallel,
                                               long timeoutMillis) {
        TabLoad[] out = new TabLoad[requests.size()];
        long queuedAt = System.currentTimeMillis();
        Map<Integer, Future<TabLoad>> running = new LinkedHashMap<>();
        ExecutorService pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        try {
            while (true) {
                boolean open = false;
                for (int i = 0; i < out.length; i++) {
                    if (out[i] != null || running.containsKey(i)) continue;
                    open = true;
                    if (running.size() >= parallel) break;
                    TabLoadRequest request = requests.get(i);
                    long cooldown = gate.cooldownMillis(request.domain());
                    if (cooldown > 0) {
                        out[i] = new TabLoad(request.tab() == null ? "" : request.tab(), request.url(),
                                TabLoadOutcome.COOLING_DOWN, null, null, (cooldown + 999) / 1000, false, "cooling down");
                        continue;
                    }
                    if (System.currentTimeMillis() - queuedAt > timeoutMillis) {
                        out[i] = new TabLoad(request.tab() == null ? "" : request.tab(), request.url(),
                                TabLoadOutcome.TIMEOUT, null, null, 0, false, "waited too long for domain");
                        continue;
                    }
                    NavigationGate.Lease lease = gate.tryAcquire(request.domain());
                    if (lease == null) continue;
                    String tab = request.tab() == null ? newTab() : request.tab();
                    long left = Math.max(1, timeoutMillis - (System.currentTimeMillis() - queuedAt));
                    running.put(i, pool.submit(() -> fetch(tab, request, lease, left)));
                }
                for (var job : new ArrayList<>(running.entrySet())) {
                    if (!job.getValue().isDone()) continue;
                    running.remove(job.getKey());
                    TabLoad load = job.getValue().get();
                    out[job.getKey()] = load;
                    if (load.outcome() == TabLoadOutcome.OK) {
                        tabs.get(load.tab()).push(load);
                    } else if (requests.get(job.getKey()).tab() == null) {
                        tabs.remove(load.tab());
                    }
                }
                if (!open && running.isEmpty()) break;
                Thread.sleep(3);
            }
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        } finally {
            pool.shutdownNow();
        }
        return List.of(out);
    }

    private TabLoad fetch(String tab, TabLoadRequest request, NavigationGate.Lease lease, long timeoutMillis) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(sites.local(request.url())))
                    .timeout(Duration.ofMillis(timeoutMillis)).GET().build(), HttpResponse.BodyHandlers.ofString());
            PageCapture capture = PageText.capture(request.url(), response.body(), response.statusCode());
            boolean challenge = ChallengeCheck.isChallenge(capture.html(), capture.url(), capture.text());
            long retryAfter = response.headers().firstValue("Retry-After").map(Long::parseLong).orElse(0L);
            lease.release(response.statusCode(), retryAfter, challenge);
            return new TabLoad(tab, request.url(), TabLoadOutcome.OK, capture,
                    response.headers().firstValue("Content-Type").orElse(null), retryAfter, challenge, "");
        } catch (HttpTimeoutException slow) {
            lease.release(0, 0, false);
            return new TabLoad(tab, request.url(), TabLoadOutcome.TIMEOUT, null, null, 0, false, "page did not load in time");
        } catch (Exception failed) {
            lease.release(0, 0, false);
            return new TabLoad(tab, request.url(), TabLoadOutcome.FAILED, null, null, 0, false, String.valueOf(failed));
        }
    }

    private String newTab() {
        String tab = "t" + nextTab++;
        tabs.put(tab, new ArrayDeque<>());
        return tab;
    }

    @Override
    public synchronized TabLoad readTab(String tab) {
        Deque<TabLoad> history = tabs.get(tab);
        if (history == null || history.isEmpty()) throw new IllegalArgumentException("Unknown tab " + tab);
        return history.peek();
    }

    @Override
    public synchronized TabLoad backTab(String tab) {
        Deque<TabLoad> history = tabs.get(tab);
        if (history == null || history.size() < 2) return null;
        history.pop();
        return history.peek();
    }

    @Override
    public synchronized boolean clickInTab(String selector, String tab) {
        clicked.add(tab + " " + selector);
        return false;
    }

    /**
     * What the browser does when Enter is pressed in a search field: the field's form is sent with the typed text
     * (as GET; the local sites ignore the method). Only a selector matching an editable field of the tab's page
     * gets that far, as for the real browser.
     */
    @Override
    public synchronized TabLoad submitSearchInTab(String tab, List<String> searchBoxSelectors, String query) {
        TabLoad shown = readTab(tab);
        var page = Jsoup.parse(shown.capture().html(), shown.capture().url());
        for (String selector : searchBoxSelectors) {
            Element field = page.selectFirst(selector);
            if (field == null) continue;
            if (!field.is("textarea, input:not([type=hidden])")) throw new IllegalStateException(selector + " is no field");
            submitted.add(tab + " | " + selector + " | " + query);
            Element form = field.closest("form");
            String action = form == null || form.absUrl("action").isEmpty() ? shown.capture().url() : form.absUrl("action");
            String url = action + (action.contains("?") ? "&" : "?") + field.attr("name") + "="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            TabLoad load = fetch(tab, new TabLoadRequest(tab, url, RegistrableDomain.of(url)),
                    NavigationGate.OPEN.tryAcquire(RegistrableDomain.of(url)), 10_000);
            if (load.outcome() == TabLoadOutcome.OK) tabs.get(tab).push(load);
            return load;
        }
        return null;
    }

    @Override
    public synchronized boolean hasOpenTab(String tab) {
        return tabs.containsKey(tab);
    }

    @Override
    public synchronized void closeTabQuietly(String tab) {
        tabs.remove(tab);
    }

    @Override
    public String contextId() {
        return context;
    }

    synchronized int openTabs() {
        return tabs.size();
    }
}
