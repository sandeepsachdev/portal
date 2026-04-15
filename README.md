# Portal Dashboard

A Spring Boot web application displaying a 4-quadrant live dashboard, containerised with Docker for deployment on [Render](https://render.com).

## Quadrants

| Position | Content | Source |
|---|---|---|
| Top-left | Latest Australian news | ABC News RSS (free) |
| Top-right | New on Netflix (English) | Watchmode API |
| Bottom-left | Trending on Wikipedia | Wikimedia REST API (free) |
| Bottom-right | Trending in Australia | Twitter / X API → Google Trends Daily fallback |

## Tech Stack

- **Java 17** / **Spring Boot 3.2**
- **Thymeleaf** — server-side templating
- **Bootstrap 5** + **Bootstrap Icons** — UI
- **ROME** — RSS feed parsing
- **Jsoup** — HTML scraping fallback
- **Maven** — build tool
- **Docker** — multi-stage image (builder + slim JRE runtime)

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `WATCHMODE_API_KEY` | Yes | API key from [watchmode.com](https://api.watchmode.com/) |
| `TWITTER_BEARER_TOKEN` | No | Twitter/X Bearer Token for trends (falls back to Google Trends Daily if not set) |
| `PORT` | No | HTTP port — Render injects this automatically (defaults to `8080`) |

> **Security note:** API keys are never stored in this repository. Pass them as environment variables at runtime.

## Running Locally

```bash
WATCHMODE_API_KEY=your_key_here mvn spring-boot:run
```

Then open `http://localhost:8080`.

## Docker

```bash
# Build
docker build -t portal .

# Run
docker run -p 8080:8080 \
  -e WATCHMODE_API_KEY=your_key_here \
  portal
```

## Deploying to Render

1. Create a new **Web Service** in Render.
2. Select **Docker** as the environment.
3. Point it at this repository and branch.
4. Add the environment variables listed above under **Environment**.
5. Render injects `PORT` automatically — no extra config needed.

The dashboard auto-refreshes every 5 minutes in the browser.

---

## Prompts Used to Build This App

This project was built entirely through conversational prompts with [Claude Code](https://claude.ai/code). The prompts are reproduced below in order.

---

### Prompt 1 — Initial project creation

> Create a new Spring Boot project that will be deployed to Render as a Docker project so create a Dockerfile for me. The app will have a web user interface. The main page will show 4 quadrants. The top right quadrant should use the Watchmode API https://api.watchmode.com/ to show the last 5 released shows on Netflix which are from English speaking countries. The bottom right quadrant will show today's top trends from Twitter. The bottom left quadrant will show if petrol prices are rising or falling in Sydney Australia. And the top left quadrant will show the latest news in Australia. Use the Watchmode API key but do not store it into git. It will be passed as an environment variable to the app.

---

### Prompt 2 — Bug fixes

> I have set the WATCHMODE_API_KEY but it does not seem to be used. The Twitter trends are not showing.

*Root causes identified and fixed:*
- The Watchmode releases endpoint does not include `original_language` per item, so the strict equality filter was silently dropping every show.
- The Google Trends RSS fallback was failing because `RestTemplate` stripped the custom `User-Agent` header when following redirects. Replaced with a raw `HttpURLConnection` and switched to ROME for robust RSS parsing.

---

### Prompt 3 — Feature changes

> Replace Sydney petrol prices with the most trending or edited articles on Wikipedia.

> In the top right also show the release date of the show and a link to the IMDb page for the show.

*Changes made:*
- Bottom-left quadrant replaced with Wikipedia's most-viewed articles for the day, fetched from the free Wikimedia REST API (no key required).
- Each Netflix show in the top-right now displays its release date and an IMDb badge link (direct title URL when Watchmode provides an `imdb_id`, otherwise an IMDb search URL).

---

### Prompt 4 — Documentation

> Please add prompts used to create this app in the README.
