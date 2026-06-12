"""
DONALD — conversation integration test.
Simulates a 5-turn dialogue using text input (mirrors what the Android app does with audio).

Flow:
  1. AI greeting (no user turn — matches speakFirstIntro/requestGreeting)
  2. User: "שלום, מה נשמע?"         → AI responds in Russian
  3. User: "מה עשיתה היום?"          → AI responds
  4. User: "בא נקרא ספר על טום סוייר" → AI switches to book reading [SWITCH_TO_READING]
  5. User: "Tom!"                     → AI evaluates ([ADVANCE_BOOK] or [REPEAT_BOOK])

Checks:
  - Each turn produces audio (not silence)
  - Turn 4 transcript contains [SWITCH_TO_READING]
  - Turn 5 transcript contains [ADVANCE_BOOK] or [REPEAT_BOOK]

Usage:
    python test_conversation.py sk-...
    set OAI_KEY=sk-... && python test_conversation.py
    export OAI_KEY=sk-... && python test_conversation.py
"""

import asyncio
import base64
import json
import os
import sys
import time

# Fix UTF-8 output on Windows terminals
if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2"

# Matches VoiceAgentViewModel.tomSawyerPhrases (first 4)
TOM_PHRASES = ["Tom!", "No answer.", "Tom!", "No answer."]
TOM_TRANSLATIONS = ["Том!", "Никакого ответа.", "Том!", "Никакого ответа."]

# Must match VoiceAgentViewModel.buildSystemPrompt() exactly
SYSTEM_PROMPT = """Ты — Дональд, дружелюбный голосовой помощник и преподаватель английского языка.

ПРАВИЛА:
1. Пользователь говорит на русском или иврите. Отвечай только на русском языке.
2. Будь тёплым и кратким — 1-3 предложения, не больше.
3. Если нет предыдущих сообщений в разговоре — поприветствуй коротко и скажи, что готов помочь с английским.

РЕЖИМ ЧТЕНИЯ "ТОМ СОЙЕР":
Когда пользователь просит читать книгу — переходи в режим чтения, добавь [SWITCH_TO_READING] в конце.

В РЕЖИМЕ ЧТЕНИЯ:
- Произнеси текущую английскую фразу вслух.
- Оценивай только ПРАВИЛЬНОСТЬ СЛОВ, не произношение и не акцент.
- Акцент — это нормально. Пользователь НЕ обязан говорить как носитель языка.
- Если пользователь произнёс все слова (даже с акцентом или не идеально) → похвали коротко, добавь [ADVANCE_BOOK] в конце.
- Если пользователь пропустил или явно перепутал слова → мягко поправь, повтори ту же фразу, добавь [REPEAT_BOOK] в конце.
- Если пользователь не понял → объясни, добавь [SWITCH_TO_CONVERSATION] в конце.
- Если пользователь устал → добавь [SWITCH_TO_CONVERSATION] в конце.

Теги [ADVANCE_BOOK], [REPEAT_BOOK], [SWITCH_TO_CONVERSATION], [SWITCH_TO_READING] ставь ТОЛЬКО в самом конце ответа."""

ALL_TAGS = ["[ADVANCE_BOOK]", "[REPEAT_BOOK]", "[SWITCH_TO_READING]", "[SWITCH_TO_CONVERSATION]"]


def build_context(mode: str, book_index: int = 0) -> str:
    """Matches VoiceAgentViewModel.buildContextText()"""
    if mode == "READING":
        phrase = TOM_PHRASES[book_index]
        translation = TOM_TRANSLATIONS[book_index]
        return (f'Режим: ЧТЕНИЕ КНИГИ. Текущая фраза №{book_index + 1}: '
                f'"{phrase}" (по-русски: "{translation}").')
    return "Режим: БЕСЕДА."


def build_instructions(mode: str, book_index: int = 0) -> str:
    """Matches VoiceAgentViewModel.buildFullInstructions()"""
    return SYSTEM_PROMPT + "\n\n" + build_context(mode, book_index)


def make_session_update(instructions: str) -> str:
    """Full session.update matching OpenAiRealtimeClient.buildSessionConfig()"""
    return json.dumps({
        "type": "session.update",
        "session": {
            "type": "realtime",
            "model": "gpt-realtime-2",
            "instructions": instructions,
            "output_modalities": ["audio"],
            "audio": {
                "output": {
                    "voice": "marin",
                    "format": {"type": "audio/pcm", "rate": 24000}
                },
                "input": {
                    "format": {"type": "audio/pcm", "rate": 24000},
                    "turn_detection": None
                }
            }
        }
    })


