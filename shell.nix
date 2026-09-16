# shell.nix — simple Nix shell for OwnDroid development (non-flake)
{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:

let
  # Android SDK via nixpkgs androidenv
  # Note: API 37 installs as "android-37.0", but AGP expects "android-37" for compileSdk 37
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    cmdLineToolsVersion = "20.0";
    toolsVersion = "26.1.1";
    platformToolsVersion = "37.0.0";
    buildToolsVersions = [ "37.0.0" ];
    includeEmulator = false;
    includeNDK = false;
    includeSystemImages = false;
    includeSources = false;
    platformVersions = [ "37.0" ];
    abiVersions = [ ];
    cmakeVersions = [ ];
    useGoogleAPIs = false;
    includeExtras = [ ];
  };

  # Symlink android-37 -> android-37.0 so AGP finds it for compileSdk 37
  # Uses symlinkJoin since runCommand can't symlink to store paths
  androidSdk = pkgs.symlinkJoin {
    name = "android-sdk-ownDroid";
    paths = [ androidComposition.androidsdk ];
    postBuild = ''
      ln -s $out/libexec/android-sdk/platforms/android-37.0 $out/libexec/android-sdk/platforms/android-37
    '';
  };
in
pkgs.mkShellNoCC {
  buildInputs = with pkgs; [
    jdk21
  ] ++ [
    androidComposition.androidsdk
  ];

  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";

  shellHook = ''
    echo "OwnDroid dev shell"
    echo "  Java: $(java -version 2>&1 | head -1)"
    echo "  Android SDK: $ANDROID_HOME"
    echo ""
    echo "  Build:   ./gradlew assembleDebug"
    echo "  Install: ./gradlew installDebug"
  '';
}
