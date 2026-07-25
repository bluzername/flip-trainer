# Flip Trainer — iPhone controller

SwiftUI app. Pairs with the Galaxy sensor over BLE, configures and controls sets, shows live
progress, and stores workout history locally with SwiftData. No accounts, no network.

## Build

The `.xcodeproj` is generated, not committed (ADR-0006):

```bash
brew install xcodegen
cd ios-app
xcodegen generate
open FlipTrainer.xcodeproj
```

Then select your device and run. Requires Xcode 16+ and iOS 17+ on the device.

```bash
xcodebuild -project FlipTrainer.xcodeproj -scheme FlipTrainer \
  -destination 'platform=iOS Simulator,name=iPhone 16' test
```

## Status

> **This code has never been compiled.** It was written in a Linux environment with no Xcode,
> no `swift`, and no iPhone. Expect to fix compile errors on first build. No behavior here has
> been verified on a device. See `../docs/DEVICE_NOTES.md`.

## Layout

```
FlipTrainer/
  App/            entry point, root navigation
  Pairing/        QR payload parsing, pairing handshake, keychain storage
  Transport/      CoreBluetooth central, framing, authenticated envelopes, ack/replay
  Model/          SwiftData models, idempotent event import, corrections
  Catalog/        exercise catalog loaded from ../exercise-definitions
  UI/             screens
FlipTrainerTests/
```
