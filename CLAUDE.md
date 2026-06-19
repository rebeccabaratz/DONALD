# АНГЕНТ — Claude Code Project Guide

## Что это

Android-приложение: голосовой учитель английского. Читает книгу «Том Сойер» фразами и ведёт живой разговор. Работает через OpenAI Realtime API (WebSocket, PCM 16-bit 24kHz). Телефон — Samsung Galaxy S21, подключается к машине BYD через Android Auto.

Два режима (AgentMode):
- **BOOK_READING** — Ангент читает фразы из Тома Сойера по-английски, пользователь повторяет, Ангент оценивает (только правильность слов, не акцент)
- **CONVERSATION** — Ангент общается **на русском** (пользователь говорит по-русски или на иврите), даёт упражнения на английском; 1-2 коротких предложения

---

## Архитектура — ключевые файлы

| Файл | Роль |
|------|------|
| `VoiceAgentViewModel.kt` | Главная логика, state machine (IDLE → PROCESSING → SPEAKING → LISTENING) |
| `OpenAiRealtimeClient.kt` | WebSocket клиент OpenAI Realtime API |
| `AudioPlayer.kt` | Воспроизведение через AudioTrack (Android Auto) |
| `VoiceRecorder.kt` | Запись mic, адаптивный порог шума |
| `MainActivity.kt` | Compose UI, `TikhonovAvatar` composable |
| `CostTracker.kt` | Логирование стоимости API сессии |
| `tom_sawyer.txt` | Текст книги (в корне проекта) |

---

## Ключевые решения (почему так, а не иначе)

**Нет реконнекта между фразами книги**
В BOOK_READING после каждой фразы история очищается через `conversation.item.delete` (не переподключением). Это убрало паузу 1-2 сек между фразами. Реализовано в `OpenAiRealtimeClient.clearConversationHistory()`.

**Замер шума во время "думания" AI**
`VoiceRecorder.measureNoiseFloor()` запускается пока AI генерирует ответ (PROCESSING state), а не перед записью. Это убрало 400мс задержки перед каждым прослушиванием. Кешируется в `VoiceAgentViewModel.cachedNoiseFloor`.

**AudioFocusRequest для Android Auto**
Без явного запроса audio focus Android Auto блокирует AudioTrack беззвучно. Реализовано в `AudioPlayer.startStreamingPlayback()` — запрашивает `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.

**Аватар Тихонова: статичный + анимированный WebP**
`TikhonovAvatar` в `MainActivity.kt` использует Coil для показа двух изображений:
- `res/drawable/tikhonov_silent.png` — первый кадр клипа (рот закрыт), показывается в IDLE/LISTENING/PROCESSING
- `res/raw/tikhonov_talking.webp` — анимированный boomerang-клип из фильма «17 мгновений весны», показывается в SPEAKING
- Coil (`coil-compose` + `coil-gif`) обрабатывает анимацию; на API 28+ используется `ImageDecoderDecoder`
- Файлы сгенерированы FACE agent из `C:\FACE\output\clip\`

**AudioTrack.write() на IO dispatcher**
`AudioPlayer.writePcmChunk()` — `suspend fun`, блокирующий `audioTrack.write()` вызывается внутри `withContext(Dispatchers.IO)`. Это позволяет UI-анимации (аватар) работать во время воспроизведения.

---

## Текущее состояние

- **Ветка**: `fix` (новые изменения; `master` обновлён PR #2)
- **master**: стабильная версия с мультяшным аватаром, починенным AudioFocus, CI
- **fix**: переименование в Ангент + аватар Тихонова — **не тестировалось на устройстве**
- **Все тесты**: проходят (нужно проверить после Coil)
- **Аудио в машине**: починено (AudioFocus), но ещё не протестировано в реальной машине

---

## FACE Agent — генератор портрета и клипа аватара

Отдельный проект в `C:\FACE\` — генерирует медиафайлы для аватара.

**Извлечь клип из видео (использовалось для Тихонова):**
```bash
cd C:\FACE
python face_agent.py --extract-at "URL" --start 43:37 --clip-duration 3 --crop 160,5,239,310
```

**Что делает `--extract-at`:**
1. yt-dlp скачивает сегмент видео
2. GPT-4o определяет координаты лица (или использует `--crop x,y,w,h`)
3. ffmpeg: crop + blur фон + boomerang loop
4. Результат: `output/clip/clip_*.gif` и `clip_*.webp`

**Готовые файлы Тихонова:**
- `C:\FACE\output\clip\clip_43-37.webp` — анимированный WebP (уже в приложении)
- `C:\FACE\output\clip\tikhonov_silent.png` — первый кадр (уже в приложении)

**API ключи:**
- `OAI_KEY` — в переменной окружения (OpenAI, начинается с `sk-proj-`)
- Ключ OpenAI также вводится в настройках приложения на телефоне (SharedPreferences)
- `GEMINI_API_KEY` — в `C:\DONALD\.env` (используется CI)

---

## Git workflow

```bash
# Работаем в ветке fix
git checkout fix
git add <файлы>
git commit -m "тип: описание"
git push origin fix
# CI собирает APK автоматически при каждом пуше в fix
```

CI собирает APK автоматически при каждом пуше в `fix`. Скачать: GitHub → Actions → последний ран → Artifacts → `app-debug`.

---

## Правила после каждого коммита

**1. Обновить CLAUDE.md если нужно**
Обновлять если изменилась архитектура, ключевое решение, зависимость, файл, статус или агент.
Включить изменения CLAUDE.md в тот же коммит (или сразу следующий).

**2. Проверить билд — обязательно, не опционально**
После каждого коммита немедленно запустить:
```
gh run list --branch fix --limit 1
gh run watch <run_id> --exit-status
```
Дождаться результата. Не переходить к следующему шагу пока билд не завершился.

**3. Если билд упал — чинить, не пушить**
Разобраться в причине, исправить, закоммитить. Повторить с шага 2.

**4. Если билд прошёл — спросить разрешение на push**
Написать пользователю: **"Билд прошёл — пушить?"** и ждать подтверждения.
Не пушить молча. Push = изменение в shared репозитории, это решение пользователя.

---

## Что может понадобиться в следующей сессии

- Проверить аватар Тихонова на устройстве — анимация WebP, переключение silent/talking
- Проверить аудио в машине (Android Auto) — если не работает, смотреть `AudioPlayer.kt`
- Влить PR (`fix` → `master`) когда аватар Тихонова протестирован
