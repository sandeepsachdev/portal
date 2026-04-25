package com.portal.service;

import com.portal.model.NewsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


import org.springframework.web.util.HtmlUtils;

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
            "https://www.smh.com.au/rss/feed.xml";

    private static final int MAX_ITEMS = 5;

    // ABC News RSS uses RFC 822 dates: "Mon, 15 Apr 2024 02:30:00 +0000"
    private static final DateTimeFormatter RFC_822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.ENGLISH);
    private static final ZoneId SYDNEY_TZ = ZoneId.of("Australia/Sydney");

    private final RestTemplate restTemplate;

    public NewsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Returns the latest {@value #MAX_ITEMS} Australian news stories.
     * Returns an empty list on any error (error is logged).
     */
    @Cacheable("news")
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

    public static String cleanString(String input) {
        if (input == null) return null;

        return input
                // Keep letters, digits, and basic punctuation
                .replaceAll("[^a-zA-Z0-9.,!?;:'\"()\\-]", " ")
                // Collapse multiple spaces
                .replaceAll("\\s+", " ")
                .trim();
    }
    private List<NewsItem> parseRss(String xml) {
        List<NewsItem> items = new ArrayList<>();

        // Split on <item> boundaries
        String[] rawItems = xml.split("<item>");
        for (int i = 1; i < rawItems.length && items.size() < MAX_ITEMS; i++) {
            if (rawItems[i].toLowerCase().contains("sport")) {
                continue;
            }
            String block = rawItems[i];

            String title       = extractTag(block, "title");
            String description = extractTag(block, "description");
            String link        = extractTag(block, "link");
            String pubDate     = extractTag(block, "pubDate");
            String imageUrl    = extractMediaUrl(block);

            if (title == null || title.isBlank()) continue;

            // Strip leading/trailing CDATA wrappers and HTML entities
            title       = cleanCdata(title);
            title       = cleanString(title);
            description = cleanCdata(description);
            description = stripHtml(description);
            description = cleanString(description);

            // Truncate description for display
            if (description != null && description.length() > 180) {
                description = description.substring(0, 177) + "…";
            }

            // Parse RFC 822 date and reformat with time in Sydney timezone
            if (pubDate != null && !pubDate.isBlank()) {
                pubDate = formatPubDate(pubDate.trim());
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

    private String formatPubDate(String raw) {
        try {
            ZonedDateTime utc = ZonedDateTime.parse(raw, RFC_822);
            return utc.withZoneSameInstant(SYDNEY_TZ).format(DISPLAY_FMT);
        } catch (Exception e) {
            // Fallback: strip day-of-week prefix if parse fails
            return raw.length() > 16 ? raw.substring(5, 16).trim() : raw;
        }
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
