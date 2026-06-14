FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/zhoujie-devsecops-demo-1.0.0-SNAPSHOT.jar app.jar
RUN apk update && apk upgrade   # 修复 Alpine 现有漏洞
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
