package com.portal.model;

public class TrendItem {
    private int rank;
    private String name;
    private String tweetVolume;
    private String url;

    public TrendItem() {}

    public TrendItem(int rank, String name, String tweetVolume, String url) {
        this.rank = rank;
        this.name = name;
        this.tweetVolume = tweetVolume;
        this.url = url;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTweetVolume() { return tweetVolume; }
    public void setTweetVolume(String tweetVolume) { this.tweetVolume = tweetVolume; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
