# SharedFrame

Gesture-driven shared-frame image transitions for Android Views and Jetpack Compose.

SharedFrame expands a loaded image from any carousel, grid, list, or custom layout into arbitrary detail content. The transition keeps the source crop continuous, clips the complete detail surface, supports Back, and can be dismissed with a smooth drag left, right, or down.

> `0.1.0-alpha02` is the current public preview. It brings Compose to Views parity, stabilizes multidirectional drag-to-dismiss, and keeps detail content fully opaque during geometric reveal and collapse.

## Features

- Views/XML and Jetpack Compose adapters with matching behavior
- Atomic first frame without a full-screen flash
- Continuous `Crop` and `Fit` interpolation across different aspect ratios
- Interactive left, right, and downward dismiss with stable finger tracking
- Fade fallback when a recycled source is no longer on screen
- Image-loader agnostic library modules
- Android API 26+

## Installation

`0.1.0-alpha02` is the current release candidate. Until the Maven Central signing
secrets are configured, clone the repository and include the required modules in
your Gradle build. After publication, choose the adapter for your UI stack:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.azizconi:shared-frame-views:0.1.0-alpha02")
    // or
    implementation("io.github.azizconi:shared-frame-compose:0.1.0-alpha02")
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

For a scrollable detail, allow downward dismiss only when its scroll container is already at the top:

```kotlin
canStartDismiss = { direction ->
    direction != SharedFrameDismissDirection.Down || !detailScroll.canScrollVertically(-1)
}
```

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

The same top-boundary rule can be supplied to `SharedFrameHost` when detail contains a `LazyColumn` or another vertical scroller:

```kotlin
canStartDismiss = { direction ->
    direction != SharedFrameDismissDirection.Down || !detailListState.canScrollBackward
}
```

## Default motion

- Duration: 250 ms
- Easing: cubic Bézier `(0.25, 0.1, 0.25, 1)`
- Scrim alpha: `0.34`
- Minimum drag scale: `0.6`
- Dismiss directions: left, right, and down
- Distance threshold: `15%` of the host's shorter side
- Velocity threshold: `700 dp/s`

Override these values with `SharedFrameConfig`.

## Sample

The `app` module contains equivalent Views and Compose feeds with carousel, grid, and vertical-list sources. Every transition uses the same loaded drawable/painter on both sides.

### Views / XML

<video src="https://github.com/user-attachments/assets/70ac1f99-d041-408a-9671-ed6cb7ed9f43" controls playsinline></video>

### Jetpack Compose

<video src="https://github.com/user-attachments/assets/5c68c521-a3bb-42f8-b88a-65698822a1ba" controls playsinline></video>

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
