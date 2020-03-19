FROM openjdk:14-slim

COPY ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT ["java", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=20", "-XX:G1PeriodicGCInterval=60000", "-Xmx700m", "-jar", "/opt/server.jar"]
