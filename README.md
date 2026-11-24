# НаноПеро ✦

Офлайн-ИИ для Android: чат, обработка текста, сводка уведомлений, голосовой дневник и клавиатура с ИИ. Всё работает прямо на телефоне, без интернета и облака.

## Возможности

- **Чат** с локальными моделями: роли, документы (PDF, Word, TXT), фото (Gemma 4), чтение вслух, напоминания, режим «Думать перед ответом».
- **Модели**: Gemini Nano (через AICore), Gemma 4 E2B/E4B (LiteRT-LM), Qwen 3 / 3.5 в формате GGUF (llama.cpp). Каталог с автоматической загрузкой, тест скорости и общая папка моделей с приложением «Персона» (`Загрузки/AI-Models`).
- **Клавиатура** (ru / uk / en): автоисправление, набор свайпом, эмодзи, буфер обмена, заготовки, панель редактирования, перевод на лету, ИИ-продолжение фразы, темы оформления.
- **Сводка уведомлений** из мессенджеров с защитой от мошеннических SMS, «Прочитать позже», голосовой дневник со статистикой, карточки для повторения с экспортом в Anki.

## Автоисправление

Работает как шумный канал (noisy channel). Каждый вариант, включая само набранное слово, получает оценку из трёх частей:

- частота слова;
- гауссова модель касаний: насколько точки нажатия похожи на это слово;
- биграммная языковая модель: насколько слово ожидается после предыдущего.

Исправление ставится, только если апостериорная вероятность лучшего варианта выше порога. Параметры подобраны на отложенных предложениях с имитацией касаний: `AutocorrectBench`, `SentenceBench`.

## Сборка

Требуется Android SDK (compileSdk 36) и JDK 17+:

```
./gradlew assembleRelease
```

Готовая нативная библиотека для llama.cpp лежит в `app/src/main/jniLibs/arm64-v8a/libnanollama.so`. Пересобрать её можно из `native/`: нужны Android NDK и исходники [llama.cpp](https://github.com/ggml-org/llama.cpp) в соседней папке `../llama.cpp`.

```
cmake -S native -B native/build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
ninja -C native/build nanollama
```

Словари и языковые модели клавиатуры (`app/src/main/assets/dict`) собираются скриптами из `tools/`.

## Структура

| Путь | Что там |
|---|---|
| `app/src/main/java/com/nanopero/app/` | Код приложения (Kotlin, Jetpack Compose, Material 3 Expressive) |
| `Keyboard.kt`, `KbInput.kt`, `Dict.kt`, `LangModel.kt` | Клавиатура и автоисправление |
| `Engines.kt`, `LlamaNative.kt` | Движки ИИ: Gemini Nano, MediaPipe, LiteRT-LM, llama.cpp |
| `native/` | JNI-обёртка над llama.cpp |
| `tools/` | Сборка биграмм и языковых моделей |
| `app/src/test/` | Тесты и замеры качества автоисправления |

## Лицензии данных

Словари: FrequencyWords (CC BY-SA 4.0). Эмодзи: Unicode CLDR. Языковые модели клавиатуры обучены на Википедии (CC BY-SA), Universal Dependencies и BrUK.
