
# Toby Bot

![Toby Bot Logo](https://i.ibb.co/5BydjDZ/lo-fi-saitama.jpg)

## Overview

Toby Bot is a feature-rich Discord bot built using Kotlin and powered by various technologies including Spring Boot, JDA (Java Discord API), JPA (Java Persistence API), Hibernate, and Spring Web. This bot is designed to enhance your Discord server by providing a wide range of features and commands.

## Features

- **Discord Integration:** Toby Bot seamlessly integrates with your Discord server to provide engaging features.
- **Spring Boot:** Leveraging the Spring Boot framework to efficiently configure and deploy the bot.
- **JDA:** Utilising JDA, a robust Java library for interacting with the Discord API.
- **JPA and Hibernate:** Storing and managing data in a relational database for efficient data management.
- **Web dashboard + OAuth2:** Companion web UI (Spring MVC + Thymeleaf) for profile, achievements, notification preferences, and admin moderation. Logs in via Discord OAuth2.
- **Per-surface notifications:** Each notification kind (achievement unlock, level-up, duel offer, tip received, streak reminder, lottery draw) ships across three surfaces — DM, channel post, and Web Push — independently opt-in per user. The router enforces "every supported surface is wired" at dispatch time so a kind can never silently drop a surface again.
- **Web Push (RFC 8030/8291/8292):** Browser push notifications via VAPID. A service worker handles delivery and deep-links the recipient back into the dashboard.

## Prerequisites

Ensure you have the following prerequisites set up before getting started:

- **Kotlin installation:** You'll need to a Kotlin development environment
- **Java Development Kit (JDK):** You'll need a Java development environment as Kotlin compiles to Java.
- **Discord Bot Token:** Obtain a Discord bot token and define its permissions.
- **Google Refresh Token** Obtain a google refresh token to allow Oauth2 to be set for the YoutubeAudioSourceManager inside the PlayerManager class
- **Database:** Set up a relational database, such as PostgreSQL.
- **Dependency Management:** Choose between Maven or Gradle for dependency management.

## Getting Started

1. Clone the Toby Bot repository to your local machine:

   ```shell
   git clone https://github.com/ml404/toby-bot.git
   ```

2. Customize the bot's configuration by editing `application.properties`. 

   1. Provide your Discord bot token and database connection details via environment variables

3. Build the project:

   ```shell
   gradle clean build
   ```

4. Start the bot:

   ```shell
   java -jar application/build/libs/application-7.7-SNAPSHOT.jar -Dspring.profiles.active=prod
   ```

## Running with Docker

Requires [Docker Desktop](https://www.docker.com/products/docker-desktop/).

1. Provide the required environment variables — either via a `.env` file or your system environment:

   | Variable | Required | Description |
   |---|---|---|
   | `TOKEN` | Yes | Discord bot token |
   | `DATABASE_URL` | Yes | PostgreSQL URL (`postgresql://user:pass@host:5432/db`) |
   | `DISCORD_CLIENT_ID` | Yes (web) | Discord OAuth2 client id for the web dashboard login flow. |
   | `DISCORD_CLIENT_SECRET` | Yes (web) | Discord OAuth2 client secret. Pair with `DISCORD_CLIENT_ID`. |
   | `APP_BASE_URL` | No | Public base URL of the web dashboard (e.g. `https://www.toby-bot.co.uk`). Used to build deep links inside Web Push notifications and any other outbound message that needs an absolute URL. When unset, push payloads omit the deep link. |
   | `TOBY_VAPID_PUBLIC_KEY` | No | VAPID public key (base64url) for Web Push. Generate a keypair with `npx web-push generate-vapid-keys`. The public key is served to browsers at `GET /api/push/vapid-public-key`. When unset, the `WebPushAdapter` bean is not registered and the router silently drops push (everything else keeps working). |
   | `TOBY_VAPID_PRIVATE_KEY` | No | VAPID private key (base64url). Pair with `TOBY_VAPID_PUBLIC_KEY`; never commit. |
   | `TOBY_VAPID_SUBJECT` | No | `mailto:` or `https://` contact URL required by RFC 8292. Defaults to `mailto:admin@example.invalid`; override in production. |
   | `YOUTUBE_API_KEY` | No | YouTube Data API key |
   | `GOOGLE_REFRESH_TOKEN` | No | OAuth2 refresh token for YouTube playback. See [lavaplayer-youtube OAuth2 docs](https://github.com/lavalink-devs/youtube-source#oauth-tokens) for how to mint one. Authenticated requests are far less likely to be IP-blocked. |

   **Option A — `.env` file** (copy the example and fill in your values):

   ```shell
   cp .env.example .env
   ```

   **Option B — host environment variables** (e.g. set in Windows user variables or Heroku config vars): Docker Compose will pass them through automatically.

2. Build and start the bot:

   ```shell
   docker compose up --build
   ```

   To run in the background: `docker compose up --build -d`

## Usage

- Invite your Toby Bot to your Discord server and grant the required permissions.
- Interact with the bot using the commands and features provided by the project.

## Enabling Web Push

Toby Bot ships Web Push (RFC 8030 / 8291 / 8292) for users who opt in via `/preferences/notifications` in the web dashboard.

1. Generate a VAPID keypair (any P-256 ECDSA keypair will do):

   ```shell
   npx web-push generate-vapid-keys
   ```

2. Export the keys as env vars (Heroku config var, Docker secret, `.env` file, etc.):

   ```
   TOBY_VAPID_PUBLIC_KEY=<publicKey>
   TOBY_VAPID_PRIVATE_KEY=<privateKey>
   TOBY_VAPID_SUBJECT=mailto:admin@your-domain
   ```

   Spring's relaxed binding maps these to `toby.vapid.public-key` etc. When both keys are present the `WebPushAdapter` bean is registered and `NotificationRouter.sendPush` forwards opted-in pushes through it. When either is unset the adapter never registers — the rest of the bot keeps working unchanged.

3. (Optional) set `APP_BASE_URL` so push payloads include a deep link back to the user's profile/achievements page in the dashboard.

4. Smoke test after redeploy:

   ```shell
   curl -s https://your-host/api/push/vapid-public-key
   ```

   200 with the configured public key in the body means the keys are wired. 404 means the env vars aren't loaded yet.

5. End-to-end: log in via Discord, toggle Push on for any event in `/preferences/notifications`, then trigger the event — a notification should land in the browser.

## Support the Project

TobyBot is free and open source. If you'd like to help cover hosting costs or just say thanks, you can support development on [Ko-fi](https://ko-fi.com/fratlayton).

## Acknowledgments

Special thanks to the creators and maintainers of Spring Boot, JDA, JPA, Hibernate, and Spring Web for their contributions to this project.

[GitHub Repository](https://github.com/ml404/toby-bot)
