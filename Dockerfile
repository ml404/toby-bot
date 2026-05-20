FROM eclipse-temurin:21-jdk AS build
WORKDIR /home/gradle/src
COPY --chown=root:root . .
RUN chmod +x gradlew && ./gradlew :application:bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN mkdir /app
COPY --from=build /home/gradle/src/application/build/libs/*.jar /app/toby-bot.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xms128m", "-Xmx224m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=192m", "-XX:ReservedCodeCacheSize=48m", "-XX:MaxDirectMemorySize=48m", "-Dio.netty.maxDirectMemory=0", "-Dio.netty.allocator.numDirectArenas=2", "-Dio.netty.allocator.numHeapArenas=2", "-Xss256k", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/toby-bot.jar"]
