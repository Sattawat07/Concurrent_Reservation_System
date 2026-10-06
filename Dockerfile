FROM maven:3.9.11-eclipse-temurin-17
ENV APP_TIMEZONE=Asia/Bangkok
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
CMD ["sleep", "infinity"]
