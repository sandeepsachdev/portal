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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches today's top trending topics.
 *
 * Primary  : Twitter / X API v1.1 – requires TWITTER_BEARER_TOKEN env var.
 *            Note: Twitter trends require at least the Basic paid tier ($100/mo).
 * Fallback : Google Trends Daily RSS for Australia (free, no auth).
 */
@Service
public class TrendsService {

    private static final Logger log = LoggerFactory.getLogger(TrendsService.class);

    private static final String TWITTER_TRENDS_URL =
            "https://api.twitter.com/1.1/trends/place.json?id=23424803"; // Australia WOEID

    private static final String GOOGLE_TRENDS_RSS =
            "https://trends.google.com/trends/trendingsearches/daily/rss?geo=AU";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${twitter.bearer.token:}")
    private String twitterBearerToken;

    public TrendsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns today's top 10 trends.
     * Uses Twitter API when bearer token is configured, otherwise Google Trends.
     */
    public List<TrendItem> getTopTrends() {
        if (twitterBearerToken != null && !twitterBearerToken.isBlank()) {
            try {
                List<TrendItem> items = fetchTwitterTrends();
                if (!items.isEmpty()) return items;
                log.warn("Twitter trends returned empty list, falling back to Google Trends");
            } catch (Exception e) {
                log.warn("Twitter trends failed ({}), falling back to Google Trends", e.getMessage());
            }
        } else {
            log.info("TWITTER_BEARER_TOKEN not set – using Google Trends Daily RSS for Australia");
        }
        return fetchGoogleTrends();
    }

    // ── Twitter ──────────────────────────────────────────────────────────────

    private List<TrendItem> fetchTwitterTrends() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(twitterBearerToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                TWITTER_TRENDS_URL, HttpMethod.GET, entity, String.class);

        return parseTwitterTrends(response.getBody());
    }

    private List<TrendItem> parseTwitterTrends(String json) throws Exception {
        List<TrendItem> items = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);

        // Twitter v1.1 response: array of location objects, each with a "trends" array
        JsonNode trendsNode = root.isArray() ? root.get(0).path("trends") : root.path("trends");
        if (trendsNode == null || !trendsNode.isArray()) return items;

        int rank = 1;
        for (JsonNode node : trendsNode) {
            String name = node.path("name").asText("");
            if (name.isBlank()) continue;

            String volume = "";
            JsonNode volNode = node.path("tweet_volume");
            if (!volNode.isNull() && volNode.isNumber()) {
                long v = volNode.asLong();
                volume = v >= 1_000_000 ? String.format("%.1fM", v / 1_000_000.0)
                       : v >= 1_000     ? String.format("%.1fK", v / 1_000.0)
                       : String.valueOf(v);
            }

            String url = node.path("url").asText("");
            items.add(new TrendItem(rank++, name, volume, url));
            if (items.size() == 10) break;
        }
        return items;
    }

    // ── Google Trends (fallback – parsed with ROME) ──────────────────────────

    private List<TrendItem> fetchGoogleTrends() {
        try {
            // Google requires a browser-like User-Agent; otherwise returns 403
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (compatible; PortalBot/1.0; +https://github.com)");
            headers.set(HttpHeaders.ACCEPT, "application/rss+xml, application/xml, text/xml");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    GOOGLE_TRENDS_RSS, HttpMethod.GET, entity, byte[].class);

            if (response.getBody() == null) {
                log.warn("Google Trends RSS returned empty body");
                return List.of();
            }

            // Parse with ROME – handles CDATA, namespaces, and encodings correctly
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(true);
            SyndFeed feed = input.build(
                    new XmlReader(new ByteArrayInputStream(response.getBody())));

            List<TrendItem> items = new ArrayList<>();
            int rank = 1;
            for (SyndEntry entry : feed.getEntries()) {
                if (items.size() >= 10) break;
                String title = entry.getTitle();
                String link  = entry.getLink();
                if (title != null && !title.isBlank()) {
                    items.add(new TrendItem(rank++, title.trim(), "", link != null ? link : ""));
                }
            }
            log.info("Google Trends returned {} items for AU", items.size());
            return items;

        } catch (Exception e) {
            log.error("Failed to fetch Google Trends RSS: {}", e.getMessage());
            return List.of();
        }
    }

    /** Returns true when the Twitter bearer token is configured. */
    public boolean isTwitterConfigured() {
        return twitterBearerToken != null && !twitterBearerToken.isBlank();
    }
}
