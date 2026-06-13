"""
DONALD — conversation integration test.

Each helper function in this file MUST match the corresponding method in the app
exactly — same JSON structure, same fields, same omissions. Any divergence means
a real bug can pass here but crash on the device.

App method → test function mapping:
  buildSessionConfig()       → make_initial_session_update()   [full config, on connect]
  updateInstructions()       → make_instructions_update()      [partial, between turns]
  requestGreeting()          → send response.create (no user item)
  commitAndRespond()         → commit audio buffer + response.create
  conversation.item.create   → send_user_text()

Flow tested (5 turns + 1 edge case):
  1. AI greeting   — response.create with no user turn
  2. User: שלום, מה נשמע?
  3. User: מה עשיתה היום?
  4. User: בא נקרא ספר על טום סוייר  → expect [SWITCH_TO_READING]
  5. User: Tom!                        → expect [ADVANCE_BOOK] or [REPEAT_BOOK]
  6. Empty audio commit                → commitAndRespond with no audio appended
                                         (happens when silence detected immediately)

Checks for every turn:
  - No error event received
  - response.done status == "completed"
  - Audio received (non-zero chunks)

Usage:
    python test_conversation.py sk-...
    set OAI_KEY=sk-...  && python test_conversation.py
    export OAI_KEY=sk-... && python test_conversation.py
"""

import asyncio
import base64
import json
import os
import sys
import time

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2"

TOM_PHRASES = ["Tom!", "No answer.", "Tom!", "No answer."]
TOM_TRANSLATIONS = ["Том!", "Никакого ответа.", "Том!", "Никакого ответа."]

ALL_TAGS = ["[ADVANCE_BOOK]", "[REPEAT_BOOK]", "[SWITCH_TO_READING]", "[SWITCH_TO_CONVERSATION]"]

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


def build_context(mode: str, book_index: int = 0) -> str:
    """Matches VoiceAgentViewModel.buildContextText()"""
    if mode == "READING":
        return (f'Режим: ЧТЕНИЕ КНИГИ. Текущая фраза №{book_index + 1}: '
                f'"{TOM_PHRASES[book_index]}" (по-русски: "{TOM_TRANSLATIONS[book_index]}").')
    return "Режим: БЕСЕДА."


def build_instructions(mode: str, book_index: int = 0) -> str:
    """Matches VoiceAgentViewModel.buildFullInstructions()"""
    return SYSTEM_PROMPT + "\n\n" + build_context(mode, book_index)


# ── JSON builders — each must match the app method listed in the comment ──────

def make_initial_session_update(instructions: str) -> str:
    """
    Full session config sent on connect.
    Matches OpenAiRealtimeClient.buildSessionConfig()
    """
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


def make_instructions_update(instructions: str) -> str:
    """
    Partial session update sent between turns.
    Matches OpenAiRealtimeClient.updateInstructions() exactly.
    Both fields are required even in partial updates:
      - session.type         → was missing before (bug: 'Missing required parameter')
      - output_modalities    → was missing before (bug: API reset to default ["text"])
    """
    return json.dumps({
        "type": "session.update",
        "session": {
            "type": "realtime",
            "output_modalities": ["audio"],   # ← MUST be present or API resets to text
            "instructions": instructions
        }
    })


def make_user_text(text: str) -> str:
    """Matches VoiceAgentViewModel → sendText / conversation.item.create"""
    return json.dumps({
        "type": "conversation.item.create",
        "item": {
            "type": "message",
            "role": "user",
            "content": [{"type": "input_text", "text": text}]
        }
    })


# ── Core receive loop ──────────────────────────────────────────────────────────

