package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.WikipediaArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    private static final int MAX_ARTICLES = 10;

    // System / meta pages that are not real articles
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "Main_Page", "Special:", "Wikipedia:", "File:", "Portal:",
            "Help:", "Template:", "User:", "Talk:", "Category:", "-"
    );

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

        String response = restTemplate.getForObject(url, String.class);
        if (response == null) return List.of();

        return parseArticles(response);
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
        return false;
    }
}
