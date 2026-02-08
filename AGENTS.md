# Agent Instructions

## Build/Test Commands

```bash
# Compile all modules
./mill _.compile

# Run all tests
./mill _.test

# Run single test class (example)
./mill bittorrent.test.testOne io.github.torrentdam.bittorrent.TorrentMetadataSpec

# Build CLI executable JAR
./mill cmd.assembly

# Format code with Scalafmt
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
```

## Project Structure

- **build.mill**: Mill build definition (primary)
- **common/**: Shared types (InfoHash, PeerId, MagnetLink)
- **dht/**: Distributed Hash Table implementation
- **bittorrent/**: Core BitTorrent protocol (shared + jvm sources)
- **files/**: File I/O utilities
- **cmd/**: CLI application entry point

Each module has `src/main/scala/` and `src/test/scala/`

## Code Style

- **Language**: Scala 3
- **Max line length**: 120 characters
- **Indentation**: 2 spaces at definition sites
- **Imports**: Sorted and expanded (no wildcards)
- **Testing**: MUnit with cats-effect (munit-cats-effect)

## Key Libraries

- Cats Effect (IO, Resource)
- FS2 (streaming)
- Scodec Bits (binary data)
- Bencode

## Patterns

- Use `given`/`using` for type classes
- Prefer `IO` for effects, avoid side effects in pure code
- Package: `io.github.torrentdam.bittorrent`
- Tests extend `munit.FunSuite` with `CatsEffectAssertions`
