FROM eclipse-temurin:22-jdk AS build
WORKDIR /home/gradle/src
COPY --chown=root:root . .
RUN chmod +x gradlew && ./gradlew :application:bootJar --no-daemon

FROM eclipse-temurin:22-jre
RUN mkdir /app
COPY --from=build /home/gradle/src/application/build/libs/*.jar /app/toby-bot.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xms64m", "-Xmx320m", "-XX:+UseZGC", "-jar", "/app/toby-bot.jar"]
