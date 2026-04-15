package com.portal.service;

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
 * Fetches today's top trending topics in Australia from Google Trends Daily RSS.
 * No API key required.
 */
@Service
public class TrendsService {

    private static final Logger log = LoggerFactory.getLogger(TrendsService.class);

    private static final String GOOGLE_TRENDS_RSS =
            "https://trends.google.com.au/trending/rss?geo=AU&hours=1";
    private static final String GOOGLE_TRENDS_RSS_LEGACY =
            "https://trends.google.com.au/trends/trendingsearches/daily/rss?geo=AU";

    private final RestTemplate restTemplate;

    public TrendsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** Returns today's top 10 trending topics in Australia. */
    public List<TrendItem> getTopTrends() {
        for (String rssUrl : new String[]{GOOGLE_TRENDS_RSS, GOOGLE_TRENDS_RSS_LEGACY}) {
            List<TrendItem> result = tryFetch(rssUrl);
            if (!result.isEmpty()) return result;
        }
        return List.of();
    }

    private List<TrendItem> tryFetch(String rssUrl) {
        try {
            HttpURLConnection conn = openConnection(rssUrl);

            // Follow up to 5 redirects manually to preserve the custom User-Agent
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
                    String searchUrl = "https://www.google.com/search?q=" +
                            java.net.URLEncoder.encode(title.trim(), java.nio.charset.StandardCharsets.UTF_8);
                    items.add(new TrendItem(rank++, title.trim(), "", searchUrl));
                }
            }
            log.info("Google Trends returned {} items for AU", items.size());
            return items;

        } catch (Exception e) {
            log.warn("Failed to fetch Google Trends RSS from {}: {}", rssUrl, e.getMessage());
            return List.of();
        }
    }

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(false);
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
}
