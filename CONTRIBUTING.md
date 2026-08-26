# Contributing

Issues and pull requests are welcome.

1. Fork the repository and create a focused branch.
2. Keep image loading outside the library modules.
3. Add or update tests for geometry, state, and gestures.
4. Run `./gradlew testDebugUnitTest lint assembleDebug`.
5. Describe visual changes and attach a short screen recording when relevant.

Public API changes should remain source-compatible within a published version line. Never commit Maven Central credentials or signing material.
