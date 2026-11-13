# Проверка интеграции ICCOA Quick Auth и GPS/recovery

Промпт для независимого ревью Gemini. Изменения объединяют
[PR #8](https://github.com/mykolaq/ft-app/pull/8) (`bc2f06e`) и
[PR #9](https://github.com/mykolaq/ft-app/pull/9) (`79244f9`, включая обновления после `05d1454`) поверх
`092eb4b` из `codex/adding-iccoa`. Проверяйте итоговый код, включая исправления
интеграции, а не только исходные PR. Диапазон для просмотра: `092eb4b..main`.

## Задание

Найдите воспроизводимые ошибки в аутентификации, автоматическом переподключении
и свежести GPS. Для каждого замечания укажите приоритет, файл/строку, конкретную
последовательность событий и предлагаемое исправление. Отделяйте подтверждённые
ошибки от вопросов, требующих проверки на головном устройстве. Не запрашивайте и
не открывайте keystore, PIN, токены, файлы авторизации или сохранённые пароли.

## Код для просмотра

- `IccoaSession.kt`, `IccoaCloseReason.kt`, `IccoaInboundFrame.kt`,
  `IccoaLaneServer.kt`, `IccoaTrustedCommitGate.kt`: классификация завершения,
  порядок callbacks и момент фиксации доверенного ключа.
- `IccoaAuth.kt`, `IccoaTrustedCarKey.kt`, `IccoaQuickTrustPolicy.kt`,
  `IccoaClientProfile.kt`: проверка подписи OEM, привязка ключа, отсутствие replay
  старого Normal PIN, запрет замены ключа при несовпадении identity.
- `IccoaService.kt`, `IccoaDropRecovery.kt`, `IccoaNormalFallback.kt`,
  `IccoaReceiverCandidate.kt`, `IccoaRememberedCar.kt`, `IccoaDiscovery.kt`:
  переход Quick → Normal, ротация BLE MAC, таймеры, generation и бюджет попыток.
- `IccoaMediaTransport.kt`, `IccoaRtsp.kt`, `IccoaInputReader.kt`:
  отличие socket EOF/timeout от ошибок формата, GCM и локального кодека.
- `IccoaGpsWire.kt`, `VehicleTelemetry.kt`, `CarWidgets.kt`, `AaCarGps.kt`:
  единицы скорости/курса, свежесть координат при поступлении GearInfo.
- Соответствующие тесты в `app/src/test/java/app/ft/iccoa` и
  `app/src/test/java/app/ft/core/VehicleTelemetryTest.kt`.

## Обязательные сценарии

1. Quick с pinned identity: корректная подпись и encrypted CONTROL → соединение
   без отправки старого PIN. Другой ключ/signature → остановка, доверенный ключ
   сохранён, автоматического перехода на PIN или повторов нет.
2. Quick result 1/2 → новый BLE scan, выбор свежего адреса того же кандидата,
   ровно один переход в Normal с обязательным текущим PIN. При ротации MAC старый
   адрес не должен побеждать свежий token. BLE token — публичная подсказка для
   выбора, не доказательство подлинности.
3. AuthConfirm + TCP accept + EOF без encrypted CONTROL → ключ не фиксируется.
   Plain frame, invalid GCM и category=1 на auxiliary lane не подтверждают CONTROL.
   Корректный encrypted CONTROL до/после отметки отправки AuthConfirm → один commit.
4. CONTROL EOF, Wi-Fi loss, зависшая запись и неожиданный method 23 → ограниченный
   transport recovery. Discovery failure/not-found расходуют тот же бюджет.
   Четвёртой автоматической попытки нет; PIN prompt останавливает цепочку.
5. Invalid CRC, frame length, GCM tag или malformed RTSP → terminal failure.
   `finally/onDisconnected` не должны раньше отправлять ложный transport failure.
6. Connect, ConnectNormal, Scan, Disconnect, Forget, Stop во время отложенного retry
   отменяют старые таймеры и callbacks. Ручной Disconnect не запускает auto-connect
   через синхронный SCAN_COMPLETE. Forget работает при отключённом auto-connect,
   другом протоколе и без Nearby permission. Поздний trust callback после Forget
   не восстанавливает ключ: запись и остановка сериализованы на main looper.
   После terminal failure доступен ручной выбор.
7. EOF/timeout после начала отправки auth request остаётся неоднозначным: нет
   автоматического повторного auth request или удаления pinned identity.
8. GearInfo после устаревшего GPS не обновляет `positionAtMs`, не возвращает
   устаревшую скорость в виджет и не передаёт устаревшую позицию в Android Auto.

## Проверка на автомобиле

Unit-тесты и сборка не подтверждают совместимость с реальным XA60. На устройстве
нужны Normal pairing → повторный Quick, отказ Quick result 1/2, смена BLE MAC,
отключение Wi-Fi/CONTROL, отмена владельцем и исчерпание трёх попыток.

Единицы GPS основаны на OEM-производителе данных через Android Location:
скорость трактуется как floor(m/s) с переводом в km/h, курс — floor(degrees).
Сравните с реальными показаниями при остановке, движении и изменении курса.
`timestampRaw` пока диагностический: повтор ранее полученного fix получает новое
локальное время приёма. Проверьте, позволяет ли OEM timestamp отличать новое
измерение от повторной доставки и out-of-order fix; не придумывайте его единицы
без данных. Это известный вопрос для проверки, а не доказанная новая регрессия.

## Команды проверки

```powershell
.\.build-tools\build-isolated.ps1 -Tasks ':app:testUnsignedTransitUnitTest', ':app:assembleUnsignedTransit', ':app:lintUnsignedTransit'
git diff --check 092eb4b..main
```

Перед отправкой замечаний сверьте их с существующими regression tests. Результат
ревью должен содержать только обоснованные findings и отдельно — оставшиеся
вопросы для аппаратной проверки.

## Известные замечания lint

Полный Android lint в исходном состоянии интеграции нашёл 48 ошибок. Два
сравнения результата `BluetoothGatt.writeCharacteristic` с `0` заменены на
`BluetoothStatusCodes.SUCCESS`. Остальные замечания относятся к коду и ресурсам,
не изменённым относительно `092eb4b`: 45 `MissingTranslation` в `values/iccoa.xml`
для `ar`, `kmr`, `ckb` и один `MissingPermission` в `AudioCapture.buildRecord`.
Оба production caller проверяют `RECORD_AUDIO`, а `AudioCapture.start` обрабатывает
отказ при создании и запуске записи. Эти замечания не скрыты baseline или
отключением проверок. Проверяйте окончательный отчёт lint отдельно от результатов
unit-тестов и сборки APK.

Итоговая локальная проверка 2026-11-03: 675 unit-тестов в 103 наборах, ошибок,
падений и пропусков нет; `assembleUnsignedTransit` собрал неподписанный APK.
Повторный `lintUnsignedTransit` завершился с указанными 46 существующими ошибками
(45 переводов + один `MissingPermission`), 124 предупреждениями и двумя подсказками.
`git diff --check 092eb4b..HEAD` прошёл. Ревью Gemini ещё не выполнялось.
