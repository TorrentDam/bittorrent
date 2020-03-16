FROM openjdk:14-slim

COPY ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT ["java", "-XX:G1PeriodicGCSystemLoadThreshold=0.0", "-XX:G1PeriodicGCInterval=30000", "-Xmx700m", "-jar", "/opt/server.jar"]
