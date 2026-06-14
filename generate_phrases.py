"""
generate_phrases.py — splits Tom Sawyer chapters into semantic phrases (≤5 words)
and generates Russian translations using OpenAI GPT-4o.

Output: ready-to-paste Kotlin lists for VoiceAgentViewModel.kt
        + saved to phrases_chapterN.txt

Usage:
    python generate_phrases.py sk-...
    python generate_phrases.py sk-... --chapter 2
    python generate_phrases.py sk-... --chapter 1 --file my_text.txt
"""

import json
import os
import re
import sys
import urllib.request
import urllib.error

# Fix Windows CMD encoding
if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CHAT_URL = "https://api.openai.com/v1/chat/completions"

SPLIT_PROMPT = """\
Split the following English text into short spoken phrases for a language learner.

Rules:
- Each phrase must be 1–5 words (NEVER cut mid-thought — "he walked to the" is WRONG).
- Prefer natural speech breaks: clause ends, commas, dashes, sentence ends.
- Preserve the original wording exactly (no paraphrasing, no punctuation changes).
- Return ONLY a JSON array of strings, nothing else.

Example output:
["Tom!", "No answer.", "What's gone with that boy,", "I wonder?"]

Text:
"""

TRANSLATE_PROMPT = """\
Translate each English phrase to Russian. Keep it short and natural — these are
spoken phrases for a language learner. Return ONLY a JSON array of Russian strings
(same order, same length as the input). No extra text.

English phrases:
"""


def openai_chat(api_key: str, prompt: str) -> str:
    payload = json.dumps({
        "model": "gpt-4o",
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
    }).encode()
    req = urllib.request.Request(
        CHAT_URL,
        data=payload,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read())
            return data["choices"][0]["message"]["content"].strip()
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"✗ HTTP {e.code}: {body[:400]}")
        sys.exit(1)


def extract_json_array(text: str) -> list:
    start = text.find("[")
    end = text.rfind("]")
    if start == -1 or end == -1:
        print(f"✗ Could not find JSON array in response:\n{text[:300]}")
        sys.exit(1)
    return json.loads(text[start:end + 1])


def load_chapters(filepath: str) -> dict:
    """Parse Gutenberg Tom Sawyer text into {chapter_num: text} dict."""
    with open(filepath, encoding="utf-8") as f:
        content = f.read()

    # Gutenberg format: "CHAPTER II", "CHAPTER III", etc. (no period, no Chapter I heading)
    pattern = re.compile(r'^CHAPTER\s+((?:X{0,3})(?:IX|IV|V?I{0,3}))\s*$',
                         re.IGNORECASE | re.MULTILINE)
    splits = list(pattern.finditer(content))
    if not splits:
        print("ERROR: Could not find chapter headings in the file.")
        sys.exit(1)

    roman = {"I":1,"II":2,"III":3,"IV":4,"V":5,"VI":6,"VII":7,"VIII":8,
             "IX":9,"X":10,"XI":11,"XII":12,"XIII":13,"XIV":14,"XV":15,
             "XVI":16,"XVII":17,"XVIII":18,"XIX":19,"XX":20,"XXI":21,
             "XXII":22,"XXIII":23,"XXIV":24,"XXV":25,"XXVI":26,"XXVII":27,
             "XXVIII":28,"XXIX":29,"XXX":30,"XXXI":31,"XXXII":32,"XXXIII":33,
             "XXXIV":34,"XXXV":35}

    chapters = {}

    # Chapter 1 = everything before the first heading
    ch1_text = content[:splits[0].start()].strip()
    if ch1_text:
        chapters[1] = ch1_text

    for i, m in enumerate(splits):
        num = roman.get(m.group(1).upper())
        if num is None:
            continue
        start = m.end()
        end = splits[i + 1].start() if i + 1 < len(splits) else len(content)
        text = content[start:end].strip()
        chapters[num] = text

    return chapters


