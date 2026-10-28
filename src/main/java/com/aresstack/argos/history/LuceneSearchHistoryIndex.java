package com.aresstack.argos.history;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Classic Lucene full-text index of one chat's history under {@code history/lucene}, modeled on AskAI's
 * {@code LuceneTextPassageIndex}: {@link FSDirectory}, {@link StandardAnalyzer} keyword matching,
 * {@link IndexWriter#updateDocument} with stable ids (idempotent) and every Lucene resource opened and closed
 * within one operation, so no reader or writer stays open. No embeddings, no vectors.
 * <p>
 * The index is a rebuildable projection of {@link SearchHistoryRepository}: its commit data records the last event
 * {@code seq} it contains, {@link #catchUp} adds whatever the log has beyond that, and an unreadable index is
 * deleted and rebuilt from the log. Lucene types never leave this class.
 * <p>
 * Documents: one per search event ({@code search:<seq>}) and one per page snapshot ({@code page:<snapshotId>},
 * updated with each visit) and one per distinct stored response body ({@code resource:<resourceId>}, updated with each
 * observation, so identical bodies never add hits; binary bodies are indexed by URL and MIME type only, optional
 * script and style text goes into a low-weighted field so it cannot crowd out page content). The searchable text is the query, hit titles/URLs/snippets, the page title, URL,
 * readable text and links, never the archived HTML.
 */
public final class LuceneSearchHistoryIndex {
    private static final String F_ID = "id";
    private static final String F_TYPE = "type";
    private static final String F_AT = "at";
    private static final String F_CONTEXT = "contextId";
    private static final String F_SNAPSHOT = "snapshotId";
    private static final String F_URL_STORED = "urlStored";
    private static final String F_TITLE_STORED = "titleStored";
    private static final String F_QUERY = "query";
    private static final String F_TITLE = "title";
    private static final String F_URL = "url";
    private static final String F_CONTENT = "content";
    private static final String F_LINKS = "links";
    private static final String F_CODE = "code";
    private static final String F_RESOURCE = "resourceId";
    /** Indexed text of one resource; the stored body may be longer. */
    static final int MAX_RESOURCE_TEXT = 2_000_000;
    private static final int MAX_TERMS = 64;
    private static final String LAST_SEQ = "argos.lastSeq";
    private static final Map<String, Float> FIELDS = Map.of(
            F_QUERY, 3f, F_TITLE, 3f, F_URL, 2f, F_CONTENT, 1f, F_LINKS, 0.5f, F_CODE, 0.2f);

    private final Path directory;

    public LuceneSearchHistoryIndex(Path directory) {
        this.directory = directory;
    }

    /** One hit: a search event or a page snapshot. Neutral types only. */
    public record Hit(String type, String id, Instant at, String title, String url, String snapshotId,
                      String contextId, float score, String resourceId) {
        public Hit(String type, String id, Instant at, String title, String url, String snapshotId,
                   String contextId, float score) {
            this(type, id, at, title, url, snapshotId, contextId, score, null);
        }
    }

    /** The last event seq the index contains; 0 when there is no index yet. */
    public synchronized long indexedSeq() throws IOException {
        try (Directory dir = FSDirectory.open(directory)) {
            if (!DirectoryReader.indexExists(dir)) return 0;
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                String value = reader.getIndexCommit().getUserData().get(LAST_SEQ);
                return value == null ? 0 : Long.parseLong(value);
            }
        }
    }

    /**
     * Brings the index up to the repository's last event. A damaged index is removed and rebuilt from the
     * canonical data; the canonical data is never touched.
     */
    public synchronized void catchUp(SearchHistoryRepository repository) throws IOException {
        long target = repository.lastSeq();
        long indexed;
        try {
            indexed = indexedSeq();
        } catch (IOException | RuntimeException damaged) {
            removeIndex();
            indexed = 0;
        }
        if (indexed >= target) return;
        try {
            write(repository, indexed);
        } catch (IOException | RuntimeException damaged) {
            if (indexed == 0) throw damaged;
            removeIndex();
            write(repository, 0);
        }
    }

    /** Throws the index away and builds it again from the repository. */
    public synchronized void rebuild(SearchHistoryRepository repository) throws IOException {
        removeIndex();
        write(repository, 0);
    }

    private void write(SearchHistoryRepository repository, long afterSeq) throws IOException {
        Files.createDirectories(directory);
        try (Analyzer analyzer = new StandardAnalyzer(); Directory dir = FSDirectory.open(directory)) {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                long[] last = {afterSeq};
                repository.forEach(afterSeq, event -> {
                    switch (event) {
                        case HistoryEvent.Search search -> writer.updateDocument(
                                new Term(F_ID, "search:" + search.seq()), searchDocument(search));
                        case HistoryEvent.PageVisit visit -> {
                            StoredPage page = repository.page(visit.snapshotId()).orElse(null);
                            if (page != null) {
                                writer.updateDocument(new Term(F_ID, "page:" + page.snapshotId()),
                                        pageDocument(page, visit));
                            }
                        }
                        case HistoryEvent.NetworkResource observed -> {
                            StoredResource resource = repository.resource(observed.resourceId()).orElse(null);
                            if (resource != null) {
                                writer.updateDocument(new Term(F_ID, "resource:" + resource.resourceId()),
                                        resourceDocument(resource, observed, text(repository, resource)));
                            }
                        }
                    }
                    last[0] = event.seq();
                });
                writer.setLiveCommitData(Map.of(LAST_SEQ, Long.toString(last[0])).entrySet());
                writer.commit();
            }
        }
    }

    private static Document searchDocument(HistoryEvent.Search search) {
        Document doc = new Document();
        doc.add(new StringField(F_ID, "search:" + search.seq(), Field.Store.YES));
        doc.add(new StoredField(F_TYPE, "search"));
        doc.add(new StoredField(F_AT, search.at().toString()));
        if (search.contextId() != null) doc.add(new StoredField(F_CONTEXT, search.contextId()));
        doc.add(new StoredField(F_TITLE_STORED, search.query()));
        doc.add(new TextField(F_QUERY, search.query(), Field.Store.NO));
        StringBuilder titles = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        StringBuilder snippets = new StringBuilder();
        for (HistoryEvent.Result result : search.results()) {
            titles.append(nonNull(result.title())).append('\n');
            urls.append(nonNull(result.url())).append('\n');
            snippets.append(nonNull(result.snippet())).append('\n');
        }
        doc.add(new TextField(F_TITLE, titles.toString(), Field.Store.NO));
        doc.add(new TextField(F_URL, urls.toString(), Field.Store.NO));
        doc.add(new TextField(F_CONTENT, snippets.toString(), Field.Store.NO));
        return doc;
    }

    private static Document pageDocument(StoredPage page, HistoryEvent.PageVisit visit) {
        Document doc = new Document();
        doc.add(new StringField(F_ID, "page:" + page.snapshotId(), Field.Store.YES));
        doc.add(new StoredField(F_TYPE, "page"));
        // The latest visit decides the shown time and context; all visits share the snapshot's content.
        doc.add(new StoredField(F_AT, visit.at().toString()));
        if (visit.contextId() != null) doc.add(new StoredField(F_CONTEXT, visit.contextId()));
        doc.add(new StoredField(F_SNAPSHOT, page.snapshotId()));
        doc.add(new StoredField(F_URL_STORED, nonNull(page.url())));
        doc.add(new StoredField(F_TITLE_STORED, page.title()));
        doc.add(new TextField(F_TITLE, page.title(), Field.Store.NO));
        doc.add(new TextField(F_URL, nonNull(page.url()), Field.Store.NO));
        doc.add(new TextField(F_CONTENT, page.content(), Field.Store.NO));
        doc.add(new TextField(F_LINKS, String.join("\n", page.links()), Field.Store.NO));
        return doc;
    }

    private static String text(SearchHistoryRepository repository, StoredResource resource) throws IOException {
        if (!resource.textual()) return "";
        Path body = repository.resourceBody(resource.resourceId());
        if (!Files.isRegularFile(body)) return "";
        String text = Files.readString(body, java.nio.charset.StandardCharsets.UTF_8);
        if ("html".equals(resource.textKind())) text = org.jsoup.Jsoup.parse(text).text();
        return text.length() > MAX_RESOURCE_TEXT ? text.substring(0, MAX_RESOURCE_TEXT) : text;
    }

    private static Document resourceDocument(StoredResource resource, HistoryEvent.NetworkResource observed,
                                             String text) {
        Document doc = new Document();
        doc.add(new StringField(F_ID, "resource:" + resource.resourceId(), Field.Store.YES));
        doc.add(new StoredField(F_TYPE, "resource"));
        doc.add(new StoredField(F_AT, observed.at().toString()));
        if (observed.contextId() != null) doc.add(new StoredField(F_CONTEXT, observed.contextId()));
        doc.add(new StoredField(F_RESOURCE, resource.resourceId()));
        doc.add(new StoredField(F_URL_STORED, nonNull(observed.url())));
        doc.add(new StoredField(F_TITLE_STORED, nonNull(resource.mimeType())));
        doc.add(new TextField(F_URL, nonNull(observed.url()), Field.Store.NO));
        doc.add(new TextField("code".equals(resource.textKind()) ? F_CODE : F_CONTENT, text, Field.Store.NO));
        return doc;
    }

    /** Keyword search over this chat's index; empty when there is no index or no searchable term. */
    public synchronized List<Hit> search(String queryText, int limit) throws IOException {
        List<Hit> hits = new ArrayList<>();
        try (Analyzer analyzer = new StandardAnalyzer(); Directory dir = FSDirectory.open(directory)) {
            if (!DirectoryReader.indexExists(dir)) return hits;
            Query query = toQuery(analyzer, queryText);
            if (query == null) return hits;
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs top = searcher.search(query, Math.max(1, limit));
                for (ScoreDoc scored : top.scoreDocs) {
                    Document d = searcher.doc(scored.doc);
                    hits.add(new Hit(d.get(F_TYPE), d.get(F_ID), Instant.parse(d.get(F_AT)), d.get(F_TITLE_STORED),
                            d.get(F_URL_STORED), d.get(F_SNAPSHOT), d.get(F_CONTEXT), scored.score,
                            d.get(F_RESOURCE)));
                }
            }
        }
        return hits;
    }

    /** As in AskAI: the analyzed terms, each a SHOULD clause, here over the history's fields with boosts. */
    private static Query toQuery(Analyzer analyzer, String queryText) throws IOException {
        List<String> terms = new ArrayList<>();
        try (TokenStream tokens = analyzer.tokenStream(F_CONTENT, queryText == null ? "" : queryText)) {
            CharTermAttribute attribute = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            // Distinct terms, bounded: six clauses per term must stay below BooleanQuery's clause limit.
            while (tokens.incrementToken() && terms.size() < MAX_TERMS) {
                if (!terms.contains(attribute.toString())) terms.add(attribute.toString());
            }
            tokens.end();
        }
        if (terms.isEmpty()) return null;
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String term : terms) {
            for (Map.Entry<String, Float> field : FIELDS.entrySet()) {
                builder.add(new BoostQuery(new TermQuery(new Term(field.getKey(), term)), field.getValue()),
                        BooleanClause.Occur.SHOULD);
            }
        }
        return builder.build();
    }

    private void removeIndex() throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
