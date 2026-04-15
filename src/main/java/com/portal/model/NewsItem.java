package com.portal.model;

public class NewsItem {
    private String title;
    private String description;
    private String url;
    private String publishedDate;
    private String imageUrl;

    public NewsItem() {}

    public NewsItem(String title, String description, String url,
                    String publishedDate, String imageUrl) {
        this.title = title;
        this.description = description;
        this.url = url;
        this.publishedDate = publishedDate;
        this.imageUrl = imageUrl;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getPublishedDate() { return publishedDate; }
    public void setPublishedDate(String publishedDate) { this.publishedDate = publishedDate; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}
