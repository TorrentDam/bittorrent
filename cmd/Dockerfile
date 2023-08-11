FROM eclipse-temurin:latest

COPY ./.jvm/target/universal/stage /opt/torrentdam

ENTRYPOINT ["/opt/torrentdam/bin/cmd", "-XX:MaxRAMPercentage=70", "-Dcats.effect.tracing.mode=none"]