def make_user_text(text: str) -> str:
    """conversation.item.create with role=user, type=input_text"""
    return json.dumps({
        "type": "conversation.item.create",
        "item": {
            "type": "message",
            "role": "user",
            "content": [{"type": "input_text", "text": text}]
        }
    })


async def recv_response(ws, label: str, timeout: float = 45.0):
    """
    Read WebSocket messages until response.done.
    Returns: {text, tags, audio_chunks, audio_kb, duration} or None on failure.
    Ignores: session.updated, conversation.item.created, and other housekeeping events.
    """
    transcript = []
    audio_chunks = 0
    audio_bytes = 0
    t0 = time.time()
    t_first_audio = None
    deadline = t0 + timeout

    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            print(f"  ✗ [{label}] TIMEOUT after {timeout}s")
            return None

        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        except (asyncio.TimeoutError, TimeoutError):
            print(f"  ✗ [{label}] TIMEOUT after {timeout}s")
            return None

        msg = json.loads(raw)
        t = msg.get("type", "")

        if t == "response.output_audio.delta":
            if t_first_audio is None:
                t_first_audio = time.time()
            audio_chunks += 1
            delta = msg.get("delta", "")
            if delta:
                audio_bytes += len(base64.b64decode(delta))

        elif t == "response.output_audio_transcript.delta":
            transcript.append(msg.get("delta", ""))

        elif t == "response.done":
            elapsed = time.time() - t0
            text = "".join(transcript)
            tags = [tag for tag in ALL_TAGS if tag in text]
            lag = f"{t_first_audio - t0:.1f}s" if t_first_audio else "—"
            kb = audio_bytes / 1024

            print(f"  ✓ [{label}] {elapsed:.1f}s | {audio_chunks} chunks ({kb:.1f}KB) | first_audio={lag}")
            if text.strip():
                display = text.strip().replace("\n", " ")
                print(f"    transcript: \"{display[:120]}{'...' if len(display) > 120 else ''}\"")
            if tags:
                print(f"    tags: {tags}")

            return {
                "text": text,
                "tags": tags,
                "audio_chunks": audio_chunks,
                "audio_kb": kb,
                "duration": elapsed,
            }

        elif t == "error":
            err = msg.get("error", {})
            print(f"  ✗ [{label}] API error: {err.get('message', '?')} (code={err.get('code', '?')})")
            return None

        # session.updated, conversation.item.created, response.created, etc. → ignore silently


async def wait_for_session_ready(ws, timeout: float = 15.0) -> bool:
    """Read until session.updated (skipping session.created). Returns True if ready."""
    deadline = time.time() + timeout
    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            print("  ✗ Timeout waiting for session.updated")
            return False
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        except (asyncio.TimeoutError, TimeoutError):
            print("  ✗ Timeout waiting for session.updated")
            return False
        msg = json.loads(raw)
        t = msg.get("type", "")
        if t == "session.updated":
            voice = (msg.get("session", {})
                     .get("audio", {})
                     .get("output", {})
                     .get("voice", "?"))
            print(f"  Session ready. voice={voice}")
            return True
        elif t == "error":
            err = msg.get("error", {})
            print(f"  ✗ Error during setup: {err.get('message', '?')}")
            return False
        # session.created → keep waiting


