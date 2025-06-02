FROM eclipse-temurin:24-jre-noble

COPY ./.jvm/target/universal/stage /opt/torrentdam

ENTRYPOINT ["/opt/torrentdam/bin/cmd", "-Dcats.effect.tracing.mode=none", "-J-XX:+UnlockExperimentalVMOptions", "-J-XX:+UseCompactObjectHeaders"]
