FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# คัดลอกโค้ดทั้งหมด
COPY Server.java Client.java ./

# Compile โค้ด Java
RUN javac Server.java Client.java

# เปิด Port 8080
EXPOSE 8080

CMD ["java", "Server"]