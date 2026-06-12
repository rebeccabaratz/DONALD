"""
Test script for OpenAI Realtime API (gpt-realtime-2)
Mirrors the exact session format used in DONALD Android app.

Usage:
    python test_realtime.py sk-your-key-here
"""

import asyncio
import json
import sys
import time
import urllib.request
import urllib.error

WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2"

SESSION_CONFIG = {
    "type": "session.update",
    "session": {
        "type": "realtime",
        "model": "gpt-realtime-2",
        "instructions": "You are a test assistant. When asked, say exactly: 'Test OK'.",
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
}

# Events we don't need to print in detail (high-frequency or boring)
SILENT_EVENTS = {
    "response.output_audio.delta",          # printed as counter
    "response.output_audio_transcript.delta" # printed as text
}

def ts():
    return f"{time.time():.3f}"

def print_event(t, msg, indent="    "):
    """Print an event with key fields."""
    extra = ""
    if "event_id" in msg:
        extra += f" [id={msg['event_id']}]"
    if "response_id" in msg:
        extra += f" [resp={msg['response_id']}]"
    if "item_id" in msg:
        extra += f" [item={msg['item_id']}]"
    if t == "error":
        err = msg.get("error", {})
        extra += f" → {err.get('type','?')}: {err.get('message','?')}"
    elif t == "session.created" or t == "session.updated":
        sess = msg.get("session", {})
        extra += f" → voice={sess.get('audio',{}).get('output',{}).get('voice','?')}"
        extra += f" modalities={sess.get('output_modalities','?')}"
        td = sess.get("audio", {}).get("input", {}).get("turn_detection")
        extra += f" vad={td}"
    elif t == "response.created":
        extra += f" → status={msg.get('response',{}).get('status','?')}"
    elif t == "response.done":
        resp = msg.get("response", {})
        usage = resp.get("usage", {})
        extra += f" → status={resp.get('status','?')}"
        if usage:
            extra += f" tokens: in={usage.get('input_tokens',0)} out={usage.get('output_tokens',0)}"
    elif t == "response.output_item.added":
        item = msg.get("item", {})
        extra += f" → type={item.get('type','?')} role={item.get('role','?')}"
    elif t == "response.content_part.added":
        part = msg.get("part", {})
        extra += f" → type={part.get('type','?')}"
    elif t == "rate_limits.updated":
        limits = msg.get("rate_limits", [])
        for lim in limits:
            extra += f" {lim.get('name','?')}={lim.get('remaining','?')}/{lim.get('limit','?')}"
    print(f"{indent}[{ts()}] {t}{extra}")

def check_key_http(api_key):
    print("\n[1] HTTP — validating API key...")
    req = urllib.request.Request(
        "https://api.openai.com/v1/models",
        headers={"Authorization": f"Bearer {api_key}"}
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read())
            models = [m["id"] for m in data.get("data", [])]
            realtime = sorted([m for m in models if "realtime" in m.lower()])
            print(f"    ✓ Key valid. {len(models)} models total.")
            print(f"    Realtime models: {realtime}")
            return True
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"    ✗ HTTP {e.code}: {body[:300]}")
        return False
    except Exception as e:
        print(f"    ✗ {e}")
        return False

