package com.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.model.PetrolPriceData;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Provides Sydney unleaded petrol (ULP 91) price data and rising/falling trend.
 *
 * Strategy:
 *  1. If NSW_FUEL_API_KEY is set → NSW Government FuelCheck REST API
 *  2. Otherwise → scrape the publicly visible motormouth.com.au price table
 *
 * Trend is computed by comparing today's average price against yesterday's.
 */
@Service
public class PetrolPriceService {

    private static final Logger log = LoggerFactory.getLogger(PetrolPriceService.class);

    // NSW FuelCheck – requires API key from api.nsw.gov.au developer portal
    private static final String NSW_FUEL_URL =
            "https://api.onegov.nsw.gov.au/FuelCheckRefApp/api/v1/fuel/prices/bylocation" +
            "?fueltype=U91&latitude=-33.8688&longitude=151.2093&radius=5&brandid=&stationname=";

    // Public price history page on motormouth – no auth needed
    private static final String MOTORMOUTH_URL =
            "https://www.motormouth.com.au/petrolprices/sydney-petrol-prices";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${nsw.fuel.api.key:}")
    private String nswFuelApiKey;

    public PetrolPriceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns a {@link PetrolPriceData} for Sydney unleaded petrol.
     * Never throws – errors are captured in {@link PetrolPriceData#getErrorMessage()}.
     */
    public PetrolPriceData getSydneyPetrolPrice() {
        if (nswFuelApiKey != null && !nswFuelApiKey.isBlank()) {
            try {
                return fetchFromNswApi();
            } catch (Exception e) {
                log.warn("NSW FuelCheck API failed ({}), trying Motormouth fallback", e.getMessage());
            }
        }
        try {
            return scrapeMotormouth();
        } catch (Exception e) {
            log.error("Motormouth scrape failed: {}", e.getMessage());
            return PetrolPriceData.error("Unable to retrieve petrol prices at this time.");
        }
    }

    // ── NSW FuelCheck API ────────────────────────────────────────────────────

    private PetrolPriceData fetchFromNswApi() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("apikey", nswFuelApiKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                NSW_FUEL_URL, HttpMethod.GET, entity, String.class);

        return parseNswApiResponse(response.getBody());
    }

    private PetrolPriceData parseNswApiResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode prices = root.path("prices");
        if (!prices.isArray() || prices.isEmpty()) {
            return PetrolPriceData.error("No price data returned from NSW FuelCheck API.");
        }

        double total = 0;
        int count = 0;
        for (JsonNode item : prices) {
            double price = item.path("price").asDouble(0);
            if (price > 0) { total += price; count++; }
        }

        if (count == 0) return PetrolPriceData.error("No valid price entries found.");

        double avg = total / count;

        PetrolPriceData data = new PetrolPriceData();
        data.setCurrentPrice(Math.round(avg * 10.0) / 10.0);
        data.setFuelType("ULP 91");
        data.setLastUpdated(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.setTrend(PetrolPriceData.Trend.UNKNOWN); // trend needs historical data
        return data;
    }

    // ── Motormouth scraper (public fallback) ─────────────────────────────────

    private PetrolPriceData scrapeMotormouth() throws Exception {
        Document doc = Jsoup.connect(MOTORMOUTH_URL)
                .userAgent("Mozilla/5.0 (compatible; PortalBot/1.0)")
                .timeout(10_000)
                .get();

        // Motormouth renders a table with date / average-price rows
        // We grab the two most recent rows to compute a trend
        Elements rows = doc.select("table tr");

        Double latestPrice = null;
        Double prevPrice   = null;
        String latestDate  = null;

        int dataRows = 0;
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() < 2) continue;

            String dateText  = cells.get(0).text().trim();
            String priceText = cells.get(1).text().replaceAll("[^0-9.]", "").trim();

            if (priceText.isEmpty()) continue;
            try {
                double price = Double.parseDouble(priceText);
                if (dataRows == 0) { latestPrice = price; latestDate = dateText; }
                if (dataRows == 1) { prevPrice = price; }
                dataRows++;
                if (dataRows == 2) break;
            } catch (NumberFormatException ignored) {}
        }

        if (latestPrice == null) {
            return PetrolPriceData.error("Could not parse petrol price data.");
        }

        PetrolPriceData data = new PetrolPriceData();
        data.setCurrentPrice(latestPrice);
        data.setPreviousPrice(prevPrice);
        data.setFuelType("ULP 91");
        data.setLastUpdated(latestDate != null ? latestDate
                : LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));

        if (prevPrice != null) {
            double diff = latestPrice - prevPrice;
            if (Math.abs(diff) < 0.2) {
                data.setTrend(PetrolPriceData.Trend.STABLE);
            } else {
                data.setTrend(diff > 0 ? PetrolPriceData.Trend.RISING : PetrolPriceData.Trend.FALLING);
            }
        } else {
            data.setTrend(PetrolPriceData.Trend.UNKNOWN);
        }

        return data;
    }
}
