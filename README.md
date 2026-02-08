# TorrentDam BitTorrent Client

A BitTorrent client library and command-line tool for downloading and managing torrents.

## What It Does

- **Download torrents** from the BitTorrent network using .torrent files or magnet links
- **Discover peers** without trackers via the Distributed Hash Table (DHT) network  
- **Fetch metadata** to download .torrent files from peers using just an info-hash
- **Verify downloads** against piece checksums to ensure data integrity
- **Run as a DHT node** to participate in the decentralized peer discovery network

## Installation

### Docker

```bash
docker pull ghcr.io/torrentdam/cmd:latest
docker run --rm ghcr.io/torrentdam/cmd:latest --help
```

## Quick Start

Download a torrent:
```bash
torrentdam torrent download --info-hash <hash> --save /downloads
```

Fetch a .torrent file from the DHT:
```bash
torrentdam torrent fetch-file --info-hash <hash> --save file.torrent
```

Verify a download:
```bash
torrentdam torrent verify --torrent file.torrent --target /downloads
```

## Usage

Run `torrentdam --help` for complete command reference.

## License

[Unlicense](LICENSE) - Public Domain
