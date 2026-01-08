# ------------ Build stage ------------
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# ------------ Runtime stage ------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Копіюємо лише bootable JAR (без -plain)
# Шаблон *-SNAPSHOT.jar НЕ збігається з ...-plain.jar, тому безпечний.
# Якщо не SNAPSHOT — заміни на свій artifactId або додай ARG.
COPY --from=build /app/target/*-SNAPSHOT.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
