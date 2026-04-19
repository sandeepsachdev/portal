package com.portal.controller;

import com.portal.service.NewsService;
import com.portal.service.TrendsService;
import com.portal.service.WatchmodeService;
import com.portal.service.WikipediaService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Controller
public class DashboardController {

    private final NewsService newsService;
    private final WatchmodeService watchmodeService;
    private final WikipediaService wikipediaService;
    private final TrendsService trendsService;

    public DashboardController(NewsService newsService,
                                WatchmodeService watchmodeService,
                                WikipediaService wikipediaService,
                                TrendsService trendsService) {
        this.newsService      = newsService;
        this.watchmodeService = watchmodeService;
        this.wikipediaService = wikipediaService;
        this.trendsService    = trendsService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        // Run all external API calls in parallel. Results are collected via typed futures
        // and added to the model only after completion — Model is not thread-safe so we
        // must never call addAttribute() from concurrent threads.
        CompletableFuture<Object> newsFuture    = CompletableFuture.supplyAsync(() -> newsService.getLatestNews());
        CompletableFuture<Object> showsFuture   = CompletableFuture.supplyAsync(() -> watchmodeService.getLatestNetflixShows());
        CompletableFuture<Object> wikiFuture    = CompletableFuture.supplyAsync(() -> wikipediaService.getTopArticles());
        CompletableFuture<Object> trendsFuture  = CompletableFuture.supplyAsync(() -> trendsService.getTopTrends());

        try {
            CompletableFuture.allOf(newsFuture, showsFuture, wikiFuture, trendsFuture)
                             .get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // Partial results are fine — each service already returns an empty list on error
        }

        model.addAttribute("news",         newsFuture.getNow(List.of()));
        Object shows = showsFuture.getNow(List.of());
        model.addAttribute("shows",        shows);
        model.addAttribute("netflixUsage", watchmodeService.getApiUsage());
        model.addAttribute("wikiArticles", wikiFuture.getNow(List.of()));
        model.addAttribute("trends",       trendsFuture.getNow(List.of()));

        return "dashboard";
    }
}