async def recv_response(ws, label: str, timeout: float = 45.0) -> dict | None:
    """
    Reads messages until response.done.
    Returns result dict or None on failure.
    Detects:
      - error events (any API error = failure)
      - response.done status != "completed" (cancelled/failed = failure)
      - zero audio chunks (silent response = failure)
    """
    transcript_parts = []
    audio_chunks = 0
    audio_bytes = 0
    error_events = []
    seen_event_types = []
    t0 = time.time()
    t_first_audio = None
    deadline = t0 + timeout

    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            print(f"  ✗ [{label}] TIMEOUT after {timeout}s")
            print(f"    Events seen: {seen_event_types}")
            return None

        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        except (asyncio.TimeoutError, TimeoutError):
            print(f"  ✗ [{label}] TIMEOUT after {timeout}s")
            return None

        msg = json.loads(raw)
        t = msg.get("type", "unknown")

        if t not in seen_event_types:
            seen_event_types.append(t)

        if t == "response.output_audio.delta":
            if t_first_audio is None:
                t_first_audio = time.time()
            audio_chunks += 1
            delta = msg.get("delta", "")
            if delta:
                audio_bytes += len(base64.b64decode(delta))

        elif t == "response.output_audio_transcript.delta":
            transcript_parts.append(msg.get("delta", ""))

        elif t == "response.done":
            elapsed = time.time() - t0
            text = "".join(transcript_parts)
            tags = [tag for tag in ALL_TAGS if tag in text]
            lag = f"{t_first_audio - t0:.1f}s" if t_first_audio else "—"
            kb = audio_bytes / 1024
            resp_obj = msg.get("response", {})
            status = resp_obj.get("status", "unknown")
            usage = resp_obj.get("usage", {})

            status_ok = (status == "completed")
            audio_ok = (audio_chunks > 0)

            icon = "✓" if (status_ok and audio_ok and not error_events) else "✗"
            print(f"  {icon} [{label}] {elapsed:.1f}s | status={status} | "
                  f"{audio_chunks} chunks ({kb:.1f}KB) | first={lag}")
            if text.strip():
                display = text.strip().replace("\n", " ")
                print(f"    transcript: \"{display[:120]}{'...' if len(display) > 120 else ''}\"")
            if tags:
                print(f"    tags: {tags}")
            if usage:
                print(f"    tokens: in={usage.get('input_tokens',0)} out={usage.get('output_tokens',0)}")
            if not status_ok:
                print(f"    ✗ BAD STATUS: expected 'completed', got '{status}'")
            if error_events:
                for e in error_events:
                    print(f"    ✗ API ERROR during turn: {e}")

            return {
                "text": text,
                "tags": tags,
                "audio_chunks": audio_chunks,
                "audio_kb": kb,
                "duration": elapsed,
                "status": status,
                "status_ok": status_ok,
                "audio_ok": audio_ok,
                "error_events": error_events,
                "ok": status_ok and audio_ok and not error_events,
            }

        elif t == "error":
            err = msg.get("error", {})
            msg_text = f"{err.get('type','?')}: {err.get('message','?')} (code={err.get('code','?')})"
            error_events.append(msg_text)
            print(f"  ✗ [{label}] API error: {msg_text}")
            # continue reading — response.done still comes after some errors

        # session.updated, conversation.item.created, response.created,
        # response.output_item.added, etc. → ignore


async def wait_for_session_ready(ws, timeout: float = 15.0) -> bool:
    """Wait for session.updated after initial session.update."""
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
                     .get("audio", {}).get("output", {}).get("voice", "?"))
            print(f"  Session ready. voice={voice}")
            return True
        elif t == "error":
            err = msg.get("error", {})
            print(f"  ✗ Error during setup: {err.get('message', '?')}")
            return False


# ── Main test ─────────────────────────────────────────────────────────────────

