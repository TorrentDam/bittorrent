FROM eclipse-temurin:25-jre-noble

COPY ./out/cmd/assembly.dest/out.jar /opt/torrentdam/assembly.jar

ENTRYPOINT ["java"]
CMD ["-Dcats.effect.tracing.mode=none", \
     "-XX:+UnlockExperimentalVMOptions", \
     "-XX:+UseCompactObjectHeaders", \
     "-jar", \
     "/opt/torrentdam/assembly.jar"]
