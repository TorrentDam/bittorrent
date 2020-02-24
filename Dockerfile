FROM oracle/graalvm-ce:20.0.0-java11

COPY ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT ["java", "-Xmx250m", "-jar", "/opt/server.jar"]
