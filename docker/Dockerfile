FROM eclipse-temurin:25-jdk-alpine
COPY ./target/shipping-service-*.jar /usr/app/app.jar
WORKDIR /usr/app
RUN touch app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
EXPOSE 8086
