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

A native (Scala Native) image built from the CI-produced binary on Ubuntu is also published:

```bash
docker pull ghcr.io/torrentdam/cmd-native:latest
docker run --rm ghcr.io/torrentdam/cmd-native:latest --help
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

`TransmissionIntegrationSuite` is a true end-to-end test: it generates a
random payload (16 MB), seeds it with a real transmission daemon, and verifies
that the `torrentdam` CLI downloads it over the peer-to-peer wire protocol using
only the `--peer` flag (no DHT discovery). The test is fully self-contained
and needs no internet access.

The test needs `transmission-daemon`, `transmission-create`, and
`transmission-remote` on the `PATH` (all provided by `nix develop`). It builds
the CLI assembly jar via `mill cmd.assembly` if `TORRENT_TEST_CMD_JAR` is not
set.

```bash
nix develop
mill cmd.assembly
export TORRENT_TEST_CMD_JAR="$PWD/out/cmd/assembly.dest/out.jar"
mill e2e.jvm.test
```

### Android app

The `android` module builds an APK from the JVM modules (`common`, `bittorrent`, `dht`,
`files`) via Mill's `AndroidAppScalaModule`: the Scala code is compiled to JVM bytecode
and dexed by `d8`, so **the BitTorrent library runs on Android/ART as-is** — no separate
engine needed. The Android SDK (cmdline-tools, build-tools 36, platform-tools,
platform android-36) is provided by Nix through `android-nixpkgs`; `nix develop` points
`ANDROID_HOME` at a symlink farm under `~/.cache/torrentdam-bittorrent/.android-sdk`.

```bash
nix develop
mill android.test          # unit tests (run on the JVM)
mill android.androidApk    # signed APK -> out/android/androidApk.dest/app.apk
```

Run on an emulator (extra dev shell with the emulator + a system image):

```bash
nix develop .#emulator --command mill android.createAndroidVirtualDevice
nix develop .#emulator --command mill android.startAndroidEmulator
nix develop .#emulator --command mill android.androidInstall
nix develop .#emulator --command mill android.androidRun
nix develop .#emulator --command mill android.stopAndroidEmulator
```

## License

[Unlicense](LICENSE) - Public Domain
