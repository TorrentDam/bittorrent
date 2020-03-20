FROM openjdk:14-slim

COPY ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT ["java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC", "-XX:ZAllocationSpikeTolerance=100", "-XX:ZUncommitDelay=0", "-Xmx800m", "-jar", "/opt/server.jar"]
