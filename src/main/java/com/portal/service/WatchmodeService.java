package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.Show;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches up to 25 most recently released English Netflix titles using the Watchmode API.
 *
 * Required env var: WATCHMODE_API_KEY
 * API docs: https://api.watchmode.com/
 */
@Service
public class WatchmodeService {

    private static final Logger log = LoggerFactory.getLogger(WatchmodeService.class);

    private static final int NETFLIX_SOURCE_ID = 203;
    private static final int LOOKBACK_DAYS = 5;

    private static final int MAX_RESULTS = 25;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    // /v1/releases/ includes release_date per item; list-titles only has year
    private static final String RELEASES_URL =
            "https://api.watchmode.com/v1/releases/" +
            "?apiKey={apiKey}" +
            "&source_ids=" + NETFLIX_SOURCE_ID +
            "&start_date={startDate}";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${watchmode.api.key:}")
    private String apiKey;

    /** Cached after the most recent releases fetch; null until first successful call. */
    private volatile String cachedUsageLabel = null;

    public WatchmodeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns up to {@value MAX_RESULTS} English Netflix titles released in the last
     * {@value LOOKBACK_DAYS} days, sorted by release date descending.
     */
    public List<Show> getLatestNetflixShows() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("WATCHMODE_API_KEY is not set – Netflix shows quadrant will be empty");
            return List.of();
        }

        try {
            String startDate = LocalDate.now(ZoneOffset.UTC)
                    .minusDays(LOOKBACK_DAYS)
                    .format(YYYYMMDD);

            String url = RELEASES_URL
                    .replace("{apiKey}", apiKey)
                    .replace("{startDate}", startDate);

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
            cacheUsageFromHeaders(resp);
            List<Show> shows = parseShows(resp.getBody());
            log.info("Watchmode returned {} Netflix releases since {}", shows.size(), startDate);
            return shows;
        } catch (Exception e) {
            log.error("Failed to fetch Watchmode titles: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Show> parseShows(String json) throws Exception {
        List<Show> shows = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);

        // /v1/releases/ returns a "releases" array; log first item to confirm field names
        JsonNode releases = root.path("releases");
        if (!releases.isArray() || releases.isEmpty()) {
            log.info("Watchmode releases response: {}", json.length() > 300 ? json.substring(0, 300) : json);
            return shows;
        }
        log.debug("First release node: {}", releases.get(0));

        for (JsonNode node : releases) {
            if (shows.size() >= MAX_RESULTS) break;

            Show show = new Show();
            show.setId(node.path("id").asInt(0));
            show.setTitle(node.path("title").asText("Unknown"));
            show.setType(node.path("type").asText(""));

            String releaseDate = parseReleaseDate(node);
            if (releaseDate != null) show.setReleaseDate(releaseDate);
            log.info("Show: {}, source_release_date raw: {}, parsed: {}",
                    show.getTitle(),
                    node.path("source_release_date").asText("(absent)"),
                    releaseDate);

            String imdbId = node.path("imdb_id").asText("");
            if (!imdbId.isBlank()) show.setImdbId(imdbId);

            String source = node.path("source_name").asText("");
            if (!source.isBlank()) show.setSource(source);

            String poster = node.path("poster_url").asText("");
            if (!poster.isBlank()) show.setPoster(poster);

            shows.add(show);
        }
        return shows;
    }

    /**
     * Extracts a release date from a Watchmode release node as a YYYYMMDD string,
     * which {@link com.portal.model.Show#getFormattedReleaseDate()} renders as "11 Apr 2026".
     *
     * The confirmed field is source_release_date in "YYYY-MM-DD" format.
     */
    private String parseReleaseDate(JsonNode node) {
        String s = node.path("source_release_date").asText("").trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return s.replace("-", "");   // "2026-04-11" → "20260411"
        }
        if (s.matches("\\d{8}")) {
            return s;                    // already YYYYMMDD
        }
        // Fall back to year-only integer
        int year = node.path("year").asInt(0);
        return year > 0 ? String.valueOf(year) : null;
    }

    /** Returns the cached usage label populated during the last releases fetch, or null. */
    public String getApiUsage() {
        return cachedUsageLabel;
    }

    /**
     * Reads rate-limit headers from the API response and updates {@link #cachedUsageLabel}.
     * Logs all X- headers at DEBUG so we can identify the correct header names.
     */
    private void cacheUsageFromHeaders(ResponseEntity<?> resp) {
        resp.getHeaders().forEach((name, values) -> {
            if (name.toLowerCase().startsWith("x-")) {
                log.debug("Watchmode header: {} = {}", name, values);
            }
        });

        // Try common rate-limit header patterns
        String used      = firstHeader(resp, "X-RateLimit-Used",      "X-Rate-Limit-Used");
        String remaining = firstHeader(resp, "X-RateLimit-Remaining", "X-Rate-Limit-Remaining");
        String limit     = firstHeader(resp, "X-RateLimit-Limit",     "X-Rate-Limit-Limit");

        if (used != null && limit != null) {
            cachedUsageLabel = String.format("%,d / %,d calls this month",
                    Long.parseLong(used), Long.parseLong(limit));
        } else if (remaining != null && limit != null) {
            long lim  = Long.parseLong(limit);
            long rem  = Long.parseLong(remaining);
            cachedUsageLabel = String.format("%,d / %,d calls this month", lim - rem, lim);
        } else {
            log.debug("No recognised rate-limit headers found in Watchmode response");
        }
    }

    private String firstHeader(ResponseEntity<?> resp, String... names) {
        for (String name : names) {
            String val = resp.getHeaders().getFirst(name);
            if (val != null && !val.isBlank()) return val.trim();
        }
        return null;
    }
}
