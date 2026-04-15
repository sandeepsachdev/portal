package com.portal.model;

public class PetrolPriceData {

    public enum Trend { RISING, FALLING, STABLE, UNKNOWN }

    private Double currentPrice;
    private Double previousPrice;
    private Trend trend;
    private String fuelType;
    private String lastUpdated;
    private String errorMessage;

    public PetrolPriceData() {}

    public static PetrolPriceData error(String message) {
        PetrolPriceData d = new PetrolPriceData();
        d.errorMessage = message;
        d.trend = Trend.UNKNOWN;
        return d;
    }

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isBlank();
    }

    public String getTrendIcon() {
        if (trend == null) return "—";
        return switch (trend) {
            case RISING  -> "↑";
            case FALLING -> "↓";
            case STABLE  -> "→";
            default      -> "—";
        };
    }

    public String getTrendClass() {
        if (trend == null) return "text-secondary";
        return switch (trend) {
            case RISING  -> "text-danger";
            case FALLING -> "text-success";
            case STABLE  -> "text-warning";
            default      -> "text-secondary";
        };
    }

    public String getTrendLabel() {
        if (trend == null) return "Unknown";
        return switch (trend) {
            case RISING  -> "Rising";
            case FALLING -> "Falling";
            case STABLE  -> "Stable";
            default      -> "Unknown";
        };
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public Double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(Double currentPrice) { this.currentPrice = currentPrice; }

    public Double getPreviousPrice() { return previousPrice; }
    public void setPreviousPrice(Double previousPrice) { this.previousPrice = previousPrice; }

    public Trend getTrend() { return trend; }
    public void setTrend(Trend trend) { this.trend = trend; }

    public String getFuelType() { return fuelType; }
    public void setFuelType(String fuelType) { this.fuelType = fuelType; }

    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
