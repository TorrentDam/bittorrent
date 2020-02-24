FROM oracle/graalvm-ce:20.0.0-java11

COPY ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT ["java", "-XX:NativeMemoryTracking=summary", "-jar", "/opt/server.jar"]
