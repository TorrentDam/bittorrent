FROM eclipse-temurin:latest

COPY ./.jvm/target/universal/stage /opt/torrentdam

ENTRYPOINT ["/opt/torrentdam/bin/cmd", "-Dcats.effect.tracing.mode=none"]
