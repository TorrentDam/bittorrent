FROM oracle/graalvm-ce:latest

COPY ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT ["java", "-XX:NativeMemoryTracking=summary", "-jar", "/opt/server.jar"]
