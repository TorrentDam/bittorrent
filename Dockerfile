FROM ubuntu

COPY ./out/server/nativeImage/dest/out /opt/bittorrent-server

ENTRYPOINT ["/opt/bittorrent-server"]
