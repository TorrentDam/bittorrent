{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # Android SDK packages from Google's SDK repository, packaged for Nix.
    # Supported: x86_64-linux, x86_64-darwin, aarch64-darwin (not aarch64-linux).
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs/stable";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    { self, nixpkgs, android-nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;

      # Android SDK components needed to build the APK with Mill (the `android` module in
      # build.mill). Keep in sync with `androidSdk` in build.mill:
      #   cmdline-tools-19-0   -> cmdlineToolsVersion
      #   build-tools-36-0-0   -> buildToolsVersion
      #   platforms-android-36 -> androidCompileSdk = 36 (platformsVersion derives from buildToolsVersion)
      sdkComponents =
        sdkPkgs:
        with sdkPkgs; [
          cmdline-tools-19-0
          build-tools-36-1-0
          platform-tools # adb
          platforms-android-36
        ];

      androidSupported = system: builtins.elem system [ "x86_64-linux" "x86_64-darwin" "aarch64-darwin" ];

      androidSdkFor =
        system:
        if androidSupported system then android-nixpkgs.sdk.${system} sdkComponents else null;

      emulatorImage =
        system: if system == "aarch64-darwin" then "system-images-android-36-google-apis-arm64-v8a" else "system-images-android-36-google-apis-x86-64";

      emulatorSdkFor =
        system:
        if androidSupported system then
          android-nixpkgs.sdk.${system} (
            sdkPkgs: sdkComponents sdkPkgs ++ [ sdkPkgs.emulator sdkPkgs."${emulatorImage system}" ]
          )
        else
          null;

      # Mill forbids tasks from reading undeclared files inside the workspace, so expose the
      # Nix-provided SDK through a writable symlink farm outside the project directory.
      # The pinned tools themselves stay in the read-only Nix store.
      androidSdkHook =
        sdk:
        let
          sdkPath = "${sdk}/share/android-sdk";
        in
        ''
          farm="$HOME/.cache/torrentdam-bittorrent/.android-sdk"
          mkdir -p "$farm"
          for entry in ${sdkPath}/*; do
            ln -sfn "$entry" "$farm/$(basename "$entry")"
          done
          export ANDROID_HOME="$farm"
          export ANDROID_SDK_ROOT="$farm"
        '';
    in
    {
      devShells = forAllSystems (
        system:
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
          androidSdk = androidSdkFor system;
          emulatorSdk = emulatorSdkFor system;
          basePackages = [
            pkgs.jdk21
            pkgs.mill
            pkgs.transmission_4
            pkgs.clang
            pkgs.zig
          ];
          baseEnv = {
            MUSL_CLANG = "${muslClang}/bin/musl-clang";
            MUSL_CLANGPP = "${muslClangPP}/bin/musl-clang++";
          };
        in
        {
          default = pkgs.mkShell (
            baseEnv
            // {
              packages = basePackages ++ pkgs.lib.optional (androidSdk != null) androidSdk;
              shellHook = pkgs.lib.optionalString (androidSdk != null) (androidSdkHook androidSdk);
            }
          );
          # Extra shell with the Android emulator + a system image, for running the app:
          #   nix develop .#emulator --command mill android.startAndroidEmulator
          emulator = pkgs.mkShell (
            baseEnv
            // {
              packages = basePackages ++ pkgs.lib.optional (emulatorSdk != null) emulatorSdk;
              shellHook = pkgs.lib.optionalString (emulatorSdk != null) (androidSdkHook emulatorSdk);
            }
          );
        }
      );
    };
}
