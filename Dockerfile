FROM oracle/graalvm-ce:latest as build

COPY . .

RUN ./mill server.assembly

FROM oracle/graalvm-ce:latest

COPY --from=build ./out/server/assembly/dest/out.jar /opt/server.jar

ENTRYPOINT "java" "-jar" "/opt/server.jar"
