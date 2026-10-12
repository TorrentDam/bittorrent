FROM eclipse-temurin:25-jre-noble
COPY ./out/cmd/jvm/assembly.dest/out.jar /opt/torrentdam/assembly.jar
ENTRYPOINT ["java", \
            "-Dcats.effect.tracing.mode=none", \
            "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED", \
            "-XX:+UnlockExperimentalVMOptions", \
            "-XX:+UseCompactObjectHeaders", \
            "-jar", \
            "/opt/torrentdam/assembly.jar"]
