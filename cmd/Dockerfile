FROM eclipse-temurin:24-jre-noble

COPY ./out/cmd/assembly.dest/out.jar /opt/torrentdam/assembly.jar

ENTRYPOINT [ "java", "-Dcats.effect.tracing.mode=none", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders", "-jar", "/opt/torrentdam/assembly.jar"]
