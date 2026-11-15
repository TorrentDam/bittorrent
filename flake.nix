{
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          muslTarget = "${pkgs.stdenv.hostPlatform.parsed.cpu.name}-linux-musl";
          muslClang = pkgs.writeShellScriptBin "musl-clang" ''
            if [ "$1" = "--version" ]; then
              echo "clang version 21.1.8"
              echo "Target: ${muslTarget}"
              exit 0
            fi
            exec ${pkgs.zig}/bin/zig cc -target ${muslTarget} "$@"
          '';
          muslClangPP = pkgs.writeShellScriptBin "musl-clang++" ''
            if [ "$1" = "--version" ]; then
              echo "clang version 21.1.8"
              echo "Target: ${muslTarget}"
              exit 0
            fi
            exec ${pkgs.zig}/bin/zig c++ -target ${muslTarget} "$@"
          '';
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk21
              pkgs.mill
              pkgs.transmission_4
              pkgs.clang
              pkgs.zig
            ];
            MUSL_CLANG = "${muslClang}/bin/musl-clang";
            MUSL_CLANGPP = "${muslClangPP}/bin/musl-clang++";
          };
        });
    };
}