async def test_websocket(api_key):
    import websockets

    print(f"\n[2] WebSocket → {WS_URL}")

    audio_chunks = 0
    audio_bytes = 0
    text_chunks = []
    session_ok = False
    turn_done = False
    t_connect = None
    t_session_ready = None
    t_first_audio = None
    t_turn_done = None

    try:
        t_connect = time.time()
        async with websockets.connect(WS_URL,
                                      additional_headers={"Authorization": f"Bearer {api_key}"},
                                      open_timeout=15) as ws:
            print(f"    ✓ Connected in {time.time()-t_connect:.2f}s")

            print(f"\n[3] Sending session.update...")
            print(f"    Config: {json.dumps(SESSION_CONFIG['session'], ensure_ascii=False)[:300]}")
            await ws.send(json.dumps(SESSION_CONFIG))

            print(f"\n[4] Listening for events (all events printed):")
            print(f"    {'─'*55}")

            async for raw in ws:
                msg = json.loads(raw)
                t = msg.get("type", "unknown")

                if t in SILENT_EVENTS:
                    pass  # handled below with counters
                else:
                    print_event(t, msg)

                # --- Handle each event ---
                if t == "session.updated":
                    t_session_ready = time.time()
                    session_ok = True
                    print(f"    ✓ Session ready in {t_session_ready - t_connect:.2f}s from connect")

                    # Send test message
                    payload = {
                        "type": "conversation.item.create",
                        "item": {
                            "type": "message",
                            "role": "user",
                            "content": [{"type": "input_text",
                                         "text": "Say exactly: Test OK"}]
                        }
                    }
                    print(f"\n[5] Sending text message + response.create...")
                    await ws.send(json.dumps(payload))
                    await ws.send(json.dumps({"type": "response.create"}))

                elif t == "response.output_audio.delta":
                    if audio_chunks == 0:
                        t_first_audio = time.time()
                        lag = t_first_audio - t_session_ready if t_session_ready else 0
                        print(f"    ✓ First audio delta after {lag:.2f}s")
                    audio_chunks += 1
                    delta = msg.get("delta", "")
                    import base64
                    audio_bytes += len(base64.b64decode(delta)) if delta else 0

                elif t == "response.output_audio_transcript.delta":
                    text_chunks.append(msg.get("delta", ""))

                elif t == "response.done":
                    t_turn_done = time.time()
                    turn_done = True
                    break

                elif t == "error":
                    print(f"    ✗ FATAL ERROR — stopping")
                    break

        print(f"    {'─'*55}")

    except Exception as e:
        print(f"\n    ✗ Exception: {type(e).__name__}: {e}")
        import traceback
        traceback.print_exc()

    # Summary
    total_time = (t_turn_done or time.time()) - (t_connect or time.time())
    print(f"\n{'='*55}")
    print(f"RESULTS")
    print(f"{'='*55}")
    print(f"  Session ready:       {'✓' if session_ok else '✗'}")
    print(f"  Audio received:      {'✓' if audio_chunks > 0 else '✗'}"
          f"  ({audio_chunks} chunks, {audio_bytes/1024:.1f} KB)")
    print(f"  Transcript:          {'✓' if text_chunks else '✗'}"
          f"  \"{(''.join(text_chunks)).strip()[:60]}\"")
    print(f"  Turn done:           {'✓' if turn_done else '✗'}")
    print(f"  Total time:          {total_time:.2f}s")
    if t_first_audio and t_connect:
        print(f"  Time to first audio: {t_first_audio - t_connect:.2f}s")
    print()
    if session_ok and audio_chunks > 0 and turn_done:
        print("  ✓✓✓ ALL CHECKS PASSED")
    else:
        print("  ✗ FAILED — check events above for the error")
    print()

def main():
    import os
    key_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".key")
    if len(sys.argv) >= 2:
        api_key = sys.argv[1].strip()
    elif os.environ.get("OAI_KEY"):
        api_key = os.environ["OAI_KEY"].strip()
        print(f"Key from $OAI_KEY: {api_key[:8]}...")
    elif os.environ.get("OPENAI_API_KEY"):
        api_key = os.environ["OPENAI_API_KEY"].strip()
        print(f"Key from $OPENAI_API_KEY: {api_key[:8]}...")
    elif os.path.exists(key_file):
        api_key = open(key_file).read().strip()
        print(f"Key from .key file: {api_key[:8]}...")
    else:
        print("No key found. Options:")
        print("  1. python test_realtime.py sk-...")
        print("  2. set OAI_KEY=sk-...  (CMD)")
        print(f"  3. echo sk-... > {key_file}")
        sys.exit(1)

    if not api_key.startswith("sk-"):
        print(f"Warning: key doesn't look right: {api_key[:12]}...")

    if not check_key_http(api_key):
        print("HTTP check failed — fix key first.")
        return

    asyncio.run(test_websocket(api_key))

if __name__ == "__main__":
    main()
