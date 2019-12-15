FROM oracle/graalvm-ce:latest

COPY ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT ["java", "-Xmx300m", "-jar", "/opt/server.jar"]
