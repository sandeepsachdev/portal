package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.TrendItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches trending topics from the Bluesky public API.
 *
 * Endpoint: https://public.api.bsky.app/xrpc/app.bsky.unspecced.getTrendingTopics
 * No API key or authentication required.
 * Requests Australian region first; falls back to global if nothing is returned.
 */
@Service
public class TrendsService {

    private static final Logger log = LoggerFactory.getLogger(TrendsService.class);

    private static final String BASE_URL =
            "https://public.api.bsky.app/xrpc/app.bsky.unspecced.getTrendingTopics?limit=10";
    private static final String AU_TRENDS_URL  = BASE_URL + "&region=AU";
    private static final String ALL_TRENDS_URL = BASE_URL;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TrendsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** Returns up to 10 trending topics from Bluesky, preferring Australian trends. */
    public List<TrendItem> getTopTrends() {
        // Try global first (more stable); fall back to AU-specific if it returns results
        List<TrendItem> global = fetch(ALL_TRENDS_URL, "global");
        if (!global.isEmpty()) {
            List<TrendItem> au = fetch(AU_TRENDS_URL, "AU");
            return au.isEmpty() ? global : au;
        }
        return List.of();
    }

    private List<TrendItem> fetch(String url, String label) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "PortalDashboard/1.0");
            headers.set("Accept", "application/json");

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<TrendItem> items = parseTrends(response.getBody());
                if (!items.isEmpty()) {
                    log.info("Bluesky {} trends returned {} items, first: {}",
                            label, items.size(), items.get(0).getName());
                    return items;
                }
                log.info("Bluesky {} trends returned an empty list", label);
            }
        } catch (HttpStatusCodeException e) {
            log.warn("Bluesky {} trends failed: {} – {}", label, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Bluesky {} trends API failed: {}", label, e.getMessage());
        }
        return List.of();
    }

    private List<TrendItem> parseTrends(String json) throws Exception {
        List<TrendItem> items = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);

        JsonNode topics = root.path("topics");
        if (!topics.isArray() || topics.isEmpty()) return items;

        int rank = 1;
        for (JsonNode node : topics) {
            if (items.size() >= 10) break;

            // Prefer displayName, fall back to topic
            String name = node.path("displayName").asText("").trim();
            if (name.isBlank()) {
                name = node.path("topic").asText("").trim();
            }
            if (name.isBlank()) continue;

            // Use provided link, or build a Bluesky search URL.
            // The API returns relative paths (e.g. "/search?q=...") so
            // we must prepend the base URL to make them absolute.
            String link = node.path("link").asText("").trim();
            if (link.isBlank()) {
                link = "https://bsky.app/search?q=" +
                        URLEncoder.encode(name, StandardCharsets.UTF_8);
            } else if (link.startsWith("/")) {
                link = "https://bsky.app" + link;
            }

            // startedAt or description can serve as "volume" — use description if present
            String description = node.path("description").asText("").trim();

            items.add(new TrendItem(rank++, name, description, link));
        }
        return items;
    }
}
