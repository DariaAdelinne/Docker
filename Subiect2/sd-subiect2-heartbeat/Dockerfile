FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml pom.xml
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/target/sd-subiect2-heartbeat-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar
COPY questions_database_student1.txt questions_database_student1.txt
COPY questions_database_student2.txt questions_database_student2.txt
ENV MAIN_CLASS=com.sd.laborator.client.ChatClientMainKt
ENTRYPOINT ["sh", "-c", "java -cp /app/app.jar $MAIN_CLASS $JAVA_ARGS"]
