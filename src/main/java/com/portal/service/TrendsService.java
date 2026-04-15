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

import java.net.HttpURLConnection;
import java.net.URL;
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

            items.add(new TrendItem(rank++, name, volume, node.path("url").asText("")));
            if (items.size() == 10) break;
        }
        return items;
    }

    // ── Google Trends (fallback) ─────────────────────────────────────────────
    //
    // RestTemplate sometimes fails on Google's chain of HTTPS redirects.
    // Use a raw URLConnection so we control redirect-following and User-Agent.

    private List<TrendItem> fetchGoogleTrends() {
        try {
            HttpURLConnection conn = openConnection(GOOGLE_TRENDS_RSS);

            // Follow up to 5 redirects manually so we keep the custom User-Agent
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
                log.warn("Google Trends RSS returned HTTP {}", conn.getResponseCode());
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

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(false); // we handle redirects manually above
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept",
                "application/rss+xml,application/xml;q=0.9,text/xml;q=0.8,*/*;q=0.7");
        conn.setRequestProperty("Accept-Language", "en-AU,en;q=0.9");
        return conn;
    }

    /** Returns true when the Twitter bearer token is configured. */
    public boolean isTwitterConfigured() {
        return twitterBearerToken != null && !twitterBearerToken.isBlank();
    }
}
