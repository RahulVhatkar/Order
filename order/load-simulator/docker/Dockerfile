FROM adoptopenjdk/openjdk16:alpine-slim
ARG JAR_FILE=load-simulator-0.0.2-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["java","-jar","/app.jar"]