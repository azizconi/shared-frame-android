# shared-frame-views

Реализация shared frame перехода для классического Android View toolkit. `SharedFrameViewController` добавляет в переданный `FrameLayout` затемнение и overlay, переносит в overlay экран деталей и анимирует `ImageView` между исходной и целевой геометрией.

## Подключение

```kotlin
dependencies {
    implementation(project(":shared-frame-views"))
}
```

Модуль транзитивно предоставляет типы из `shared-frame-core`, включая `SharedFrameConfig` и `SharedFramePhase`.

## Разметка host

Корневой контейнер экрана должен быть `FrameLayout`. Обычный контент размещается внутри него до создания контроллера:

```xml
<FrameLayout
    android:id="@+id/shared_frame_host"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- Список, сетка или другой исходный экран -->

</FrameLayout>
```

Контроллер добавляет scrim и overlay последними дочерними элементами, то есть поверх существующего контента.

## Минимальный пример

```kotlin
class GalleryActivity : AppCompatActivity() {
    private lateinit var controller: SharedFrameViewController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val host = findViewById<FrameLayout>(R.id.shared_frame_host)
        val source = findViewById<ImageView>(R.id.photo)
        controller = SharedFrameViewController(host)

        source.setOnClickListener {
            val detail = layoutInflater.inflate(
                R.layout.view_photo_detail,
                host,
                false,
            )
            val detailHero = detail.findViewById<ImageView>(R.id.detail_hero)

            controller.open(
                SharedFrameViewRequest(
                    key = "photo-42",
                    sourceHero = source,
                    drawable = requireNotNull(source.drawable),
                    sourceRadiusPx = 16.dpToPx(resources.displayMetrics.density),
                    detailRoot = detail,
                    detailHero = detailHero,
                    onShown = { /* экран полностью открыт */ },
                    onHidden = { /* экран полностью закрыт */ },
                )
            )

            detail.findViewById<View>(R.id.close_button)
                .setOnClickListener { controller.close() }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (!controller.handleBack()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        controller.dispose()
        super.onDestroy()
    }
}

private fun Int.dpToPx(density: Float): Float = this * density
```

`detailRoot` следует создавать без parent (`inflate(..., host, false)`). При открытии контроллер сам переносит его в overlay и растягивает на весь host.

## `SharedFrameViewRequest`

| Поле | Назначение |
| --- | --- |
| `key` | Идентификатор запроса; полезен вызывающему коду, внутри текущей реализации не участвует в поиске source |
| `sourceHero` | Прикреплённый и уже измеренный исходный `ImageView` |
| `drawable` | Изображение для detail hero; контроллер пытается создать отдельный экземпляр через `constantState` |
| `sourceRadiusPx` | Радиус углов исходного изображения в пикселях |
| `detailRoot` | Полноэкранное содержимое экрана деталей |
| `detailHero` | Целевой `ImageView` внутри `detailRoot` |
| `onShown` | Вызывается после завершения открытия |
| `onHidden` | Вызывается после завершения закрытия и восстановления source |

На время перехода `detailHero` переключается в `ImageView.ScaleType.MATRIX`, а после завершения — в `CENTER_CROP`. Исходный `sourceHero` скрывается через `alpha = 0` и восстанавливается при закрытии или `dispose()`.

## Управление и lifecycle

```kotlin
controller.open(request)
controller.close()
controller.handleBack()
controller.dispose()
```

- `open` возвращает `false`, если `sourceHero` не прикреплён к окну или другой переход уже активен.
- `close` работает из фаз `Idle` и `Dragging`.
- `handleBack` закрывает detail в фазе `Idle`; в других видимых фазах возвращает `true`, показывая, что Back поглощён.
- `dispose` необходимо вызвать при уничтожении владельца: он отменяет pending callbacks и анимацию, освобождает `VelocityTracker`, восстанавливает source и удаляет служебные View из host.
- `phase` можно читать для диагностики и синхронизации внешнего UI.

## Жест закрытия

Overlay отслеживает горизонтальный жест, когда горизонтальное смещение заметно больше вертикального (`1.15×`) и превышен системный touch slop. Закрытие завершается свайпом вправо по расстоянию или скорости; вертикальный жест отклоняется. Если порог не достигнут, detail возвращается в раскрытое положение.

## Fallback и ограничения

- `sourceHero`, host и `detailHero` должны быть измерены; вычисление геометрии начинается на ближайшем pre-draw.
- Для бесшовной интерполяции содержимого размеры drawable у source и detail должны совпадать. Если изображение или геометрия недоступны при открытии, контроллер безопасно показывает detail сразу, без shared-frame анимации; при закрытии используется fade.
- Если source отсоединён перед закрытием, используется fade-анимация.
- Detail root фактически передаётся во владение контроллеру: если он уже имеет parent, контроллер удалит его оттуда.
- Один экземпляр контроллера обслуживает один активный переход. Вызовы, несовместимые с текущей фазой, возвращают `false`.
- Контроллер изменяет `alpha`, `scaleType` и `imageMatrix` hero View; не меняйте эти свойства параллельно во время перехода.
