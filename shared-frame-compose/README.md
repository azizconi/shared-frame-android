# shared-frame-compose

Compose-реализация shared frame перехода. Публичный API состоит из `SharedFrameComposeController`, `SharedFrameHost`, `sharedFrameSource` и `sharedFrameDetailHero`.

## Подключение

```kotlin
dependencies {
    implementation(project(":shared-frame-compose"))
}
```

Модуль транзитивно предоставляет типы из `shared-frame-core`, включая `SharedFrameConfig` и `SharedFramePhase`.

## Минимальный пример

```kotlin
@Composable
fun GalleryScreen(photoPainter: Painter) {
    val controller = rememberSharedFrameController()

    SharedFrameHost(
        controller = controller,
        modifier = Modifier.fillMaxSize(),
        detailContent = {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .sharedFrameDetailHero(),
                )
                Button(
                    onClick = controller::close,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text("Закрыть")
                }
            }
        },
    ) {
        Image(
            painter = photoPainter,
            contentDescription = "Открыть фотографию",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(160.dp)
                .sharedFrameSource(
                    controller = controller,
                    key = "photo-42",
                    painter = photoPainter,
                    contentScale = ContentScale.Crop,
                    cornerRadius = 16.dp,
                )
                .clickable { controller.open("photo-42") },
        )
    }
}
```

`SharedFrameHost` должен охватывать и исходный контент, и экран деталей. Для каждого источника нужен стабильный уникальный `key`. Перед вызовом `open(key)` источник должен успеть пройти layout и зарегистрироваться через `sharedFrameSource`.

## API

### `rememberSharedFrameController`

```kotlin
val controller = rememberSharedFrameController(
    config = SharedFrameConfig(durationMillis = 300L),
)
```

Создаёт и запоминает контроллер. Конфигурация доступна как `controller.config`, текущее состояние — как observable-свойства `controller.phase` и `controller.activeKey`.

### `Modifier.sharedFrameSource`

Регистрирует composable как исходный hero-элемент:

```kotlin
Modifier.sharedFrameSource(
    controller = controller,
    key = photo.id,
    painter = painter,
    contentScale = ContentScale.Crop,
    cornerRadius = 12.dp,
)
```

- `painter` должен иметь конечный положительный `intrinsicSize`, иначе невозможно рассчитать переход изображения.
- Реализация различает `ContentScale.Fit` и остальные значения: `Fit` считается aspect fit, остальные — aspect fill.
- `cornerRadius` описывает радиус исходного элемента и во время открытия плавно уменьшается до нуля.
- При удалении composable из композиции источник автоматически снимается с регистрации.

### `SharedFrameHost`

Host отображает обычный `content`, scrim и активный `detailContent`. Лямбда деталей выполняется в `SharedFrameDetailScope` и предоставляет:

- `key` — ключ активного источника;
- `painter` — тот же painter, который был зарегистрирован у источника;
- `Modifier.sharedFrameDetailHero()` — отметку целевой области изображения.

В `detailContent` должна быть ровно одна актуальная hero-область. Она обязана получить ненулевой размер после layout.

### Управление

```kotlin
controller.open(key) // открыть зарегистрированный источник
controller.close()   // закрыть экран деталей
```

Host автоматически перехватывает системную кнопку Back, пока переход видим. Back запускает закрытие только в фазе `Idle`.

## Жест закрытия

Жест доступен на всём detail-контенте в фазе `Idle`. Экран следует за пальцем по обеим осям, а его масштаб зависит от смещения по X. Закрытие завершается только движением вправо: когда пройдена настроенная доля ширины либо достигнута минимальная скорость. В остальных случаях экран анимируется обратно.

## Практические ограничения

- Не удаляйте `SharedFrameHost` во время активной анимации.
- Для корректного обратного перехода источник должен оставаться зарегистрированным и находиться в границах host. Если он исчез или прокручен за пределы host, закрытие выполняется через fade.
- Размер и содержимое `Painter` не следует менять посреди перехода.
- Host предполагает одну активную shared-frame анимацию; повторные `open`/`close` в неподходящей фазе возвращают `false`.
