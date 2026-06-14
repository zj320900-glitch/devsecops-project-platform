FROM eclipse-temurin:21-alpine
WORKDIR /app  
COPY target/zhoujie-devsecops-demo-1.0.0-SNAPSHOT.jar app.jar  
EXPOSE 8080  
ENTRYPOINT ["java", "-jar", "app.jar"] 
