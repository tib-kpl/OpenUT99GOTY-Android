# UT99GOTY-Android

Unofficial Android launcher for **Unreal Tournament 99 GOTY**, using OldUnreal's native Linux 469 ARM64 engine inside a minimal ARM64 Linux runtime. Android 8+ and an ARM64 device are required.

## Install

Download the signed APK from [Releases](https://github.com/tib-kpl/UT99GOTY-Android/releases/latest). Allow file access, then select an existing PC game folder or choose **Telecharger le jeu** to install the official OldUnreal game media into a folder of your choice. Downloads resume after interruption and the media is checked against the SHA-256 published by the official installer. The OldUnreal Linux patch is downloaded at first launch. No game data or proprietary engine binaries are bundled in the APK.

The downloader installs the GOTY CD1 data and Bonus Pack 4. Compressed GOTY maps are decompressed with the native ARM64 ucc commandlet into a private persistent cache; original game files are preserved.

## Controls and settings

The right stick moves the menu pointer in Mouse ON mode and aims in Mouse OFF mode. Use **Select** or the centered button to change modes. The label follows the selected game language. Native SDL mouse capture also reports mode changes; the manual button remains available for menus that keep capture enabled.

| Control | Action |
|---|---|
| Left stick | WASD in game, arrows in menus |
| D-pad | Native arrow keys |
| R2 / L2 | Fire / secondary fire, mouse left / right click |
| A | Jump in game, Enter in menus |
| B / Start | Escape / menu |
| L3 | Crouch |
| R3 | Best weapon |
| L1 / R1 | Previous / next weapon |
| X in menus | Next focused control (Tab) |
| Y in menus | Android keyboard for the selected text field |

Touch input positions and clicks the pointer in Mouse ON mode. The gear opens launcher settings; **Retour au jeu** resumes the same session; **Redemarrer le jeu** applies language and graphics changes by restarting the engine. **Graphismes** provides integrated Turnip/Zink Vulkan rendering on compatible Adreno devices, automatic selection, software fallback, 540p/720p/1080p, and an optional engine FPS counter. FPS visibility changes on return to the running game; renderer and resolution apply at the next launch. Android driver ZIP files are unsupported and are not offered.

Saves, cache and user settings stay in the app private home directory; game configuration uses `UnrealTournament.ini` and `User.ini`. Keep the same signing key for updates to preserve app data.

## Build and releases

`android/runtime/build-mesa.sh` compiles Mesa Turnip/Zink; `build-runtime.sh` assembles the Debian ARM64 runtime and SDL3 input/audio bridge. `android/runtime/prepare-proot.py` retrieves the Termux proot binaries. Run these scripts in the documented Linux ARM64 containers, then build the APK with JDK 21 and Android SDK 36:

```sh
cd android
./gradlew assembleRelease
```

Pushes to `main` run the release workflow with native ARM64 GitHub runners. Configure the four `OPENUT99GOTY_KEYSTORE_BASE64`, `_KEYSTORE_PASSWORD`, `_KEY_ALIAS` and `_KEY_PASSWORD` signing secrets. Private keys and generated runtimes/APKs must remain outside Git.

## Upstream

- [OldUnreal patches](https://github.com/OldUnreal/UnrealTournamentPatches)
- [Official full game installers](https://github.com/OldUnreal/FullGameInstallers/tree/master/Linux)
- [Third-party notices](THIRD_PARTY.md)

This launcher is not affiliated with Epic Games or OldUnreal. Upstream game and patch licenses apply separately from the launcher license.

The colored app icon and launcher illustration are original generated fan art inspired by the respective game. **Configurer la manette** stores per-device button mappings and right-stick axis choices. Generic Android button layouts and RX/RY right-stick axes are supported; AYN Thor still requires physical verification.

### External controllers

Xbox, PlayStation (DualShock / DualSense), Switch-style and generic Android gamepads
use Android's standard button positions over USB or Bluetooth. The configuration screen
shows the corresponding physical button labels for Microsoft, Sony and Nintendo devices;
other controllers keep the generic A/B/X/Y labels. Custom bindings remain saved per device.
Both digital and analog triggers are accepted and merged, with no duplicate action.
Right-stick Z/RZ and RX/RY layouts and device dead zones are handled; held actions are
released when a controller disconnects or is reconfigured. Linux cannot open a second
copy of the physical controller alongside the Android input bridge.

Odin 2 Portal mappings are preserved. AYN Thor menu and in-game controls were validated in Daikatana.
The other ports use the same Android controller normalization. Xbox, DualSense and Switch controllers still need physical testing.
