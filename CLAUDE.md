# DONALD — Claude Code Project Guide

## Что это

Android-приложение: голосовой учитель английского. Читает книгу «Том Сойер» фразами и ведёт живой разговор. Работает через OpenAI Realtime API (WebSocket, PCM 16-bit 24kHz). Телефон — Samsung Galaxy S21, подключается к машине BYD через Android Auto.

Два режима (AgentMode):
- **BOOK_READING** — Дональд читает фразы из Тома Сойера по-английски, пользователь повторяет, Дональд оценивает (только правильность слов, не акцент)
- **CONVERSATION** — Дональд общается **на русском** (пользователь говорит по-русски или на иврите), даёт упражнения на английском; 1-2 коротких предложения

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
| `app/src/main/res/raw/tom_sawyer.txt` | Текст книги |

---

## Ключевые решения (почему так, а не иначе)

**Нет реконнекта между фразами книги**
В BOOK_READING после каждой фразы история очищается через `conversation.item.delete` (не переподключением). Это убрало паузу 1-2 сек между фразами. Реализовано в `OpenAiRealtimeClient.clearConversationHistory()`.

**Замер шума во время "думания" AI**
`VoiceRecorder.measureNoiseFloor()` запускается пока AI генерирует ответ (PROCESSING state), а не перед записью. Это убрало 400мс задержки перед каждым прослушиванием. Кешируется в `VoiceAgentViewModel.cachedNoiseFloor`.

**AudioFocusRequest для Android Auto**
Без явного запроса audio focus Android Auto блокирует AudioTrack беззвучно. Реализовано в `AudioPlayer.startStreamingPlayback()` — запрашивает `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.

**Аватар: фото + Canvas-рот**
`TikhonovAvatar` в `MainActivity.kt` показывает фотопортрет (`R.drawable.tikhonov_portrait`) и рисует поверх анимированный рот Canvas-кривыми. Рот открывается по амплитуде `outputAmplitude` (делитель 1200). Координаты рта: centerY=67.5%, width=18% от высоты/ширины composable.

---

## Текущее состояние

- **Ветка**: `fix` (PR открыт против `master`)
- **Все тесты**: проходят
- **Аудио в машине**: починено (AudioFocus), но ещё не протестировано в реальной машине
- **Аватар**: фотопортрет вставлен, анимация рта работает — точность координат рта не проверена на устройстве

---

## FACE Agent — генератор портрета аватара

Отдельный проект в `C:\FACE\` — генерирует фотопортрет для аватара.

**Как запустить:**
```bash
cd C:\FACE
PYTHONUTF8=1 OAI_KEY=$OAI_KEY python face_agent.py --reference tikhonov.jpg --rounds 2
```

**Что делает:**
1. GPT-4o анализирует `tikhonov.jpg` → точные пропорции лица
2. `gpt-image-1` генерирует N портретов с этими пропорциями
3. Результаты в `C:\FACE\output\`

**После генерации:**
- Выбрать лучший портрет → сохранить как `output\chosen.png`
- Запустить `python face_agent.py --report` → получить координаты рта
- Скопировать в DONALD: `cp output/chosen.png C:\DONALD\app\src\main\res\drawable\tikhonov_portrait.png`
- Обновить координаты в `TikhonovAvatar` если нужно

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
# PR уже открыт на GitHub: fix → master
```

CI собирает APK автоматически при каждом пуше в `fix`. Скачать: GitHub → Actions → последний ран → Artifacts → `app-debug`.

---

## Что может понадобиться в следующей сессии

- Проверить аудио в машине (Android Auto) — если не работает, смотреть `AudioPlayer.kt`
- Подстроить координаты рта аватара если они не совпадают с реальным фото — константы в `TikhonovAvatar` в `MainActivity.kt` (строки `mCY`, `mW`)
- Сгенерировать новый портрет если текущий не понравился — запустить FACE agent
- Влить PR (`fix` → `master`) когда всё протестировано
