package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.WikipediaArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches the most-viewed Wikipedia articles for the current day using the
 * free, no-auth Wikimedia REST API.
 *
 * API docs: https://wikimedia.org/api/rest_v1/#/Pageviews_data/get_metrics_pageviews_top__project___access___year___month___day_
 */
@Service
public class WikipediaService {

    private static final Logger log = LoggerFactory.getLogger(WikipediaService.class);

    private static final String PAGEVIEWS_URL =
            "https://wikimedia.org/api/rest_v1/metrics/pageviews/top/" +
            "en.wikipedia/all-access/{year}/{month}/{day}";

    private static final String SUMMARY_URL =
            "https://en.wikipedia.org/api/rest_v1/page/summary/{title}";

    // Wikimedia requires a descriptive User-Agent; see https://w.wiki/4wJS
    private static final String USER_AGENT =
            "PortalDashboard/1.0 (sandeepsachdev17@gmail.com)";

    private static final int MAX_ARTICLES = 10;

    // System / meta pages that are not real articles
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "Main_Page", "Special:", "Wikipedia:", "File:", "Portal:",
            "Help:", "Template:", "User:", "Talk:", "Category:", "-"
    );

    // Adult / inappropriate content to exclude (case-insensitive exact match)
    private static final Set<String> BLOCKED_TERMS = Set.of(
            "xxx", ".xxx", "pornography", "porn"
    );

    // Dedicated pool for I/O-bound summary fetches — avoids starving the common ForkJoinPool
    private static final ExecutorService SUMMARY_POOL = Executors.newFixedThreadPool(MAX_ARTICLES);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WikipediaService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns up to {@value #MAX_ARTICLES} most-viewed Wikipedia articles.
     * Tries yesterday first (data always complete), falls back to two days ago.
     */
    public List<WikipediaArticle> getTopArticles() {
        // Yesterday's data is always fully available; today's may still be partial
        for (int daysBack = 1; daysBack <= 3; daysBack++) {
            try {
                LocalDate date = LocalDate.now().minusDays(daysBack);
                List<WikipediaArticle> articles = fetchForDate(date);
                if (!articles.isEmpty()) {
                    log.info("Wikipedia top articles loaded for {} ({} items)", date, articles.size());
                    return articles;
                }
            } catch (Exception e) {
                log.warn("Wikipedia pageviews fetch failed for -{} days: {}", daysBack, e.getMessage());
            }
        }
        log.error("All Wikipedia pageviews fetch attempts failed");
        return List.of();
    }

    private List<WikipediaArticle> fetchForDate(LocalDate date) throws Exception {
        String url = PAGEVIEWS_URL
                .replace("{year}",  String.valueOf(date.getYear()))
                .replace("{month}", String.format("%02d", date.getMonthValue()))
                .replace("{day}",   String.format("%02d", date.getDayOfMonth()));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        String response = resp.getBody();
        if (response == null) return List.of();

        List<WikipediaArticle> articles = parseArticles(response);
        enrichWithSummaries(articles, headers);
        return articles;
    }

    /** Fetches thumbnail + description for each article in parallel via the summary API. */
    private void enrichWithSummaries(List<WikipediaArticle> articles, HttpHeaders headers) {
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        List<CompletableFuture<Void>> futures = articles.stream().map(article ->
            CompletableFuture.runAsync(() -> {
                try {
                    String url = SUMMARY_URL.replace("{title}", article.getArticleKey());
                    ResponseEntity<String> resp = restTemplate.exchange(
                            url, HttpMethod.GET, entity, String.class);
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        JsonNode root = objectMapper.readTree(resp.getBody());
                        String desc = root.path("description").asText("").trim();
                        if (!desc.isBlank()) article.setDescription(desc);
                        String thumb = root.path("thumbnail").path("source").asText("").trim();
                        if (!thumb.isBlank()) article.setThumbnailUrl(thumb);
                    }
                } catch (Exception e) {
                    log.debug("Summary fetch failed for {}: {}", article.getArticleKey(), e.getMessage());
                }
            }, SUMMARY_POOL)
        ).toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Some Wikipedia summary fetches timed out or failed");
        }
    }

    private List<WikipediaArticle> parseArticles(String json) throws Exception {
        List<WikipediaArticle> articles = new ArrayList<>();

        JsonNode root  = objectMapper.readTree(json);
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) return articles;

        JsonNode articlesNode = items.get(0).path("articles");
        if (!articlesNode.isArray()) return articles;

        for (JsonNode node : articlesNode) {
            String key   = node.path("article").asText("");
            long   views = node.path("views").asLong(0);
            int    rank  = node.path("rank").asInt(0);

            if (shouldSkip(key)) continue;

            articles.add(new WikipediaArticle(rank, key, views));
            if (articles.size() == MAX_ARTICLES) break;
        }
        return articles;
    }

    private boolean shouldSkip(String key) {
        if (key.isBlank()) return true;
        for (String prefix : SKIP_PREFIXES) {
            if (key.equals(prefix) || key.startsWith(prefix)) return true;
        }
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        for (String term : BLOCKED_TERMS) {
            if (lower.equals(term) || lower.startsWith(term + "_") || lower.endsWith("_" + term)) return true;
        }
        return false;
    }
}
