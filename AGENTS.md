# Agent Instructions

## Build/Test Commands

```bash
# Compile all modules (both platforms)
./mill __.jvm.compile
./mill __.native.compile

# Run all tests (both platforms)
./mill __.jvm.test
./mill __.native.test

# Run single test class (example)
./mill bittorrent.jvm.test.testOne io.github.torrentdam.bittorrent.TorrentMetadataSpec

# Build CLI executable JAR
./mill cmd.jvm.assembly

# Build CLI native binary (requires clang)
./mill cmd.native.nativeLink

# Format code with Scalafmt
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
```

## Project Structure

- **build.mill**: Mill build definition (primary)
- **common/**: Shared types (InfoHash, PeerId, MagnetLink)
- **dht/**: Distributed Hash Table implementation
- **bittorrent/**: Core BitTorrent protocol (shared + platform sources)
- **files/**: File I/O utilities
- **cmd/**: CLI application entry point

Each module uses the standard Mill cross-platform layout:
`<module>/src` (shared), `<module>/src-jvm`, `<module>/src-native`,
with tests in `<module>/test/src` and resources in `<module>/test/resources`.
Each module has `jvm` and `native` submodules, e.g. `./mill bittorrent.native.test`.

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
