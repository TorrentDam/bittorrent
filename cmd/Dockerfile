FROM eclipse-temurin:21-jre-jammy

COPY ./.jvm/target/universal/stage /opt/torrentdam

ENTRYPOINT ["/opt/torrentdam/bin/cmd", "-XX:MaxRAMPercentage=60", "-Dcats.effect.tracing.mode=none"]
