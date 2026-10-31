package com.aresstack.argos.eval;

import com.aresstack.argos.network.HostResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A small fictional web on loopback, so that answers cannot come from a model's training data and every run
 * sees the same pages. Each site has its own domain under the reserved {@code .test} top-level domain (RFC
 * 2606), resolved to loopback by {@link #resolver()} for Argos' network policy and proxy. Real-looking domains
 * matter: with {@code http://127.0.0.1:port} addresses, local models often refused to open a page at all,
 * assuming local addresses were off limits (first Windows run, PR #13).
 *
 * <p>Sites: the Kestrel Valley Observatory ({@link #OBSERVATORY}), distractors (fan club, hotel, forum), a
 * mirror (sky atlas) and a search engine. The observatory's /darksky always fails with HTTP 500; the next
 * search fails once with HTTP 503 after {@link #failNextSearch()}.
 */
public final class FixtureWorld implements AutoCloseable {
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "what", "which", "who", "how", "many",
            "with", "from", "that", "this", "are", "was", "did", "does", "have", "has", "its", "official", "website",
            "site", "page", "web", "www", "http", "https", "find", "about", "near", "der", "die", "das", "und");

    public static final String OBSERVATORY = "www.kestrel-observatory.test";
    static final String FAN_CLUB = "kvo-fanclub.test";
    static final String HOTEL = "kestrel-valley-hotel.test";
    static final String FORUM = "stargazer-forum.test";
    static final String ATLAS = "nordic-sky-atlas.test";
    static final String ENGINE = "kestrelsearch.test";

    /** One indexed page: title and snippet as the engine shows them, plus terms the engine matches on. */
    record Entry(String host, String path, String title, String snippet, String terms) {
        Set<String> tokens() {
            return tokenize(title + " " + snippet + " " + terms);
        }
    }

    // Index order breaks score ties: distractors come first, so picking the right hit is the model's job.
    static final List<Entry> INDEX = List.of(
            new Entry(FAN_CLUB, "/", "Kestrel Valley Observatory Fan Club (unofficial)",
                    "Unofficial fan pages, photos and rumours about the Kestrel Valley Observatory.",
                    "observatory fans club photos rumours"),
            new Entry(HOTEL, "/", "Kestrel Valley Hotel – stay near the observatory",
                    "Rooms with a view of the Kestrel Valley, 10 minutes from the observatory. Restaurant opening hours on Saturday and Sunday.",
                    "hotel rooms restaurant opening hours saturday sunday visit observatory"),
            new Entry(OBSERVATORY, "/", "Kestrel Valley Observatory – Official Website",
                    "Public observing nights, lectures, museum and visitor information of the Kestrel Valley Observatory.",
                    "official homepage visit visitor opening hours saturday sunday admission contact address"),
            new Entry(FORUM, "/threads/kvo-clear-nights",
                    "Stargazer Forum: How many clear nights did Kestrel Valley Observatory have in 2025?",
                    "Forum thread with guesses about the number of clear nights at the observatory last year.",
                    "clear nights 2025 weather statistics number"),
            new Entry(OBSERVATORY, "/report-2025", "Annual Report 2025 – Kestrel Valley Observatory",
                    "Observing statistics, clear nights, visitors and finances of the observatory for 2025.",
                    "annual report statistics clear nights 2025 visitors number"),
            new Entry(OBSERVATORY, "/darksky", "Dark-sky protection zone map – Kestrel Valley Observatory",
                    "Official map and name of the protected dark-sky zone around the observatory.",
                    "dark sky darksky zone protection protected area map name light pollution"),
            new Entry(ATLAS, "/maps/kestrel-valley", "Nordic Sky Atlas: Kestrel Valley dark-sky zone (mirror)",
                    "Mirror of the observatory's dark-sky map with the zone's lighting rules.",
                    "dark sky darksky zone protection protected area map name light pollution mirror"),
            new Entry(OBSERVATORY, "/instruments", "Instruments – Kestrel Valley Observatory",
                    "Telescopes of the observatory with type, aperture and year of service.",
                    "telescope telescopes aperture mirror reflector refractor"),
            new Entry(OBSERVATORY, "/events", "Events – Kestrel Valley Observatory",
                    "Upcoming lectures and observing nights.", "events lecture lectures talk speaker calendar"),
            new Entry(OBSERVATORY, "/archive", "History and archive – Kestrel Valley Observatory",
                    "Night logs since 1962 and the history of the observatory.",
                    "history archive founding founded director logbook"));

    /** Static pages by host + path. */
    private static final Map<String, String> PAGES = Map.ofEntries(
            Map.entry(OBSERVATORY + "/", "kvo-home.html"),
            Map.entry(OBSERVATORY + "/instruments", "kvo-instruments.html"),
            Map.entry(OBSERVATORY + "/events", "kvo-events.html"),
            Map.entry(OBSERVATORY + "/tickets", "kvo-tickets.html"),
            Map.entry(OBSERVATORY + "/report-2025", "kvo-report-2025.html"),
            Map.entry(FORUM + "/threads/kvo-clear-nights", "forum-clear-nights.html"),
            Map.entry(FAN_CLUB + "/", "fans-kvo.html"),
            Map.entry(HOTEL + "/", "hotel.html"),
            Map.entry(ATLAS + "/maps/kestrel-valley", "atlas-darksky.html"));

    private final HttpServer server;
    private final AtomicBoolean failNextSearch = new AtomicBoolean();
    private final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());

    public FixtureWorld() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    /** Base URL of the observatory's site, e.g. {@code http://www.kestrel-observatory.test:41234}. */
    public String siteBase() {
        return base(OBSERVATORY);
    }

    String base(String host) {
        return "http://" + host + ":" + server.getAddress().getPort();
    }

    /** The engine's start page for Argos' custom search engine: its search field leads to {@link #searchTemplate}. */
    public String searchHome() {
        return base(ENGINE) + "/";
    }

    /** The URL the engine's search form produces for a query (for reading result pages directly in tests). */
    public String searchTemplate() {
        return base(ENGINE) + "/search?q={query}";
    }

    /** Resolves the world's {@code .test} domains to loopback; every other name the usual way. */
    public static HostResolver resolver() {
        return host -> host.toLowerCase(Locale.ROOT).endsWith(".test")
                ? List.of(InetAddress.getLoopbackAddress()) : HostResolver.SYSTEM.resolve(host);
    }

    /** The next search request answers HTTP 503 once (recovery task). */
    public void failNextSearch() {
        failNextSearch.set(true);
    }

    public void reset() {
        failNextSearch.set(false);
        requests.clear();
    }

    /** Paths with query requested since the last reset, for the transcript. */
    public List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String host = hostOf(exchange);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String page = host + path;
            requests.add(page + (query == null ? "" : "?" + query));
            if (page.equals(ENGINE + "/")) {
                respond(exchange, 200, "text/html; charset=utf-8", "<!DOCTYPE html><html><head><title>Kestrel Search"
                        + "</title></head><body><h1>Kestrel Search</h1><form action=\"/search\" role=\"search\">"
                        + "<input type=\"search\" name=\"q\" aria-label=\"Search\"><button>Search</button></form>"
                        + "</body></html>");
            } else if (page.equals(ENGINE + "/search")) {
                if (failNextSearch.getAndSet(false)) {
                    respond(exchange, 503, "text/html; charset=utf-8", "<!DOCTYPE html><html><head><title>503 Service "
                            + "Unavailable</title></head><body><h1>Service Unavailable</h1><p>Search is temporarily "
                            + "unavailable. Please try again.</p></body></html>");
                } else {
                    respond(exchange, 200, "text/html; charset=utf-8", searchPage(parameter(query, "q")));
                }
            } else if (page.equals(OBSERVATORY + "/darksky")) {
                respond(exchange, 500, "text/html; charset=utf-8", resource("server-error.html"));
            } else if (page.equals(OBSERVATORY + "/api/events.json")) {
                pause(1500);
                respond(exchange, 200, "application/json", resource("kvo-events.json"));
            } else if (page.equals(OBSERVATORY + "/tickets/status")) {
                respond(exchange, 200, "text/html; charset=utf-8", ticketStatus(parameter(query, "ticket")));
            } else if (page.equals(OBSERVATORY + "/archive")) {
                respond(exchange, 200, "text/html; charset=utf-8", archivePage());
            } else if (PAGES.containsKey(page)) {
                respond(exchange, 200, "text/html; charset=utf-8", resource(PAGES.get(page)));
            } else if (path.equals("/favicon.ico")) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                respond(exchange, 404, "text/html; charset=utf-8", "<!DOCTYPE html><html><head><title>404 Not Found"
                        + "</title></head><body><h1>Not Found</h1><p>No page at " + escape(path) + ".</p></body></html>");
            }
        }
    }

    /** The site a request is for, from its Host header (without port). */
    private static String hostOf(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) return "";
        int colon = host.lastIndexOf(':');
        return (colon > 0 ? host.substring(0, colon) : host).toLowerCase(Locale.ROOT);
    }

    /** The engine's result page for a query, as the browser receives it. */
    public String searchHtml(String query) {
        return searchPage(query);
    }

    /** How many result cards the engine shows for a query. */
    public int expectedHits(String query) {
        Set<String> words = tokenize(query);
        return (int) Math.min(8, INDEX.stream().filter(entry -> entry.tokens().stream().anyMatch(words::contains)).count());
    }

    /** Hits ranked by how many query words they contain; no hit at all renders the engine's "no results" page. */
    String searchPage(String query) {
        Set<String> words = tokenize(query);
        List<Entry> hits = new ArrayList<>();
        Map<Entry, Integer> scores = new java.util.HashMap<>();
        for (Entry entry : INDEX) {
            Set<String> matched = new HashSet<>(entry.tokens());
            matched.retainAll(words);
            if (!matched.isEmpty()) {
                hits.add(entry);
                scores.put(entry, matched.size());
            }
        }
        hits.sort(Comparator.comparingInt((Entry entry) -> -scores.get(entry)));
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset='utf-8'><title>")
                .append(escape(query)).append(" – Kestrel Search</title></head><body>")
                .append("<header><a href='/'>Kestrel Search</a><nav><a href='/news'>News</a> <a href='/help'>Help</a>"
                        + " <a href='/about'>About</a></nav></header>")
                .append("<form action='/search'><input name='q' value='").append(escape(query))
                .append("'><button>Search</button></form><div class='layout'><div class='hits'>");
        if (hits.isEmpty()) {
            html.append("<p class='no-results'>No results found for <b>").append(escape(query)).append("</b>.</p>");
        }
        for (Entry hit : hits.subList(0, Math.min(8, hits.size()))) {
            html.append("<article class='card'><h3><a href='").append(base(hit.host())).append(hit.path()).append("'>")
                    .append(escape(hit.title())).append("</a></h3><p>").append(escape(hit.snippet()))
                    .append("</p></article>");
        }
        return html.append("</div></div><footer><a href='/privacy'>Privacy</a> <a href='/terms'>Terms</a></footer>")
                .append("</body></html>").toString();
    }

    private static String ticketStatus(String ticket) {
        String normalized = ticket == null ? "" : ticket.strip().toUpperCase(Locale.ROOT);
        String body = normalized.equals("KV-4471")
                ? "<p>Ticket KV-4471: CONFIRMED. Seat F12, Saturday public observing night, 18:00.</p>"
                : "<p>Ticket " + escape(normalized) + " was not found. Please check the number.</p>";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Ticket status – Kestrel Valley Observatory</title>"
                + "</head><body><nav><a href='/'>Home</a> | <a href='/tickets'>Check another ticket</a></nav>"
                + "<h1>Ticket status</h1>" + body + "</body></html>";
    }

    /** A long logbook (several web_read parts); the founding history sits at the very end. */
    static String archivePage() {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset='utf-8'><title>History and archive"
                + " – Kestrel Valley Observatory</title></head><body><nav><a href='/'>Home</a></nav>"
                + "<h1>History and archive</h1><h2>Night logs</h2>");
        String[] observers = {"A. Lind", "K. Varga", "P. Okafor", "M. Hale", "S. Brandt", "T. Ruiz"};
        String[] targets = {"M31", "Jupiter", "the Pleiades", "comet C/1985", "Saturn", "M42", "the Moon", "Vega"};
        String[] seeing = {"good", "fair", "excellent", "poor"};
        for (int night = 0; night < 160; night++) {
            int year = 1962 + night / 4;
            int month = 1 + (night * 5) % 12;
            int day = 1 + (night * 7) % 28;
            html.append("<p>Night log ").append(year).append('-').append(month < 10 ? "0" : "").append(month).append('-')
                    .append(day < 10 ? "0" : "").append(day).append(": seeing ").append(seeing[night % 4])
                    .append(", transparency ").append(seeing[(night + 1) % 4]).append(". Observer ")
                    .append(observers[night % observers.length]).append(" followed ")
                    .append(targets[night % targets.length])
                    .append(" with the Brenna refractor and noted the instrument temperature every hour.</p>");
        }
        html.append("<h2>Founding of the observatory</h2><p>The Kestrel Valley Observatory was founded in 1961 by the "
                + "Kestrel Valley Astronomical Society. Its founding director was Margit Solberg, who led it until 1988.</p>");
        return html.append("</body></html>").toString();
    }

    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String word : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) tokens.add(word);
        }
        return tokens;
    }

    private static String parameter(String rawQuery, String name) {
        if (rawQuery == null) return "";
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equals(name)) {
                return equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static String resource(String name) {
        try (InputStream in = FixtureWorld.class.getResourceAsStream("/world/" + name)) {
            if (in == null) throw new IllegalStateException("Missing fixture /world/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
                .replace("\"", "&quot;");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
