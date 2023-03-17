FROM openjdk:15
EXPOSE 8080
ADD build/libs/toby-bot-5.0-SNAPSHOT-all.jar toby-bot-5.0-SNAPSHOT-all.jar
ENTRYPOINT ["java", "-jar", "/toby-bot-5.0-SNAPSHOT-all.jar"]