# aptX Max

`aptX Max` - небольшое Android-приложение для выбора лучшего доступного A2DP-кодека на подключенных Bluetooth-наушниках через Shizuku.

Приложение не привязано к Xiaomi Buds 5 Pro. Оно читает `BluetoothCodecStatus`, берет список `mCodecsSelectableCapabilities` для каждого подключенного A2DP-устройства и выбирает лучший реально доступный вариант:

1. явный Lossless-кодек, если прошивка показывает его как отдельный codec/capability;
2. aptX Adaptive;
3. LHDC/LHAC;
4. LDAC;
5. aptX HD;
6. aptX;
7. LC3/Opus;
8. AAC/SBC.

Важно: приложение не подделывает vendor-биты и не создает `aptX Lossless` из воздуха. Если прошивка или наушники не показывают Lossless как доступный режим, будет выбран лучший режим из реально selectable-списка. На проверенном Meizu 21 + Xiaomi Buds 5 Pro прошивка показала максимум `aptX Adaptive / 96 kHz / 24-bit / stereo`.

## Установка

Готовый APK лежит в `releases/aptx-max.apk`.

1. Установи Shizuku.
2. Запусти Shizuku через ADB или root.
3. Установи `releases/aptx-max.apk`.
4. Открой `aptX Max`, выдай разрешение Shizuku и нажми `Включить лучший кодек`.

## Сборка

Нужны:

- Windows;
- Android SDK Platform `android-36.1` или новее;
- Android SDK Build Tools с `aapt2`, `d8`, `zipalign`, `apksigner`;
- JDK, например Android Studio JBR.

Сборка:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Результаты:

- `build/aptx-max.apk`;
- `build/set-a2dp-codec.dex`;
- обновленный `releases/aptx-max.apk`.

APK подписывается debug-ключом из `signing/debug.keystore`. Этот ключ лежит в репозитории только для воспроизводимых тестовых сборок и обновлений поверх APK из `releases/`; для production-релиза нужен отдельный приватный ключ.

## ADB helper

Для разового запуска без APK можно отправить helper на телефон:

```powershell
adb push build\set-a2dp-codec.dex /data/local/tmp/set-a2dp-codec.dex
adb shell CLASSPATH=/data/local/tmp/set-a2dp-codec.dex app_process /system/bin SetA2dpCodec
```

Скрипт для Android shell:

```sh
/data/local/tmp/set_best_a2dp_codec.sh
```

## Проверка на устройстве

Полезные команды:

```sh
settings get global bluetooth_a2dp_codec
settings get global bluetooth_a2dp_codec_sample_rate
settings get global bluetooth_a2dp_codec_bits_per_sample
dumpsys bluetooth_manager | grep -i -E 'Current Codec|mCodecConfig|mCodecsSelectableCapabilities'
```
