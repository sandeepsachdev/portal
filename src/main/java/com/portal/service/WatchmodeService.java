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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private static final DateTimeFormatter ISO_DATE    = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter YYYYMMDD     = DateTimeFormatter.ofPattern("yyyyMMdd");

    // /v1/releases/ includes release_date per item; list-titles only has year
    private static final String RELEASES_URL =
            "https://api.watchmode.com/v1/releases/" +
            "?apiKey={apiKey}" +
            "&source_ids=" + NETFLIX_SOURCE_ID +
            "&start_date={startDate}";

    private static final String DETAILS_URL =
            "https://api.watchmode.com/v1/title/{id}/details/?apiKey={apiKey}";

    // Dedicated pool for parallel detail fetches (I/O-bound)
    private static final ExecutorService DETAILS_POOL = Executors.newFixedThreadPool(5);

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

        // Collect all items with their raw date for sorting
        record Entry(String rawDate, Show show) {}
        List<Entry> entries = new ArrayList<>();

        for (JsonNode node : releases) {
            Show show = new Show();
            show.setId(node.path("id").asInt(0));
            show.setTitle(node.path("title").asText("Unknown"));
            show.setType(node.path("type").asText(""));

            String rawDate = node.path("source_release_date").asText("");
            String releaseDate = parseReleaseDate(node);
            if (releaseDate != null) show.setReleaseDate(releaseDate);

            String imdbId = node.path("imdb_id").asText("");
            if (!imdbId.isBlank()) show.setImdbId(imdbId);

            String source = node.path("source_name").asText("");
            if (!source.isBlank()) show.setSource(source);

            String poster = node.path("poster_url").asText("");
            if (!poster.isBlank()) show.setPoster(poster);

            entries.add(new Entry(rawDate, show));
        }

        // Sort by source_release_date descending — YYYY-MM-DD strings sort correctly
        entries.sort(Comparator.comparing((Entry e) -> e.rawDate()).reversed());

        entries.stream().limit(MAX_RESULTS).map(Entry::show).forEach(shows::add);
        enrichWithRatings(shows);
        return shows;
    }

    /** Fetches user_rating from title details for each show in parallel. */
    private void enrichWithRatings(List<Show> shows) {
        List<CompletableFuture<Void>> futures = shows.stream().map(show ->
            CompletableFuture.runAsync(() -> {
                try {
                    String url = DETAILS_URL
                            .replace("{id}", String.valueOf(show.getId()))
                            .replace("{apiKey}", apiKey);
                    String body = restTemplate.getForObject(url, String.class);
                    if (body != null) {
                        JsonNode root = objectMapper.readTree(body);
                        double rating = root.path("user_rating").asDouble(0);
                        if (rating > 0) show.setRating(String.format("%.1f", rating));
                    }
                } catch (Exception e) {
                    log.debug("Rating fetch failed for show {}: {}", show.getId(), e.getMessage());
                }
            }, DETAILS_POOL)
        ).toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Some rating fetches timed out or failed");
        }
    }

    /**
     * Parses source_release_date from a Watchmode release node and returns a
     * display-ready string (e.g. "15 Apr 2026") so the template can use
     * ${show.releaseDate} directly without any further formatting.
     */
    private String parseReleaseDate(JsonNode node) {
        String s = node.path("source_release_date").asText("").trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                return LocalDate.parse(s, ISO_DATE).format(DISPLAY_DATE); // "15 Apr 2026"
            } catch (Exception e) {
                return s;
            }
        }
        if (s.matches("\\d{8}")) {
            try {
                return LocalDate.parse(s, YYYYMMDD).format(DISPLAY_DATE);
            } catch (Exception e) {
                return s;
            }
        }
        int year = node.path("year").asInt(0);
        return year > 0 ? String.valueOf(year) : null;
    }

    /** Returns the cached usage label populated during the last releases fetch, or null. */
    public String getApiUsage() {
        return cachedUsageLabel;
    }

    /**
     * Reads rate-limit headers from the API response and updates {@link #cachedUsageLabel}.
     * Logs all response headers at INFO so we can identify the correct header names.
     */
    private void cacheUsageFromHeaders(ResponseEntity<?> resp) {
        resp.getHeaders().forEach((name, values) ->
                log.debug("Watchmode response header: {} = {}", name, values));

        String used  = firstHeader(resp, "x-account-quota-used");
        String limit = firstHeader(resp, "x-account-quota");

        if (used != null && limit != null) {
            cachedUsageLabel = String.format("%,d / %,d calls this month",
                    Long.parseLong(used), Long.parseLong(limit));
        } else {
            log.info("Watchmode quota headers not found in response");
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
