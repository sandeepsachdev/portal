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
 * Fetches the 5 most recently released Netflix titles from English-speaking
 * countries using the Watchmode API.
 *
 * Required env var: WATCHMODE_API_KEY
 * API docs: https://api.watchmode.com/
 */
@Service
public class WatchmodeService {

    private static final Logger log = LoggerFactory.getLogger(WatchmodeService.class);

    // Netflix source ID in the Watchmode catalogue
    private static final int NETFLIX_SOURCE_ID = 203;

    private static final String RELEASES_URL =
            "https://api.watchmode.com/v1/releases/" +
            "?apiKey={apiKey}" +
            "&source_ids=" + NETFLIX_SOURCE_ID;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${watchmode.api.key:}")
    private String apiKey;

    public WatchmodeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns the 5 most recently released Netflix titles in English.
     * Returns an empty list (with a warning logged) if the API key is not set.
     */
    public List<Show> getLatestNetflixShows() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("WATCHMODE_API_KEY is not set – Netflix shows quadrant will be empty");
            return List.of();
        }

        try {
            String url = RELEASES_URL.replace("{apiKey}", apiKey);
            String response = restTemplate.getForObject(url, String.class);
            List<Show> shows = parseShows(response);
            log.info("Watchmode returned {} English Netflix titles", shows.size());
            return shows;
        } catch (Exception e) {
            log.error("Failed to fetch Watchmode releases: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Show> parseShows(String json) throws Exception {
        List<Show> shows = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode releases = root.path("releases");

        if (!releases.isArray()) return shows;

        for (JsonNode node : releases) {
            String lang = node.path("original_language").asText("");
            // Watchmode releases endpoint often omits original_language.
            // Only exclude when the field is explicitly set to a non-English value.
            if (!lang.isBlank() && !"en".equalsIgnoreCase(lang)) continue;

            Show show = new Show();
            show.setId(node.path("id").asInt(0));
            show.setTitle(node.path("title").asText("Unknown"));
            show.setType(node.path("type").asText(""));
            show.setReleaseDate(node.path("release_date").asText(""));
            show.setPoster(node.path("poster").asText(null));

            JsonNode ep = node.path("episode_number");
            if (!ep.isNull() && ep.isNumber()) show.setEpisodeNumber(ep.asInt());

            JsonNode sn = node.path("season_number");
            if (!sn.isNull() && sn.isNumber()) show.setSeasonNumber(sn.asInt());

            shows.add(show);

            if (shows.size() == 5) break;
        }
        return shows;
    }
}
