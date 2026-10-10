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

## Development

Enter a development environment with JDK 21, Mill, and transmission via Nix:

```bash
nix develop
```

### End-to-End Tests

`TransmissionIntegrationSuite` is a true end-to-end test: it seeds a real
transmission daemon with a small public payload file (~11 MB) and verifies that
the `torrentdam` CLI downloads it over the peer-to-peer wire protocol using only
the `--peer` flag (no DHT discovery).

The test needs `transmission-daemon`, `transmission-create`, and
`transmission-remote` on the `PATH` (all provided by `nix develop`). It builds
the CLI assembly jar via `./mill cmd.assembly` if `TORRENT_TEST_CMD_JAR` is not
set. Override the seeded file with `TORRENT_TEST_PAYLOAD` if needed.

```bash
nix develop
./mill cmd.assembly
export TORRENT_TEST_CMD_JAR="$PWD/out/cmd/assembly.dest/out.jar"
./mill cmd.test.testOnly com.github.torrentdam.bittorrent.TransmissionIntegrationSuite
```

## License

[Unlicense](LICENSE) - Public Domain
