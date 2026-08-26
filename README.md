# SharedFrame

Gesture-driven shared-frame image transitions for Android Views and Jetpack Compose.

SharedFrame expands a loaded image from any carousel, grid, list, or custom layout into arbitrary detail content. The transition keeps the source crop continuous, clips the complete detail surface, supports Back, and can be dismissed with an iOS-style rightward drag.

> `0.1.0-alpha01` is the first public preview. Feedback and API suggestions are welcome.

## Features

- Views/XML and Jetpack Compose adapters with matching behavior
- Atomic first frame without a full-screen flash
- Continuous `Crop` and `Fit` interpolation across different aspect ratios
- Rightward interactive dismiss, cancel, and velocity/distance completion
- Fade fallback when a recycled source is no longer on screen
- Image-loader agnostic library modules
- Android API 26+

## Installation

`0.1.0-alpha01` is the first release candidate. Until the Maven Central signing
secrets are configured, clone the repository and include the required modules in
your Gradle build. After publication, choose the adapter for your UI stack:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.azizconi:shared-frame-views:0.1.0-alpha01")
    // or
    implementation("io.github.azizconi:shared-frame-compose:0.1.0-alpha01")
}
```

`shared-frame-core` is resolved transitively. Coil is used only by the sample app; use any image loader in your application.

## Views / XML

Create one controller for a full-screen `FrameLayout` host, then pass the already loaded source drawable and your arbitrary detail layout:

```kotlin
val controller = SharedFrameViewController(binding.sharedFrameHost)

sourceImage.setOnClickListener {
    val detail = ViewPhotoDetailBinding.inflate(layoutInflater)
    controller.open(
        SharedFrameViewRequest(
            key = photo.id,
            sourceHero = sourceImage,
            drawable = requireNotNull(sourceImage.drawable),
            sourceRadiusPx = 16.dp,
            detailRoot = detail.root,
            detailHero = detail.hero,
        )
    )
}

onBackPressedDispatcher.addCallback(this) {
    if (!controller.handleBack()) finish()
}

override fun onDestroy() {
    controller.dispose()
    super.onDestroy()
}
```

Only call `open` after the drawable is loaded and both source/detail can be measured.

## Jetpack Compose

Place content inside one `SharedFrameHost`, register loaded source painters, and mark the hero inside detail content:

```kotlin
val controller = rememberSharedFrameController()

SharedFrameHost(
    controller = controller,
    detailContent = {
        Column {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .sharedFrameDetailHero(ContentScale.Crop),
            )
        }
    },
) {
    Image(
        painter = loadedPainter,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .sharedFrameSource(
                controller = controller,
                key = photo.id,
                painter = loadedPainter,
                contentScale = ContentScale.Crop,
                cornerRadius = 16.dp,
            )
            .clickable { controller.open(photo.id) },
    )
}
```

The source modifier must receive a painter with a finite intrinsic size. Register it after the loader reports success. Pass the actual, independent `ContentScale` to both source and detail registration. The first preview deliberately supports `ContentScale.Crop` and `ContentScale.Fit`; unsupported non-uniform scales fail fast instead of producing a broken morph.

## Default motion

- Duration: 250 ms
- Easing: cubic Bézier `(0.25, 0.1, 0.25, 1)`
- Scrim alpha: `0.34`
- Minimum drag scale: `0.6`
- Distance threshold: `25%`
- Velocity threshold: `1100 dp/s`

Override these values with `SharedFrameConfig`.

## Sample

The `app` module contains equivalent Views and Compose feeds with carousel, grid, and vertical-list sources. Every transition uses the same loaded drawable/painter on both sides.

### Views / XML

<video src="https://github.com/user-attachments/assets/8216281b-e31d-4de9-9dd5-c0c51ab62305" controls playsinline></video>

### Jetpack Compose

<video src="https://github.com/user-attachments/assets/e745188c-6031-4a8b-be9c-4e27b398cbdd" controls playsinline></video>

## Building

```shell
./gradlew testDebugUnitTest lint assembleDebug
```

Instrumentation tests require an API 26+ emulator:

```shell
./gradlew connectedDebugAndroidTest
```

## License

```
Copyright 2026 Azizjon

Licensed under the Apache License, Version 2.0.
```

See [LICENSE](LICENSE).
