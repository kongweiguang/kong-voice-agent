FROM bellsoft/liberica-openjdk-debian:21.0.4-cds

LABEL maintainer="kongweiguang"

RUN mkdir -p /kong/server/logs \
    /kong/models

WORKDIR /kong/server

ENV SERVER_PORT=9877 LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_OPTS="-Xms8g -Xmx8g" SPRING_ACTIVE=prod

EXPOSE ${SERVER_PORT}

ADD ./app/target/kong-voice-agent-app-1.0-SNAPSHOT.jar ./app.jar

ENTRYPOINT java -Djava.security.egd=file:/dev/./urandom \
           -Dserver.port=${SERVER_PORT} \
           -Dspring.profiles.active=${SPRING_ACTIVE} \
           -XX:+HeapDumpOnOutOfMemoryError \
           -XX:+UseZGC ${JAVA_OPTS} \
           -jar app.jar

