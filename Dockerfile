FROM openjdk:15
ENV DATABASE_URL 'postgres://iqfaqjktaojqob:7dcb3d54e328da3a9f958ce741a362f1aded53377af2f020d380043d20b7a726@ec2-176-34-211-0.eu-west-1.compute.amazonaws.com:5432/d6g8v4nlh191c6'
EXPOSE 8080
ADD build/libs/toby-bot-5.0-SNAPSHOT-all.jar toby-bot-5.0-SNAPSHOT-all.jar
ENTRYPOINT ["java", "-jar", "/toby-bot-5.0-SNAPSHOT-all.jar"]