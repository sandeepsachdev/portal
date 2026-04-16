package com.portal.model;

public class Show {
    private Integer id;
    private String title;
    private String type;
    private String releaseDate;
    private String poster;
    private Integer episodeNumber;
    private Integer seasonNumber;
    private String imdbId;
    private String source;   // populated if Watchmode returns it

    public Show() {}

    public Show(Integer id, String title, String type, String releaseDate,
                String poster, Integer episodeNumber, Integer seasonNumber) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.releaseDate = releaseDate;
        this.poster = poster;
        this.episodeNumber = episodeNumber;
        this.seasonNumber = seasonNumber;
    }

    public String getTypeLabel() {
        if (type == null) return "Unknown";
        return switch (type) {
            case "tv_series"  -> "TV Series";
            case "movie"      -> "Movie";
            case "tv_movie"   -> "TV Movie";
            case "tv_special" -> "TV Special";
            default           -> type;
        };
    }

    public String getFormattedReleaseDate() {
        if (releaseDate == null || releaseDate.length() < 8) return releaseDate;
        // Watchmode returns dates as YYYYMMDD
        try {
            String year  = releaseDate.substring(0, 4);
            String month = releaseDate.substring(4, 6);
            String day   = releaseDate.substring(6, 8);
            String[] months = {"Jan","Feb","Mar","Apr","May","Jun",
                               "Jul","Aug","Sep","Oct","Nov","Dec"};
            int m = Integer.parseInt(month) - 1;
            String monthName = (m >= 0 && m < 12) ? months[m] : month;
            return day + " " + monthName + " " + year;
        } catch (Exception e) {
            return releaseDate;
        }
    }

    /** IMDB URL: direct title page when imdbId is known, search URL otherwise. */
    public String getImdbUrl() {
        if (imdbId != null && !imdbId.isBlank()) {
            return "https://www.imdb.com/title/" + imdbId + "/";
        }
        try {
            String query = java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8);
            return "https://www.imdb.com/find?q=" + query;
        } catch (Exception e) {
            return "https://www.imdb.com/";
        }
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getReleaseDate() { return releaseDate; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }

    public String getPoster() { return poster; }
    public void setPoster(String poster) { this.poster = poster; }

    public Integer getEpisodeNumber() { return episodeNumber; }
    public void setEpisodeNumber(Integer episodeNumber) { this.episodeNumber = episodeNumber; }

    public Integer getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(Integer seasonNumber) { this.seasonNumber = seasonNumber; }

    public String getImdbId() { return imdbId; }
    public void setImdbId(String imdbId) { this.imdbId = imdbId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
