package com.portal.model;

public class WikipediaArticle {
    private int rank;
    private String title;       // display title (spaces, not underscores)
    private String articleKey;  // raw key used in the URL
    private long views;
    private String url;

    public WikipediaArticle() {}

    public WikipediaArticle(int rank, String articleKey, long views) {
        this.rank       = rank;
        this.articleKey = articleKey;
        this.title      = articleKey.replace('_', ' ');
        this.views      = views;
        this.url        = "https://en.wikipedia.org/wiki/" + articleKey;
    }

    public String getFormattedViews() {
        if (views >= 1_000_000) return String.format("%.1fM", views / 1_000_000.0);
        if (views >= 1_000)     return String.format("%.0fK", views / 1_000.0);
        return String.valueOf(views);
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArticleKey() { return articleKey; }
    public void setArticleKey(String articleKey) { this.articleKey = articleKey; }

    public long getViews() { return views; }
    public void setViews(long views) { this.views = views; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
