
# Toby Bot

![Toby Bot Logo](https://i.ibb.co/5BydjDZ/lo-fi-saitama.jpg)

## Overview

Toby Bot is a feature-rich Discord bot built using Kotlin and powered by various technologies including Spring Boot, JDA (Java Discord API), JPA (Java Persistence API), Hibernate, and Spring Web. This bot is designed to enhance your Discord server by providing a wide range of features and commands.

## Features

- **Discord Integration:** Toby Bot seamlessly integrates with your Discord server to provide engaging features.
- **Spring Boot:** Leveraging the Spring Boot framework to efficiently configure and deploy the bot.
- **JDA:** Utilising JDA, a robust Java library for interacting with the Discord API.
- **JPA and Hibernate:** Storing and managing data in a relational database for efficient data management.

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
   java -jar application/build/libs/application-6.0-SNAPSHOT.jar -Dspring.profiles.active=prod
   ```

## Usage

- Invite your Toby Bot to your Discord server and grant the required permissions.
- Interact with the bot using the commands and features provided by the project.

## Acknowledgments

Special thanks to the creators and maintainers of Spring Boot, JDA, JPA, Hibernate, and Spring Web for their contributions to this project.

[GitHub Repository](https://github.com/ml404/toby-bot)