async def run_conversation_test(api_key: str):
    import websockets

    mode = "CONVERSATION"
    book_index = 0
    checks: list[tuple[str, bool]] = []
    t_total = time.time()

    print(f"\n{'='*65}")
    print("DONALD — CONVERSATION TEST")
    print(f"{'='*65}")

    try:
        t_connect = time.time()
        async with websockets.connect(
            WS_URL,
            additional_headers={"Authorization": f"Bearer {api_key}"},
            open_timeout=15,
        ) as ws:
            print(f"Connected in {time.time() - t_connect:.2f}s\n")

            # ── Initial session setup ──────────────────────────────────
            print("[Setup] Sending session.update with CONVERSATION context...")
            await ws.send(make_session_update(build_instructions(mode, book_index)))
            if not await wait_for_session_ready(ws):
                print("FATAL: session setup failed")
                return
            print(f"{'─'*65}")

            # ── Turn 1: AI greeting (no user turn = requestGreeting) ───
            print("\n[1/5] AI greeting (response.create, no user message)")
            await ws.send(json.dumps({"type": "response.create"}))
            r1 = await recv_response(ws, "greeting")
            checks.append(("Turn 1 — AI greets (audio received)", r1 is not None and r1["audio_chunks"] > 0))

            # ── Turn 2: User says שלום ─────────────────────────────────
            print("\n[2/5] User: שלום, מה נשמע?")
            await ws.send(make_session_update(build_instructions(mode, book_index)))
            await ws.send(make_user_text("שלום, מה נשמע?"))
            await ws.send(json.dumps({"type": "response.create"}))
            r2 = await recv_response(ws, "shalom")
            checks.append(("Turn 2 — responds to greeting (audio)", r2 is not None and r2["audio_chunks"] > 0))

            # ── Turn 3: User asks what AI did today ────────────────────
            print("\n[3/5] User: מה עשיתה היום?")
            await ws.send(make_session_update(build_instructions(mode, book_index)))
            await ws.send(make_user_text("מה עשיתה היום?"))
            await ws.send(json.dumps({"type": "response.create"}))
            r3 = await recv_response(ws, "what did you do today")
            checks.append(("Turn 3 — responds to question (audio)", r3 is not None and r3["audio_chunks"] > 0))

            # ── Turn 4: User asks to read Tom Sawyer ───────────────────
            print("\n[4/5] User: בא נקרא ספר על טום סוייר")
            await ws.send(make_session_update(build_instructions(mode, book_index)))
            await ws.send(make_user_text("בא נקרא ספר על טום סוייר"))
            await ws.send(json.dumps({"type": "response.create"}))
            r4 = await recv_response(ws, "read Tom Sawyer")
            switched = r4 is not None and "[SWITCH_TO_READING]" in r4["tags"]
            checks.append(("Turn 4 — switches to book reading [SWITCH_TO_READING]", switched))
            if switched:
                mode = "READING"
                print("    → mode = READING ✓")

            # ── Turn 5: User repeats "Tom!" ────────────────────────────
            print(f"\n[5/5] User repeats the phrase: Tom!  (mode={mode}, phrase=#{book_index + 1}: \"{TOM_PHRASES[book_index]}\")")
            await ws.send(make_session_update(build_instructions(mode, book_index)))
            await ws.send(make_user_text("Tom!"))
            await ws.send(json.dumps({"type": "response.create"}))
            r5 = await recv_response(ws, "Tom!")
            evaluated = (
                r5 is not None and
                ("[ADVANCE_BOOK]" in r5["tags"] or "[REPEAT_BOOK]" in r5["tags"])
            )
            tag_found = ""
            if r5 and r5["tags"]:
                tag_found = next((t for t in ["[ADVANCE_BOOK]", "[REPEAT_BOOK]"] if t in r5["tags"]), "")
            checks.append((f"Turn 5 — AI evaluates repeat ({tag_found or 'ADVANCE or REPEAT tag'})", evaluated))

    except Exception as exc:
        import traceback
        print(f"\n✗ Exception: {type(exc).__name__}: {exc}")
        traceback.print_exc()

    # ── Summary ────────────────────────────────────────────────────────
    total = time.time() - t_total
    print(f"\n{'='*65}")
    print("SUMMARY")
    print(f"{'='*65}")
    all_ok = True
    for label, passed in checks:
        icon = "✓" if passed else "✗"
        print(f"  {icon}  {label}")
        if not passed:
            all_ok = False
    print(f"\n  Total time: {total:.1f}s")
    print()
    if all_ok:
        print("  ✓✓✓  ALL CHECKS PASSED")
    else:
        print("  ✗  SOME CHECKS FAILED — see transcript above")
    print()


def main():
    api_key = None
    if len(sys.argv) >= 2:
        api_key = sys.argv[1].strip()
    elif os.environ.get("OAI_KEY"):
        api_key = os.environ["OAI_KEY"].strip()
        print(f"Key from $OAI_KEY: {api_key[:8]}...")
    elif os.environ.get("OPENAI_API_KEY"):
        api_key = os.environ["OPENAI_API_KEY"].strip()
        print(f"Key from $OPENAI_API_KEY: {api_key[:8]}...")

    if not api_key:
        print("No API key found.\n"
              "Options:\n"
              "  python test_conversation.py sk-...\n"
              "  set OAI_KEY=sk-...  && python test_conversation.py   (CMD)\n"
              "  export OAI_KEY=sk-... && python test_conversation.py  (bash)")
        sys.exit(1)

    if not api_key.startswith("sk-"):
        print(f"Warning: key looks unusual: {api_key[:12]}...")

    asyncio.run(run_conversation_test(api_key))


if __name__ == "__main__":
    main()
