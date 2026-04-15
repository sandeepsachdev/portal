package com.portal.controller;

import com.portal.service.NewsService;
import com.portal.service.PetrolPriceService;
import com.portal.service.TrendsService;
import com.portal.service.WatchmodeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final NewsService newsService;
    private final WatchmodeService watchmodeService;
    private final PetrolPriceService petrolPriceService;
    private final TrendsService trendsService;

    public DashboardController(NewsService newsService,
                                WatchmodeService watchmodeService,
                                PetrolPriceService petrolPriceService,
                                TrendsService trendsService) {
        this.newsService       = newsService;
        this.watchmodeService  = watchmodeService;
        this.petrolPriceService = petrolPriceService;
        this.trendsService     = trendsService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("news",            newsService.getLatestNews());
        model.addAttribute("shows",           watchmodeService.getLatestNetflixShows());
        model.addAttribute("petrol",          petrolPriceService.getSydneyPetrolPrice());
        model.addAttribute("trends",          trendsService.getTopTrends());
        model.addAttribute("twitterEnabled",  trendsService.isTwitterConfigured());
        return "dashboard";
    }
}
