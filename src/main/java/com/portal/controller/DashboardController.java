package com.portal.controller;

import com.portal.service.NewsService;
import com.portal.service.TrendsService;
import com.portal.service.WatchmodeService;
import com.portal.service.WikipediaService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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
        model.addAttribute("news",           newsService.getLatestNews());
        model.addAttribute("shows",          watchmodeService.getLatestNetflixShows());
        model.addAttribute("netflixUsage",   watchmodeService.getApiUsage());
        model.addAttribute("wikiArticles",   wikipediaService.getTopArticles());
        model.addAttribute("trends",         trendsService.getTopTrends());
        return "dashboard";
    }
}
