package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.Show;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the 5 most recently released English Netflix titles using the Watchmode API.
 *
 * Required env var: WATCHMODE_API_KEY
 * API docs: https://api.watchmode.com/
 */
@Service
public class WatchmodeService {

    private static final Logger log = LoggerFactory.getLogger(WatchmodeService.class);

    private static final int NETFLIX_SOURCE_ID = 203;
    private static final int LOOKBACK_DAYS = 5;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    // Fetch up to 10 so there's headroom after any language filtering, return first 5
    private static final String LIST_TITLES_URL =
            "https://api.watchmode.com/v1/list-titles/" +
            "?apiKey={apiKey}" +
            "&source_ids=" + NETFLIX_SOURCE_ID +
            "&sort_by=release_date_desc" +
            "&languages=en" +
            "&limit=10" +
            "&release_date_start={releaseDateStart}";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${watchmode.api.key:}")
    private String apiKey;

    public WatchmodeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns up to 5 English Netflix titles released in the last {@value LOOKBACK_DAYS} days,
     * sorted by release date descending.
     */
    public List<Show> getLatestNetflixShows() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("WATCHMODE_API_KEY is not set – Netflix shows quadrant will be empty");
            return List.of();
        }

        try {
            // Watchmode expects YYYYMMDD integers for date filter params, not Unix timestamps
            String startDate = LocalDate.now(ZoneOffset.UTC)
                    .minusDays(LOOKBACK_DAYS)
                    .format(YYYYMMDD);

            String url = LIST_TITLES_URL
                    .replace("{apiKey}", apiKey)
                    .replace("{releaseDateStart}", startDate);

            String response = restTemplate.getForObject(url, String.class);
            List<Show> shows = parseShows(response);
            log.info("Watchmode returned {} Netflix titles released in the last {} days",
                    shows.size(), LOOKBACK_DAYS);
            return shows;
        } catch (Exception e) {
            log.error("Failed to fetch Watchmode titles: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Show> parseShows(String json) throws Exception {
        List<Show> shows = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode titles = root.path("titles");

        if (!titles.isArray()) return shows;

        for (JsonNode node : titles) {
            Show show = new Show();
            show.setId(node.path("id").asInt(0));
            show.setTitle(node.path("title").asText("Unknown"));
            show.setType(node.path("type").asText(""));

            // Parse release date — Watchmode may return it as a YYYYMMDD integer,
            // a Unix timestamp, a "YYYY-MM-DD" string, or only a year integer.
            String releaseDate = parseReleaseDate(node);
            if (releaseDate != null) show.setReleaseDate(releaseDate);
            log.debug("Title: {}, raw release_date: {}, year: {}, parsed: {}",
                    show.getTitle(),
                    node.path("release_date").asText("(absent)"),
                    node.path("year").asText("(absent)"),
                    releaseDate);

            String imdbId = node.path("imdb_id").asText("");
            if (!imdbId.isBlank()) show.setImdbId(imdbId);

            shows.add(show);
            if (shows.size() == 5) break;
        }
        return shows;
    }

    /**
     * Extracts a release date from a Watchmode title node in YYYYMMDD format,
     * ready for {@link com.portal.model.Show#getFormattedReleaseDate()}.
     *
     * Handles four formats the API may return:
     *  - YYYYMMDD integer (e.g. 20260411) — used directly
     *  - Unix timestamp integer (e.g. 1744934400) — converted via epoch days
     *  - "YYYY-MM-DD" string — dashes stripped
     *  - Year-only integer (e.g. 2026) — stored as-is (displays as "2026")
     */
    private String parseReleaseDate(JsonNode node) {
        JsonNode rdNode = node.path("release_date");
        if (!rdNode.isMissingNode() && !rdNode.isNull()) {
            if (rdNode.isNumber()) {
                long rd = rdNode.asLong(0);
                if (rd >= 10_000_000) {          // looks like YYYYMMDD (≥ 10000101)
                    return String.valueOf(rd);
                } else if (rd > 0) {             // Unix timestamp in seconds
                    return LocalDate.ofEpochDay(rd / 86_400L)
                            .format(YYYYMMDD);
                }
            } else {
                String s = rdNode.asText("").trim();
                if (s.matches("\\d{8}")) {
                    return s;                    // already YYYYMMDD
                } else if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return s.replace("-", "");   // "YYYY-MM-DD" → "YYYYMMDD"
                }
            }
        }
        // Fall back to year-only
        int year = node.path("year").asInt(0);
        return year > 0 ? String.valueOf(year) : null;
    }
}
