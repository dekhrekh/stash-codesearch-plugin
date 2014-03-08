package com.palantir.stash.codesearch.search;

import com.atlassian.plugin.webresource.WebResourceManager;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.atlassian.stash.server.ApplicationPropertiesService;
import com.atlassian.stash.user.*;
import com.atlassian.stash.repository.*;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.palantir.stash.codesearch.manager.RepositoryServiceManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.*;
import javax.servlet.http.*;
import org.apache.commons.io.FilenameUtils;
import org.elasticsearch.action.search.*;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.*;
import org.elasticsearch.search.highlight.*;
import org.joda.time.*;
import org.joda.time.format.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.palantir.stash.codesearch.elasticsearch.ElasticSearch.*;
import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.*;

public class SearchServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(SearchServlet.class);

    private static final TimeValue SEARCH_TIMEOUT = new TimeValue(10000);

    private static final ImmutableSet<String> NO_HIGHLIGHT = ImmutableSet.of(
        "txt",
        "exe"
    );

    // TODO: de-magic-numberify these values

    private static final int PREVIEW_LINES = 10;

    private static final int MAX_MATCH_LINES = 50;

    private static final int PAGE_SIZE = 50;

    private static final int MAX_FRAGMENTS = 50;

    private static final float COMMIT_SUBJECT_BOOST = 4.0F;

    private static final float COMMIT_BODY_BOOST = 2.0F;

    private static final float COMMIT_HASH_BOOST = 50.0F;

    private static final float FILE_PATH_BOOST = 5.0F;

    private static final DateTimeFormatter TIME_PARSER = ISODateTimeFormat.dateTime();

    private static final DateTimeFormatter DATE_PARSER = ISODateTimeFormat.date();

    private static final DateTimeFormatter TIME_PRINTER = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm");

    private final RepositoryServiceManager repositoryServiceManager;

    private final PermissionValidationService validationService;

    private final SoyTemplateRenderer soyTemplateRenderer;

    private final StashAuthenticationContext authenticationContext;

    private final ApplicationPropertiesService propertiesService;

    private final WebResourceManager resourceManager;

    public SearchServlet (
            RepositoryServiceManager repositoryServiceManager,
            PermissionValidationService validationService,
            SoyTemplateRenderer soyTemplateRenderer,
            StashAuthenticationContext authenticationContext,
            ApplicationPropertiesService propertiesService,
            WebResourceManager resourceManager) {
        this.repositoryServiceManager = repositoryServiceManager;
        this.validationService = validationService;
        this.soyTemplateRenderer = soyTemplateRenderer;
        this.authenticationContext = authenticationContext;
        this.propertiesService = propertiesService;
        this.resourceManager = resourceManager;
    }

    private String getStringFromMap (Map<String, ? extends Object> map, String key) {
        Object o = map.get(key);
        if (o == null) {
            return "";
        }
        return o.toString();
    }

    private String getDateStringFromMap (Map<String, ? extends Object> map, String key) {
        Object o = map.get(key);
        if (o == null) {
            return "";
        }
        try {
            return TIME_PRINTER.print(TIME_PARSER.parseDateTime(o.toString()));
        } catch (Exception e) {
            return "";
        }
    }

    // Returns map view of search hits for soy templates
    private ImmutableMap<String, Object> searchHitToDataMap (
            SearchHit hit, Map<String, Repository> repoMap) {
        ImmutableMap.Builder<String, Object> hitData = new ImmutableMap.Builder<String, Object>();
        Map<String, Object> hitSource = hit.getSource();

        String type = hit.getType();
        hitData.put("type", type);

        String project = getStringFromMap(hitSource, "project");
        hitData.put("project", project);

        String repository = getStringFromMap(hitSource, "repository");
        hitData.put("repository", repository);

        // Validate permissions & build hit data map
        String repoId = project + "^" + repository;
        Repository repoObject = repoMap.get(repoId);
        if (repoObject != null &&
                repoObject.getProject().getKey().equals(project) &&
                repoObject.getSlug().equals(repository)) {

            // Generate refs array
            ImmutableSortedSet<String> refSet;
            try {
                refSet = ImmutableSortedSet.copyOf((Iterable <String>) hitSource.get("refs"));
            } catch (Exception e) {
                log.warn("Invalid refs collection detected for element in {}/{}", project, repository, e);
                return null;
            }
            if (refSet.isEmpty()) {
                log.warn("Detected empty refs collection for element in {}/{}", project, repository);
                return null;
            }
            hitData.put("refs", refSet);

            // Human-readable labels
            hitData
                .put("projectname", repoObject.getProject().getName())
                .put("repositoryname", repoObject.getName());

            if (type.equals("commit")) {
                hitData
                    .put("hash", getStringFromMap(hitSource, "hash"))
                    .put("subject", getStringFromMap(hitSource, "subject"))
                    .put("body", getStringFromMap(hitSource, "body"))
                    .put("commitDate", getDateStringFromMap(hitSource, "commitdate"))
                    .put("authorName", getStringFromMap(hitSource, "authorname"))
                    .put("authorEmail", getStringFromMap(hitSource, "authoremail"));

            } else if (type.equals("file")) {
                HighlightField highlightField = hit.getHighlightFields().get("contents");
                String path = getStringFromMap(hitSource, "path");
                String primaryRef = "refs/heads/master";
                if (!refSet.contains(primaryRef)) {
                    primaryRef = refSet.iterator().next();
                }
                String contents = getStringFromMap(hitSource, "contents");
                SourceSearch searchedContents = SourceSearch.search(
                    contents, highlightField, 1, PREVIEW_LINES, MAX_MATCH_LINES);
                String extension = FilenameUtils.getExtension(path).toLowerCase();

                hitData
                    .put("path", path)
                    .put("blob", getStringFromMap(hitSource, "blob"))
                    .put("primaryRef", primaryRef)
                    .put("sourceLines", searchedContents.getJoinedLines())
                    .put("sourceLineNums", searchedContents.getJoinedLineNums())
                    .put("isPreview", searchedContents.isPreview())
                    .put("shownLines", searchedContents.getLines().length)
                    .put("excessLines", searchedContents.getExcess())
                    .put("extension", extension)
                    .put("noHighlight", NO_HIGHLIGHT.contains(extension));
            }
        }

        return hitData.build();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        StashUser user = authenticationContext.getCurrentUser();
        if (user == null) {
            try {
                resp.sendRedirect("/stash/login");
            } catch (Exception e) {
                log.error("Unable to redirect unauthenticated user to login page", e);
            }
            return;
        }

        SearchParams params = SearchParams.getParams(req, DateTimeZone.forTimeZone(propertiesService.getDefaultTimeZone()));

        // Execute ES query
        int pages = 0;
        long totalHits = 0;
        long searchTime = 0;
        SearchHit[] currentHits = {};
        String error = "";
        ArrayList<ImmutableMap<String, Object>> hitArray =
            new ArrayList<ImmutableMap<String, Object>>(currentHits.length);
        if (params.doSearch) {
            ImmutableMap<String, Repository> repoMap = repositoryServiceManager.getRepositoryMap(
                validationService);
            if (repoMap.isEmpty()) {
                error = "You do not have permissions to access any repositories";
            }
            int startIndex = params.page * PAGE_SIZE;
            SearchRequestBuilder esReq = ES_CLIENT.prepareSearch(ES_SEARCHALIAS)
                .setFrom(startIndex)
                .setSize(PAGE_SIZE)
                .setTimeout(SEARCH_TIMEOUT)
                .setFetchSource(true);

            if (error != null && !error.isEmpty()) {
                log.warn("Not performing search due to error {}", error);

            } else {
                // Build query source and perform query
                QueryBuilder query = matchAllQuery();
                if (params.searchString != null && !params.searchString.isEmpty()) {
                    QueryStringQueryBuilder queryStringQuery = queryString(params.searchString)
                        .analyzeWildcard(false)
                        .lenient(true)
                        .defaultOperator(QueryStringQueryBuilder.Operator.AND);
                    if (params.searchCommits) {
                        queryStringQuery
                            .field("commit.subject", COMMIT_SUBJECT_BOOST)
                            .field("commit.hash", COMMIT_HASH_BOOST)
                            .field("commit.body", COMMIT_BODY_BOOST);
                    }
                    if (params.searchFilenames) {
                        queryStringQuery.field("file.path", FILE_PATH_BOOST);
                    }
                    if (params.searchCode) {
                        queryStringQuery.field("file.contents", 1);
                    }
                    query = queryStringQuery;
                }
                FilterBuilder filter = andFilter(
                    SearchFilters.aclFilter(repoMap),
                    SearchFilters.refFilter(params.refNames.split(",")),
                    SearchFilters.projectFilter(params.projectKeys.split(",")),
                    SearchFilters.repositoryFilter(params.repoNames.split(",")),
                    SearchFilters.authorFilter(params.authorNames.split(",")),
                    SearchFilters.dateRangeFilter(params.committedAfter, params.committedBefore));
                FilteredQueryBuilder finalQuery = filteredQuery(query, filter);
                esReq.setQuery(finalQuery)
                    .setHighlighterPreTags("\u0001")
                    .setHighlighterPostTags("\u0001")
                    .addHighlightedField("contents", 1, MAX_FRAGMENTS);

                String[] typeArray = {};
                if (params.searchCommits) {
                    if (params.searchFilenames || params.searchCode) {
                        typeArray = new String[]{"commit", "file"};
                    } else {
                        typeArray = new String[]{"commit"};
                    }
                } else if (params.searchFilenames || params.searchCode) {
                    typeArray = new String[]{"file"};
                }
                esReq.setTypes(typeArray);

                SearchResponse esResp = null;
                try {
                    esResp = esReq.get();
                } catch (SearchPhaseExecutionException e) {
                    log.warn("Query failure", e);
                    error = "Make sure your query conforms to the Lucene/Elasticsearch query string syntax.";
                }

                if (esResp != null) {
                    SearchHits esHits = esResp.getHits();
                    totalHits = esHits.getTotalHits();
                    pages = (int) Math.min((long) Integer.MAX_VALUE, (totalHits + PAGE_SIZE - 1) / PAGE_SIZE);
                    currentHits = esHits.getHits();
                    searchTime = esResp.getTookInMillis();
                    for (ShardSearchFailure failure : esResp.getShardFailures()) {
                        log.warn("Shard failure {}", failure.reason());
                        if (error == null || error.isEmpty()) {
                            error = "Shard failure: " + failure.reason();
                        }
                    }
                }
 
            }

            // Iterate through current page of search hits
            for (SearchHit hit : currentHits) {
                ImmutableMap<String, Object> hitData = searchHitToDataMap(hit, repoMap);
                if (hitData != null) {
                    hitArray.add(hitData);
                }
            }
        }

        // Render page
        resourceManager.requireResource("com.palantir.stash.stash-code-search:scs-resources");
        resourceManager.requireResource("com.atlassian.auiplugin:aui-date-picker");
        resp.setContentType("text/html");
        try {
            String queryString = req.getQueryString();
            String fullUri = req.getRequestURI() + "?" +
                (queryString == null ? "" : queryString.replaceAll("&?page=\\d*", ""));
            int startIndex = fullUri.indexOf("/plugins/");
            String uriPrefix = startIndex < 0 ? "" : fullUri.substring(0, startIndex);
            ImmutableMap<String, Object> data = new ImmutableMap.Builder<String, Object>()
                .put("pages", pages)
                .put("currentPage", params.page)
                .put("prevParams", params.soyParams)
                .put("doSearch", params.doSearch)
                .put("totalHits", totalHits)
                .put("hitArray", hitArray)
                .put("error", error)
                .put("fullUri", fullUri)
                .put("uriPrefix", uriPrefix)
                .put("resultFrom", Math.min(totalHits, params.page * PAGE_SIZE + 1))
                .put("resultTo", Math.min(totalHits, (params.page + 1) * PAGE_SIZE))
                .put("searchTime", searchTime)
                .build();
            soyTemplateRenderer.render(resp.getWriter(),
                "com.palantir.stash.stash-code-search:codesearch-soy",
                "plugin.page.codesearch.searchPage",
                data);
        } catch (Exception e) {
            log.error("Error rendering Soy template", e);
        }
    }

    // Utility class for parsing and storing search parameters from an http request
    private static class SearchParams {

        public final boolean doSearch;
        public final String searchString;
        public final boolean searchCode;
        public final boolean searchFilenames;
        public final boolean searchCommits;
        public final String projectKeys;
        public final String repoNames;
        public final String refNames;
        public final String authorNames;
        public final String committedAfterStr;
        public final String committedBeforeStr;
        public final int page;
        public final ReadableInstant committedAfter;
        public final ReadableInstant committedBefore;
        public final ImmutableMap<String, Object> soyParams;

        private SearchParams (boolean doSearch, String searchString, boolean searchCode,
                boolean searchFilenames, boolean searchCommits, String projectKeys,
                String repoNames, String refNames, String authorNames, int page,
                String committedAfterStr, String committedBeforeStr, DateTimeZone tz) {
            this.doSearch = doSearch;
            this.searchString = searchString;
            this.searchCode = searchCode;
            this.searchFilenames = searchFilenames;
            this.searchCommits = searchCommits;
            this.projectKeys = projectKeys;
            this.repoNames = repoNames;
            this.refNames = refNames;
            this.authorNames = authorNames;
            this.page = page;
            this.committedAfterStr = committedAfterStr;
            this.committedBeforeStr = committedBeforeStr;
            ImmutableMap.Builder<String, Object> paramBuilder = new ImmutableMap.Builder<String, Object>()
                .put("searchString", searchString)
                .put("searchCode", searchCode)
                .put("searchFilenames", searchFilenames)
                .put("searchCommits", searchCommits)
                .put("projectKeys", projectKeys)
                .put("repoNames", repoNames)
                .put("refNames", refNames)
                .put("authorNames", authorNames)
                .put("committedAfter", committedAfterStr)
                .put("committedBefore", committedBeforeStr)
                .put("page", page);
            this.soyParams = paramBuilder.build();

            MutableDateTime afterDateTime;
            try {
                afterDateTime = DATE_PARSER.withZone(tz).parseMutableDateTime(committedAfterStr);
                afterDateTime.setSecondOfDay(0);
                afterDateTime.setMillisOfSecond(0);
            } catch (Exception e) {
                afterDateTime= null;
            }
            this.committedAfter = afterDateTime;

            MutableDateTime beforeDateTime;
            try {
                beforeDateTime = DATE_PARSER.withZone(tz).parseMutableDateTime(committedBeforeStr);
                beforeDateTime.setHourOfDay(23);
                beforeDateTime.setMinuteOfHour(59);
                beforeDateTime.setSecondOfMinute(59);
                beforeDateTime.setMillisOfSecond(999);
            } catch (Exception e) {
                beforeDateTime = null;
            }
            this.committedBefore = beforeDateTime;
        }

        public static SearchParams getParams (HttpServletRequest req, DateTimeZone tz) {
            boolean doSearch = true;

            String searchString = req.getParameter("searchString");
            if (searchString == null) {
                doSearch = false;
                searchString = "";
            }

            boolean searchCode = "on".equals(req.getParameter("searchCode"));
            boolean searchFilenames = "on".equals(req.getParameter("searchFilenames"));
            boolean searchCommits = "on".equals(req.getParameter("searchCommits"));
            if (!searchFilenames && !searchCode && !searchCommits) {
                // Reset checkboxes
                searchFilenames = searchCode = searchCommits = true;
            }

            String projectKeys = req.getParameter("projectKeys");
            if (projectKeys == null) {
                projectKeys = "";
            }

            String repoNames = req.getParameter("repoNames");
            if (repoNames == null) {
                repoNames = "";
            }

            String refNames = req.getParameter("refNames");
            if (refNames == null) {
                refNames = "";
            }

            String authorNames = req.getParameter("authorNames");
            if (authorNames == null) {
                authorNames = "";
            }

            String committedAfterStr = req.getParameter("committedAfter");
            if (committedAfterStr == null) {
                committedAfterStr = "";
            }
             
            String committedBeforeStr = req.getParameter("committedBefore");
            if (committedBeforeStr == null) {
                committedBeforeStr = "";
            }

            int page;
            try {
                page = Math.max(0, Integer.parseInt(req.getParameter("page")));
            } catch (Exception e) {
                page = 0;
            }

            return new SearchParams(doSearch, searchString, searchCode, searchFilenames,
                searchCommits, projectKeys, repoNames, refNames, authorNames, page,
                committedAfterStr, committedBeforeStr, tz);
        }
    }

}