# Возврат экрана проекции CarLife после камер и задней передачи

Дата: 23 ноября 2026 года.

Связанные компоненты: `CarLifeSession.kt`, `CarLifeService.kt`, `VideoFeed.kt`, `CarDisplay.kt`.

Логи: `.codex-attachments/baidu-20261122/ft.log` (строки 21540–21820).

---

## 1. Контекст проблемы

При переключении на камеры автомобиля (камера заднего вида при включении задней передачи R, круговой обзор 360 по кнопке или датчикам парковки) головное устройство (ГУ) временно скрывает проекцию CarLife. После выключения камер экран CarLife на ГУ не восстанавливался — магнитола оставалась на штатном рабочем столе Toyota / черном экране, а в логах FT появлялась повторяющаяся ошибка:
`W CarLife: the car did not confirm projection return after leaving the gear`.

---

## 2. Анализ первопричины (Root Cause)

По детальным логам протокола CarLife из `ft.log` восстановлена точная хронология:

1. **Вход в заднюю передачу / камеры:**
   - Автомобиль сообщает `car gear 5` (Reverse).
   - FT отправляет `GO_TO_DESKTOP` (если включено действие в настройках).
   - ГУ отправляет команду `CMD_VIDEO_ENCODER_PAUSE` (`0x0001800A`).
   - `CarLifeSession` вызывает `feed.pause()`, переводя `VideoFeed` в режим `Mode.PAUSED`.
   - В режиме `Mode.PAUSED` видеопоток перестаёт отправлять видеокадры и шлёт исключительно пустые heartbeat-пакеты (0 байт).

2. **Выход из задней передачи (возврат в Drive / передачу 3–4):**
   - FT отправляет запрос магнитоле на возврат CarLife на передний план: `TX REQUEST_GO_TO_FOREGROUND` (`0x00010038`).
   - ГУ соглашается и через 14 мс присылает команду `RX GO_TO_FOREGROUND` (`0x00018025`).

3. **Критический дефект в `CarLifeSession.kt`:**
   В коде присутствовала заглушка:
   ```kotlin
   CarLifeProtocol.CMD_GO_TO_FOREGROUND -> DiagLog.i(tag, "head unit asked FT to come forward, it already draws on the car")
   ```
   FT ошибочно считал, что он «уже рисует на машине», и **не предпринимал никаких действий**:
   - `feed.start()` не вызывался (`VideoFeed` оставался замороженным в `Mode.PAUSED`).
   - Кадры с кодировщика (`CarDisplay` / `SurfaceEncoder`) продолжали отбрасываться.
   - Запрос ключевого кадра (`onNeedKeyframe`) и пробуждение дисплея (`onVideoBack`) не выполнялись.
   - Подтверждение перехода на передний план (`CMD_FOREGROUND`, `0x0001001B`) не отправлялось ГУ.
   - Коллбэк `onProjectionShown` не вызывался, из-за чего `CarLifeService` считал, что ГУ проигнорировало запрос, делал 3 безуспешные попытки с интервалом в 1 секунду и логировал предупреждение об отказе возврата.

4. **Зависимость от настройки `gearReverse` в `CarLifeService.kt`:**
   Флаг `gearHidProjection = true` выставлялся только в том случае, если пользователь явно настроил действие «Экран авто» (`GearAction.CAR_SCREEN`). Если действие было выключено (`NONE`), при выходе из задней передачи попытка возврата вообще не стартовала, несмотря на то, что камеры в автомобиле переключаются аппаратно.

5. **Гонка состояний при задержке `GEAR_HOLD_MS` (700 мс):**
   При переходе R → D задержка 700 мс предотвращает дёргание экрана во время манёвров. Раннее подтверждение от ГУ сбрасывало `gearHidProjection`, но после задержки проверка `wasReverse` в `applyGear` всё равно запускала запрос возврата.

---

## 3. Что исправлено

