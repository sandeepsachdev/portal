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
        // Run all external API calls in parallel so a slow source doesn't block the rest
        CompletableFuture<?>[] futures = {
            CompletableFuture.supplyAsync(newsService::getLatestNews)
                .thenAccept(v -> model.addAttribute("news", v)),
            CompletableFuture.supplyAsync(watchmodeService::getLatestNetflixShows)
                .thenAccept(v -> model.addAttribute("shows", v)),
            CompletableFuture.supplyAsync(watchmodeService::getApiUsage)
                .thenAccept(v -> model.addAttribute("netflixUsage", v)),
            CompletableFuture.supplyAsync(wikipediaService::getTopArticles)
                .thenAccept(v -> model.addAttribute("wikiArticles", v)),
            CompletableFuture.supplyAsync(trendsService::getTopTrends)
                .thenAccept(v -> model.addAttribute("trends", v)),
        };

        // Wait for all, but no longer than 15 s total
        try {
            CompletableFuture.allOf(futures).get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // Partial results are fine — each service already returns an empty list on error
        }

        // Ensure model keys always exist so Thymeleaf th:if / th:each don't throw
        model.asMap().putIfAbsent("news",         List.of());
        model.asMap().putIfAbsent("shows",        List.of());
        model.asMap().putIfAbsent("netflixUsage", null);
        model.asMap().putIfAbsent("wikiArticles", List.of());
        model.asMap().putIfAbsent("trends",       List.of());

        return "dashboard";
    }
}