def to_kotlin_list(name: str, items: list) -> str:
    lines = [f'    val {name} = listOf(']
    row = []
    for item in items:
        escaped = item.replace('"', '\\"')
        row.append(f'"{escaped}"')
        if len(row) == 4:
            lines.append("        " + ", ".join(row) + ",")
            row = []
    if row:
        lines.append("        " + ", ".join(row))
    lines.append("    )")
    return "\n".join(lines)


def main():
    api_key = None
    chapter_num = 1
    txt_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tom_sawyer.txt")

    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--chapter" and i + 1 < len(args):
            chapter_num = int(args[i + 1])
            i += 2
        elif args[i] == "--file" and i + 1 < len(args):
            txt_file = args[i + 1]
            i += 2
        elif args[i].startswith("sk-"):
            api_key = args[i]
            i += 1
        else:
            i += 1

    if not api_key:
        api_key = os.environ.get("OAI_KEY") or os.environ.get("OPENAI_API_KEY")
    if not api_key:
        print("No API key.\n"
              "  python generate_phrases.py sk-...\n"
              "  set OAI_KEY=sk-... && python generate_phrases.py")
        sys.exit(1)

    if not os.path.exists(txt_file):
        print(f"✗ File not found: {txt_file}")
        print("  Place tom_sawyer.txt in C:\\DONALD\\ or pass --file path/to/file.txt")
        sys.exit(1)

    print(f"Loading {txt_file}...")
    chapters = load_chapters(txt_file)
    print(f"Found {len(chapters)} chapters: {sorted(chapters.keys())}")

    text = chapters.get(chapter_num)
    if not text:
        print(f"✗ Chapter {chapter_num} not found. Available: {sorted(chapters.keys())}")
        sys.exit(1)

    word_count = len(text.split())
    print(f"\n── Chapter {chapter_num} ({word_count} words) ───────────────────────────")
    print(text[:200] + "...\n")

    print("Step 1/2 — splitting into phrases (gpt-4o)...")
    raw_split = openai_chat(api_key, SPLIT_PROMPT + text)
    phrases = extract_json_array(raw_split)
    print(f"  → {len(phrases)} phrases")

    print("Step 2/2 — translating to Russian (gpt-4o, batches of 50)...")
    translations = []
    batch_size = 50
    for batch_start in range(0, len(phrases), batch_size):
        batch = phrases[batch_start:batch_start + batch_size]
        raw_trans = openai_chat(api_key, TRANSLATE_PROMPT + json.dumps(batch, ensure_ascii=False))
        batch_trans = extract_json_array(raw_trans)
        if len(batch_trans) != len(batch):
            print(f"  WARNING: batch {batch_start//batch_size + 1} mismatch "
                  f"({len(batch)} phrases vs {len(batch_trans)} translations) — padding with empty strings")
            while len(batch_trans) < len(batch):
                batch_trans.append("")
            batch_trans = batch_trans[:len(batch)]
        translations.extend(batch_trans)
        print(f"  translated {min(batch_start + batch_size, len(phrases))}/{len(phrases)}")

    if len(translations) != len(phrases):
        print(f"ERROR: Length mismatch: {len(phrases)} phrases vs {len(translations)} translations")
        sys.exit(1)

    print(f"\n{'═'*60}")
    print("RESULT — paste into VoiceAgentViewModel.kt:\n")
    print(to_kotlin_list("tomSawyerPhrases", phrases))
    print()
    print(to_kotlin_list("tomSawyerTranslations", translations))
    print(f"{'═'*60}")

    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            f"phrases_chapter{chapter_num}.txt")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(f"Chapter {chapter_num} — {len(phrases)} phrases\n\n")
        f.write(to_kotlin_list("tomSawyerPhrases", phrases))
        f.write("\n\n")
        f.write(to_kotlin_list("tomSawyerTranslations", translations))
        f.write("\n\nPhrase list:\n")
        for idx, (en, ru) in enumerate(zip(phrases, translations)):
            f.write(f"{idx+1:>3}. {en!r:40} → {ru!r}\n")

    print(f"\nSaved to: {out_path}")


if __name__ == "__main__":
    main()
