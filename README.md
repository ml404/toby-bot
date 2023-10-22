
# Toby Bot

![Toby Bot Logo](https://your-image-url.com/toby-bot-logo.png)

## Overview

Toby Bot is a feature-rich Discord bot built using Java and powered by various technologies including Spring Boot, JDA (Java Discord API), JPA (Java Persistence API), Hibernate, and Spring Web. This bot is designed to enhance your Discord server by providing a wide range of features and commands.

## Features

- **Discord Integration:** Toby Bot seamlessly integrates with your Discord server to provide engaging features.
- **Spring Boot:** Leveraging the Spring Boot framework to efficiently configure and deploy the bot.
- **JDA:** Utilizing JDA, a robust Java library for interacting with the Discord API.
- **JPA and Hibernate:** Storing and managing data in a relational database for efficient data management.
- **Spring Web:** Opportunity to create web interfaces to interact with your bot.

## Prerequisites

Ensure you have the following prerequisites set up before getting started:

- **Java Development Kit (JDK):** You'll need a Java development environment.
- **Discord Bot Token:** Obtain a Discord bot token and define its permissions.
- **Database:** Set up a relational database, such as PostgreSQL.
- **Dependency Management:** Choose between Maven or Gradle for dependency management.

## Getting Started

1. Clone the Toby Bot repository to your local machine:

   ```shell
   git clone https://github.com/ml404/toby-bot.git
   ```

2. Customize the bot's configuration by editing `application.properties`. 
Provide your Discord bot token and database connection details via environment variables

3. Build the project:

   ```shell
   gradle clean build
   ```

4. Start the bot:

   ```shell
   java -jar build/libs/toby-bot-5.0-SNAPSHOT.jar -Dspring.profiles.active=prod
   ```

## Usage

- Invite your Toby Bot to your Discord server and grant the required permissions.
- Interact with the bot using the commands and features provided by the project.

## Acknowledgments

Special thanks to the creators and maintainers of Spring Boot, JDA, JPA, Hibernate, and Spring Web for their contributions to this project.

[GitHub Repository](https://github.com/ml404/toby-bot)