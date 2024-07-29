# Use the latest Gradle image with JDK 17 for the build stage
FROM gradle:7.4.1-jdk17 AS build

# Set the working directory inside the container
WORKDIR /home/gradle/src

# Copy the project files to the working directory
COPY --chown=gradle:gradle . /home/gradle/src

# Build the project
RUN gradle build --no-daemon

# Use the latest OpenJDK 17 image for the runtime stage
FROM openjdk:17-jdk-slim

# Expose the application port
EXPOSE 8080

# Create the application directory
RUN mkdir /app

# Copy the built jar file from the build stage
COPY --from=build /home/gradle/src/build/libs/*.jar /app/spring-boot-application.jar

# Set the entry point to run the application
ENTRYPOINT ["java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseContainerSupport", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/spring-boot-application.jar"]
