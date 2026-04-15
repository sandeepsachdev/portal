package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.TrendItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches today's top trending topics.
 *
 * Primary  : Twitter / X API v1.1 trends endpoint – requires TWITTER_BEARER_TOKEN env var.
 *            Sydney WOEID = 1105779, Australia WOEID = 23424803.
 * Fallback : Google Trends Daily RSS for Australia (free, no auth required).
 *
 * Set env var TWITTER_BEARER_TOKEN to enable the Twitter path.
 */
@Service
public class TrendsService {

    private static final Logger log = LoggerFactory.getLogger(TrendsService.class);

    private static final String TWITTER_TRENDS_URL =
            "https://api.twitter.com/1.1/trends/place.json?id=23424803"; // Australia

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
     * Returns today's top 10 trends. Uses Twitter API when bearer token is
     * configured, otherwise falls back to Google Trends Daily for Australia.
     */
    public List<TrendItem> getTopTrends() {
        if (twitterBearerToken != null && !twitterBearerToken.isBlank()) {
            try {
                return fetchTwitterTrends();
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

        // Twitter response is an array; first element has "trends"
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

    // ── Google Trends (fallback) ─────────────────────────────────────────────

    private List<TrendItem> fetchGoogleTrends() {
        List<TrendItem> items = new ArrayList<>();
        try {
            // Parse the RSS manually to avoid pulling in full ROME dependency path
            String xml = restTemplate.getForObject(GOOGLE_TRENDS_RSS, String.class);
            if (xml == null) return items;

            // Extract <title> tags inside <item> blocks
            String[] itemBlocks = xml.split("<item>");
            int rank = 1;
            for (int i = 1; i < itemBlocks.length && items.size() < 10; i++) {
                String block = itemBlocks[i];
                String title = extractXmlTag(block, "title");
                String link  = extractXmlTag(block, "link");
                if (title != null && !title.isBlank()) {
                    items.add(new TrendItem(rank++, title, "", link != null ? link : ""));
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Google Trends: {}", e.getMessage());
        }
        return items;
    }

    private String extractXmlTag(String xml, String tag) {
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        int end   = xml.indexOf(close);
        if (start < 0 || end < 0) return null;
        return xml.substring(start + open.length(), end).trim()
                  .replaceAll("<!\\[CDATA\\[|]]>", "")
                  .trim();
    }

    /** Returns true when the Twitter bearer token is configured. */
    public boolean isTwitterConfigured() {
        return twitterBearerToken != null && !twitterBearerToken.isBlank();
    }
}
