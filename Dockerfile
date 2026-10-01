FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

COPY Server.java Client.java RaceTest.java ./

RUN javac Server.java Client.java RaceTest.java

EXPOSE 8080

CMD ["java", "Server"]
