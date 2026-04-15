package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.Show;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the top 5 trending Netflix titles (English) using the Watchmode API.
 *
 * Required env var: WATCHMODE_API_KEY
 * API docs: https://api.watchmode.com/
 */
@Service
public class WatchmodeService {

    private static final Logger log = LoggerFactory.getLogger(WatchmodeService.class);

    // Netflix source ID in the Watchmode catalogue
    private static final int NETFLIX_SOURCE_ID = 203;

    private static final String LIST_TITLES_URL =
            "https://api.watchmode.com/v1/list-titles/" +
            "?apiKey={apiKey}" +
            "&source_ids=" + NETFLIX_SOURCE_ID +
            "&sort_by=popularity_desc" +
            "&languages=en" +
            "&limit=5";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${watchmode.api.key:}")
    private String apiKey;

    public WatchmodeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns the top 5 trending Netflix titles in English, sorted by popularity.
     * Returns an empty list (with a warning logged) if the API key is not set.
     */
    public List<Show> getLatestNetflixShows() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("WATCHMODE_API_KEY is not set – Netflix shows quadrant will be empty");
            return List.of();
        }

        try {
            String url = LIST_TITLES_URL.replace("{apiKey}", apiKey);
            String response = restTemplate.getForObject(url, String.class);
            List<Show> shows = parseShows(response);
            log.info("Watchmode returned {} trending English Netflix titles", shows.size());
            return shows;
        } catch (Exception e) {
            log.error("Failed to fetch Watchmode trending titles: {}", e.getMessage());
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

            // list-titles returns year (int) rather than a full release date
            int year = node.path("year").asInt(0);
            if (year > 0) show.setReleaseDate(String.valueOf(year));

            String imdbId = node.path("imdb_id").asText("");
            if (!imdbId.isBlank()) show.setImdbId(imdbId);

            // poster is not included in list-titles; placeholder icon will show instead
            shows.add(show);

            if (shows.size() == 5) break;
        }
        return shows;
    }
}
