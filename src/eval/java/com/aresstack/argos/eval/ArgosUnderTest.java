package com.aresstack.argos.eval;

import com.aresstack.argos.ArgosApplication;
import com.aresstack.argos.ArgosBrowsingTools;
import com.aresstack.argos.ArgosConfig;
import com.aresstack.argos.ArgosTools;
import com.aresstack.argos.CamoufoxBrowser;
import com.aresstack.argos.DocumentAndBatchTools;
import com.aresstack.argos.ResearchDocuments;
import com.aresstack.argos.document.DocumentStore;
import com.aresstack.argos.network.NetworkSettings;
import com.aresstack.argos.research.ResearchSettings;
import com.aresstack.argos.search.BrowserWebSearch;
import com.aresstack.argos.search.SearchEngine;
import com.aresstack.argos.search.SearchSettings;
import com.aresstack.argos.web.UrlPolicy;
import com.aresstack.argos.web.WebSession;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Argos as deployed (real Camoufox, network boundary, MCP over Streamable HTTP), wired to the fixture world's
 * search engine. One browser serves every model and profile of a run; every task gets a fresh MCP server and
 * web session with the profile's tools, so link ids and history never leak from one task into the next.
 */
public final class ArgosUnderTest implements AutoCloseable {
    private final ArgosConfig config;
    private final CamoufoxBrowser browser;
    private final UrlPolicy urlPolicy;
    private final ResearchDocuments documents;
    private final FixtureWorld world;
    private final int partCharacters;

    public ArgosUnderTest(ArgosConfig installed, Path profileDirectory, boolean headless, FixtureWorld world) {
        this(installed, profileDirectory, headless, world, WebSession.DEFAULT_PART_CHARACTERS);
    }

    /** @param partCharacters web_read part size, for A/B runs against small context lengths */
    public ArgosUnderTest(ArgosConfig installed, Path profileDirectory, boolean headless, FixtureWorld world,
                          int partCharacters) {
        installed.requireBrowserConfiguration();
        // Loopback is admitted by rule (the fixture world); everything else keeps the strict boundary.
        NetworkSettings network = NetworkSettings.parse("strict", "127.0.0.1,::1", null, null);
        this.config = new ArgosConfig("127.0.0.1", 0, installed.executable(), installed.launchOptions(),
                profileDirectory, headless, null, "none", null, "argos", installed.toolProfile(), installed.readiness(),
                network, false);
        // The fixture world's .test domains resolve to loopback; the policy and the proxy still decide.
        this.browser = new CamoufoxBrowser(config, FixtureWorld.resolver());
        this.urlPolicy = UrlPolicy.from(network, FixtureWorld.resolver());
        this.documents = new ResearchDocuments(browser, new DocumentStore(), urlPolicy);
        this.world = world;
        this.partCharacters = partCharacters;
    }

    /**
     * Starts a fresh MCP endpoint for one task; close it when the task is done. Fixture tasks search the
     * fixture world's engine; live tasks use the engines configured for Argos (SEARCH_ENGINES).
     */
    public Session startSession(EvalProfile profile, boolean live) throws Exception {
        browser.clear();
        SearchSettings search = live ? SearchSettings.fromEnvironment()
                : new SearchSettings(List.of(SearchEngine.custom(world.searchTemplate())), 10, Duration.ofMinutes(10));
        WebSession web = new WebSession(browser, urlPolicy, new BrowserWebSearch(browser, search), partCharacters,
                WebSession.DEFAULT_MAX_LINKS);
        List<SyncToolSpecification> tools = new ArrayList<>(new ArgosTools(browser, web,
                ResearchSettings.fromEnvironment(), new DocumentAndBatchTools(documents)).forProfile(profile.base));
        if (profile.compactInteraction) tools.addAll(new ArgosBrowsingTools(browser).compact());
        ArgosApplication.RunningServer server = ArgosApplication.serve(config, tools);
        return new Session(server, "http://127.0.0.1:" + server.port());
    }

    public record Session(ArgosApplication.RunningServer server, String url) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }

    @Override
    public void close() {
        browser.close();
        documents.close();
    }
}
