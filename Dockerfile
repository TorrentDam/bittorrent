FROM openjdk:14-slim

COPY ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT ["java", "-jar", "/opt/server.jar"]