async def run_conversation_test(api_key: str):
    import websockets

    mode = "CONVERSATION"
    book_index = 0
    checks: list[tuple[str, bool, str]] = []  # (label, passed, detail)
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

            # ── Initial setup ──────────────────────────────────────────────
            print("[Setup] Sending initial session.update (full config)...")
            await ws.send(make_initial_session_update(build_instructions(mode, book_index)))
            if not await wait_for_session_ready(ws):
                print("FATAL: session setup failed")
                return
            print(f"{'─'*65}")

            def add_check(label, result, expect_tag=None):
                """Register a turn result as a named check."""
                if result is None:
                    checks.append((label, False, "no response / timeout"))
                    return
                if not result["ok"]:
                    detail = []
                    if not result["status_ok"]:
                        detail.append(f"status={result['status']}")
                    if not result["audio_ok"]:
                        detail.append("no audio")
                    if result["error_events"]:
                        detail.append(f"errors={result['error_events']}")
                    checks.append((label, False, ", ".join(detail)))
                    return
                if expect_tag is not None:
                    tags = result["tags"]
                    if isinstance(expect_tag, list):
                        found = any(t in tags for t in expect_tag)
                        tag_str = next((t for t in expect_tag if t in tags), str(expect_tag))
                    else:
                        found = expect_tag in tags
                        tag_str = expect_tag
                    if not found:
                        checks.append((label, False, f"expected tag {expect_tag}, got {tags}"))
                        return
                    checks.append((label, True, tag_str))
                else:
                    checks.append((label, True, f"{result['audio_chunks']} chunks"))

            # ── Turn 1: AI greeting (= requestGreeting, no user item) ──────
            print("\n[1/6] AI greeting  (response.create, no user message)")
            await ws.send(json.dumps({"type": "response.create"}))
            r1 = await recv_response(ws, "greeting")
            add_check("Turn 1 — AI greets, no user turn", r1)

            # ── Turn 2: User says שלום ─────────────────────────────────────
            print("\n[2/6] User: שלום, מה נשמע?")
            await ws.send(make_instructions_update(build_instructions(mode, book_index)))
            await ws.send(make_user_text("שלום, מה נשמע?"))
            await ws.send(json.dumps({"type": "response.create"}))
            r2 = await recv_response(ws, "shalom")
            add_check("Turn 2 — responds to Hebrew greeting", r2)

            # ── Turn 3: User asks what AI did today ────────────────────────
            print("\n[3/6] User: מה עשיתה היום?")
            await ws.send(make_instructions_update(build_instructions(mode, book_index)))
            await ws.send(make_user_text("מה עשיתה היום?"))
            await ws.send(json.dumps({"type": "response.create"}))
            r3 = await recv_response(ws, "what did you do today")
            add_check("Turn 3 — responds to Hebrew question", r3)

            # ── Turn 4: User asks to read Tom Sawyer ───────────────────────
            print("\n[4/6] User: בא נקרא ספר על טום סוייר")
            await ws.send(make_instructions_update(build_instructions(mode, book_index)))
            await ws.send(make_user_text("בא נקרא ספר על טום סוייר"))
            await ws.send(json.dumps({"type": "response.create"}))
            r4 = await recv_response(ws, "read Tom Sawyer")
            add_check("Turn 4 — switches to book reading", r4, "[SWITCH_TO_READING]")
            if r4 and "[SWITCH_TO_READING]" in r4["tags"]:
                mode = "READING"
                print("    → mode = READING")

            # ── Turn 5: User repeats the phrase ───────────────────────────
            phrase = TOM_PHRASES[book_index]
            print(f"\n[5/6] User repeats: \"{phrase}\"  (mode={mode})")
            await ws.send(make_instructions_update(build_instructions(mode, book_index)))
            await ws.send(make_user_text(phrase))
            await ws.send(json.dumps({"type": "response.create"}))
            r5 = await recv_response(ws, f'repeat "{phrase}"')
            add_check("Turn 5 — AI evaluates repeat", r5,
                      ["[ADVANCE_BOOK]", "[REPEAT_BOOK]"])

            # ── Turn 6: Empty audio commit (= commitAndRespond with no audio) ──
            # In the app this happens when onSilenceDetected fires before any audio
            # was recorded (chunksSent == 0). The fix in VoiceAgentViewModel guards
            # against this, but the API itself returns a known benign error code
            # "input_audio_buffer_commit_empty" which must NOT be shown to the user.
            print("\n[6/6] Empty audio buffer commit  (no audio appended)")
            await ws.send(make_instructions_update(build_instructions(mode, book_index)))
            await ws.send(json.dumps({"type": "input_audio_buffer.commit"}))
            await ws.send(json.dumps({"type": "response.create"}))
            r6 = await recv_response(ws, "empty commit", timeout=20.0)
            BENIGN_EMPTY_ERROR = "input_audio_buffer_commit_empty"
            if r6 is None:
                checks.append(("Turn 6 — empty commit: known error or no error (no crash/unknown error)",
                                False, "timeout or crash"))
            else:
                benign = all(BENIGN_EMPTY_ERROR in e for e in r6["error_events"])
                no_error = len(r6["error_events"]) == 0
                if no_error:
                    # App fixed it upstream (chunksSent guard): pass
                    add_check("Turn 6 — empty commit: known error or no error (no crash/unknown error)", r6)
                elif benign:
                    # Known API error for empty buffer — acceptable, must NOT reach user
                    checks.append(("Turn 6 — empty commit: known error or no error (no crash/unknown error)",
                                   True, f"known benign error: {BENIGN_EMPTY_ERROR}"))
                else:
                    checks.append(("Turn 6 — empty commit: known error or no error (no crash/unknown error)",
                                   False, f"unexpected error: {r6['error_events']}"))

    except Exception as exc:
        import traceback
        print(f"\n✗ Exception: {type(exc).__name__}: {exc}")
        traceback.print_exc()

    # ── Summary ────────────────────────────────────────────────────────────────
    total = time.time() - t_total
    print(f"\n{'='*65}")
    print("SUMMARY")
    print(f"{'='*65}")
    all_ok = True
    for label, passed, detail in checks:
        icon = "✓" if passed else "✗"
        print(f"  {icon}  {label}")
        if detail:
            print(f"       {detail}")
        if not passed:
            all_ok = False

    print(f"\n  Total time: {total:.1f}s")
    print()
    if all_ok:
        print("  ✓✓✓  ALL CHECKS PASSED")
    else:
        print("  ✗  SOME CHECKS FAILED — see above")
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
              "  set OAI_KEY=sk-...  && python test_conversation.py\n"
              "  export OAI_KEY=sk-... && python test_conversation.py")
        sys.exit(1)

    if not api_key.startswith("sk-"):
        print(f"Warning: key looks unusual: {api_key[:12]}...")

    asyncio.run(run_conversation_test(api_key))


if __name__ == "__main__":
    main()