### 1. `CarLifeSession.kt`
- Добавлена полноценная обработка `CMD_GO_TO_FOREGROUND` (`0x00018025`):
  - Вызов `feed.start()` для перевода видеопотока из `Mode.PAUSED` в `Mode.RESUMING`. Если кодировщик ещё не инициализирован (`Mode.OFF`), обработчик завершится. Штатное подтверждение отправится при `CMD_VIDEO_ENCODER_INIT`.
  - Установка `projecting = true` и состояния `State.Projecting`.
  - Запуск видеотаймера `startVideoTimer()`.
  - Вызов `onVideoBack?.invoke()` (`carDisplay.wake()`) при возобновлении после паузы и запрос ключевого кадра через `onNeedKeyframe?.invoke()`.
  - Отправка ГУ подтверждения `CMD_FOREGROUND` (`0x0001001B`).
  - Вызов `onProjectionShown?.invoke()` для оповещения сервиса об успешном возврате.
- В обработчик `CMD_VEHICLE_FOREGROUND` (`0x00018097`) добавлено возобновление видеопотока, если он находился в режиме паузы.

### 2. `CarLifeService.kt`
- Включение задней передачи (`gear == GEAR_REVERSE`) теперь всегда выставляет `gearHidProjection = true`, независимо от того, выбрано ли в настройках действие `CAR_SCREEN` или `NONE` (учитывая аппаратное включение камер авто).
- При выходе из задней передачи `requestGearProjectionReturn` запускается, пока `gearHidProjection` выставлен, в том числе при `gearReverse = NONE`.
- Подтверждение `onGearProjectionShown` во время задержки сбрасывает `gearHidProjection`. Отложенный `applyGear` применяет передачу и настройки звука без запроса возврата.

### 3. Тестирование
Создан набор модульных тестов в `app/src/test/java/app/ft/carlife/CarLifeProjectionReturnTest.kt`:
- `goToForegroundResumesPausedVideoFeedAndConfirmsToCar` проверяет возобновление `VideoFeed` из `PAUSED`, запрос ключевого кадра, отправку нового `CMD_FOREGROUND` в ответ на команду и вызов `onProjectionShown`.
- `goToForegroundBeforeVideoInitKeepsProjectionOff` проверяет состояние сеанса и отсутствие подтверждения до инициализации кодировщика.
- `vehicleForegroundResumesPausedVideoFeed`: проверка восстановления видео при получении `CMD_VEHICLE_FOREGROUND`.
- `leavingReverseGearRequestsForegroundEvenWhenActionIsNone`: проверка запроса возврата проекции при выходе из задней передачи при `gearReverse = NONE`.
- `projectionShownDuringGearHoldSkipsForegroundRequest` проверяет применение передачи и отсутствие повторного запроса после раннего подтверждения.

---

## 4. Памятка для Codex и будущих ревью

> [!IMPORTANT]
> **Архитектурные правила для работы с CarLife проекцией и камерами авто:**
> 1. **Семантика команд переднего плана:**
>    - `CMD_REQUEST_GO_TO_FOREGROUND` (`0x00010038`, MD → HU) — телефон просит ГУ показать CarLife.
>    - `CMD_GO_TO_FOREGROUND` (`0x00018025`, HU → MD) — команда, которую Toyota отправила при выходе из режима камер. В FT после инициализации кодировщика она возобновляет приостановленный поток, запрашивает ключевой кадр и отправляет `CMD_FOREGROUND` (`0x0001001B`). [Таблица команд SDK Baidu](https://github.com/fgrcwp/apollo-DuerOS/blob/master/CarLife-Android-Vehicle-V2.0/carlife-sdk/src/main/java/com/baidu/carlife/sdk/internal/protocol/ServiceTypes.kt#L93)
>    - `CMD_VEHICLE_FOREGROUND` (`0x00018097`, HU → MD) — FT обрабатывает эту команду как подтверждение видимости окна CarLife.
> 2. **Состояния `VideoFeed`:**
>    - После `CMD_VIDEO_ENCODER_PAUSE` видеопоток находится в `Mode.PAUSED`. Любые кадры от `CarDisplay` в этом режиме дропаются.
>    - Выход из `Mode.PAUSED` возможен **только** через `feed.start()`. Если не вызвать `feed.start()`, видео никогда не пойдёт на экран авто.
>    - При переходе `PAUSED` → `RESUMING` FT запрашивает ключевой кадр, чтобы ускорить восстановление изображения.
> 3. **Аппаратные камеры автомобиля:**
>    - ГУ может переключиться на камеры без участия CarLife. FT проверяет необходимость возврата после выхода из R даже при `gearReverse = NONE`. Если ГУ уже подтвердило возврат во время задержки, дополнительный запрос не нужен.
