package com.portal.service;

import com.portal.model.NewsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the latest Australian news from the ABC News RSS feed.
 *
 * Feed URL: https://www.abc.net.au/news/feed/51120/rss.xml
 * No API key required – it is a publicly available RSS feed.
 */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private static final String ABC_NEWS_RSS =
            "https://www.abc.net.au/news/feed/51120/rss.xml";

    private static final int MAX_ITEMS = 6;

    private final RestTemplate restTemplate;

    public NewsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Returns the latest {@value #MAX_ITEMS} Australian news stories.
     * Returns an empty list on any error (error is logged).
     */
    public List<NewsItem> getLatestNews() {
        try {
            String xml = restTemplate.getForObject(ABC_NEWS_RSS, String.class);
            if (xml == null) return List.of();
            return parseRss(xml);
        } catch (Exception e) {
            log.error("Failed to fetch ABC News RSS: {}", e.getMessage());
            return List.of();
        }
    }

    private List<NewsItem> parseRss(String xml) {
        List<NewsItem> items = new ArrayList<>();

        // Split on <item> boundaries
        String[] rawItems = xml.split("<item>");
        for (int i = 1; i < rawItems.length && items.size() < MAX_ITEMS; i++) {
            String block = rawItems[i];

            String title       = extractTag(block, "title");
            String description = extractTag(block, "description");
            String link        = extractTag(block, "link");
            String pubDate     = extractTag(block, "pubDate");
            String imageUrl    = extractMediaUrl(block);

            if (title == null || title.isBlank()) continue;

            // Strip leading/trailing CDATA wrappers and HTML entities
            title       = cleanCdata(title);
            description = cleanCdata(description);
            description = stripHtml(description);

            // Truncate description for display
            if (description != null && description.length() > 180) {
                description = description.substring(0, 177) + "…";
            }

            // Format date: keep only the first part ("Mon, 15 Apr 2024 …")
            if (pubDate != null && pubDate.length() > 16) {
                pubDate = pubDate.substring(5, 16).trim(); // "15 Apr 2024"
            }

            items.add(new NewsItem(title, description, link, pubDate, imageUrl));
        }
        return items;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractTag(String xml, String tag) {
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        int end   = xml.indexOf(close);
        if (start < 0 || end < 0) return null;
        return xml.substring(start + open.length(), end).trim();
    }

    /** Extracts the url attribute from a <media:content> or <media:thumbnail> tag. */
    private String extractMediaUrl(String block) {
        String[] candidates = { "media:content", "media:thumbnail" };
        for (String tag : candidates) {
            String open = "<" + tag + " ";
            int tagStart = block.indexOf(open);
            if (tagStart < 0) continue;
            int tagEnd = block.indexOf(">", tagStart);
            if (tagEnd < 0) continue;
            String attrs = block.substring(tagStart, tagEnd);

            // Look for url="..." attribute
            int urlIdx = attrs.indexOf("url=\"");
            if (urlIdx < 0) continue;
            int urlStart = urlIdx + 5;
            int urlEnd   = attrs.indexOf("\"", urlStart);
            if (urlEnd < 0) continue;
            return attrs.substring(urlStart, urlEnd);
        }
        return null;
    }

    private String cleanCdata(String s) {
        if (s == null) return null;
        return s.replaceAll("(?s)<!\\[CDATA\\[|]]>", "").trim();
    }

    private String stripHtml(String s) {
        if (s == null) return null;
        return s.replaceAll("<[^>]+>", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#\\d+;", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}
