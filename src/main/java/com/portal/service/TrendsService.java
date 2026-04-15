package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.TrendItem;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the most recent trending searches in Australia.
 *
 * Strategy (in order):
 *  1. Google Trends dailytrends JSON API – actual search queries + volume
 *  2. Google Trends RSS (trends.google.com.au) – fallback
 *
 * No API key required for either source.
 */
@Service
public class TrendsService {

    private static final Logger log = LoggerFactory.getLogger(TrendsService.class);

    // Unofficial JSON endpoint used by pytrends – returns real search queries
    // tz=-600  → AEST (UTC+10, minutes west of UTC in Google's convention)
    // ns=15    → news category (broader than default)
    private static final String DAILY_TRENDS_URL =
            "https://trends.google.com/trends/api/dailytrends" +
            "?hl=en-AU&tz=-600&geo=AU&ns=15";

    // RSS fallback
    private static final String RSS_PRIMARY =
            "https://trends.google.com.au/trending/rss?geo=AU&hl=en-AU";
    private static final String RSS_LEGACY =
            "https://trends.google.com.au/trends/trendingsearches/daily/rss?geo=AU&hl=en-AU";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TrendsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** Returns up to 10 of the most recent trending searches in Australia. */
    public List<TrendItem> getTopTrends() {
        // 1. Try the JSON API first – most accurate
        try {
            List<TrendItem> items = fetchDailyTrends();
            if (!items.isEmpty()) {
                log.info("Daily Trends API returned {} AU items, first: {}", items.size(), items.get(0).getName());
                return items;
            }
        } catch (Exception e) {
            log.warn("Daily Trends API failed ({}), trying RSS fallback", e.getMessage());
        }

        // 2. Fall back to RSS
        for (String rssUrl : new String[]{RSS_PRIMARY, RSS_LEGACY}) {
            List<TrendItem> result = tryFetchRss(rssUrl);
            if (!result.isEmpty()) return result;
        }
        return List.of();
    }

    // ── Google Trends dailytrends JSON API ────────────────────────────────────

    private List<TrendItem> fetchDailyTrends() throws Exception {
        HttpURLConnection conn = openConnection(DAILY_TRENDS_URL);

        int redirects = 0;
        while (redirects < 5) {
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == 307 || status == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                conn = openConnection(location);
                redirects++;
            } else {
                break;
            }
        }

        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("HTTP " + conn.getResponseCode());
        }

        // Google prefixes the JSON response with ")]}'\n" – strip it
        String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (body.startsWith(")]}'")) {
            body = body.substring(body.indexOf('\n') + 1);
        }

        return parseDailyTrends(body);
    }

    private List<TrendItem> parseDailyTrends(String json) throws Exception {
        List<TrendItem> items = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);

        JsonNode days = root.path("default").path("trendingSearchesDays");
        if (!days.isArray() || days.isEmpty()) return items;

        // Take the most recent day's searches
        JsonNode searches = days.get(0).path("trendingSearches");
        if (!searches.isArray()) return items;

        int rank = 1;
        for (JsonNode node : searches) {
            if (items.size() >= 10) break;

            String query = node.path("title").path("query").asText("").trim();
            if (query.isBlank()) continue;

            // formattedTrafficSource = "10K+ searches" or similar
            String volume = node.path("formattedTrafficSource").asText("").trim();

            String searchUrl = "https://www.google.com.au/search?q=" +
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);

            items.add(new TrendItem(rank++, query, volume, searchUrl));
        }
        return items;
    }

    // ── RSS fallback ──────────────────────────────────────────────────────────

    private List<TrendItem> tryFetchRss(String rssUrl) {
        try {
            HttpURLConnection conn = openConnection(rssUrl);

            int redirects = 0;
            while (redirects < 5) {
                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = openConnection(location);
                    redirects++;
                } else {
                    break;
                }
            }

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                log.warn("Google Trends RSS returned HTTP {} for {}", conn.getResponseCode(), rssUrl);
                return List.of();
            }

            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(true);
            SyndFeed feed = input.build(new XmlReader(conn.getInputStream()));

            List<TrendItem> items = new ArrayList<>();
            int rank = 1;
            for (SyndEntry entry : feed.getEntries()) {
                if (items.size() >= 10) break;
                String title = entry.getTitle();
                if (title != null && !title.isBlank()) {
                    String searchUrl = "https://www.google.com.au/search?q=" +
                            java.net.URLEncoder.encode(title.trim(), java.nio.charset.StandardCharsets.UTF_8);
                    items.add(new TrendItem(rank++, title.trim(), "", searchUrl));
                }
            }
            if (!items.isEmpty()) {
                log.info("RSS fallback [{}] returned {} AU items, first: {}",
                        rssUrl, items.size(), items.get(0).getName());
            }
            return items;

        } catch (Exception e) {
            log.warn("Failed to fetch Google Trends RSS from {}: {}", rssUrl, e.getMessage());
            return List.of();
        }
    }

    // ── Shared connection helper ──────────────────────────────────────────────

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept",
                "application/json,application/rss+xml,application/xml;q=0.9,*/*;q=0.7");
        conn.setRequestProperty("Accept-Language", "en-AU,en;q=0.9");
        conn.setRequestProperty("Cookie", "GL=AU; PREF=hl=en-AU;");
        return conn;
    }
}
