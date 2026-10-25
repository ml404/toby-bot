FROM eclipse-temurin:21-jdk AS build
WORKDIR /home/gradle/src
COPY --chown=root:root . .
RUN chmod +x gradlew && ./gradlew :application:bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN mkdir /app && useradd --system --no-create-home tobybot
COPY --from=build /home/gradle/src/application/build/libs/*.jar /app/toby-bot.jar
COPY jvm.options /app/jvm.options
# glibc otherwise creates up to 8 malloc arenas per CPU; each can hold tens of
# MB of native memory that none of the caps in jvm.options cover.
ENV MALLOC_ARENA_MAX=2
USER tobybot
EXPOSE 8080
ENTRYPOINT ["java", "@/app/jvm.options", "-jar", "/app/toby-bot.jar"]
