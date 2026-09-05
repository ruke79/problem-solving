#!/usr/bin/env python3
"""플래시카드 TSV 를 **인덱스 카드 크기의 인쇄물**로 만든다.

    ./scripts/make-flashcard-sheets.py                    # 기본: 인덱스 카드(127×76mm)에 직접 인쇄
    ./scripts/make-flashcard-sheets.py --layout a4        # A4 에 격자로 찍어 잘라 쓰기
    ./scripts/make-flashcard-sheets.py --size ring        # 링 단어카드(90×55mm)
    ./scripts/make-flashcard-sheets.py --check            # 최신인지 검사 (CI 용)

두 가지 인쇄 방식

  layout=card  페이지 크기를 카드 크기로 잡는다. **카드를 프린터에 직접 넣어** 찍는 방식이라
               재단이 필요 없다. 앞면·뒷면이 번갈아 나오므로 양면 인쇄로 뽑는다.
  layout=a4    A4 한 장에 카드를 격자로 배치하고 재단선을 넣는다. 카드를 프린터에 못 넣거나,
               두꺼운 종이에 찍어 직접 자를 때. 뒷면 시트는 **좌우를 뒤집어** 배치하므로
               양면 인쇄하면 앞뒤가 맞는다.

카드 크기는 `--size` 로 고른다(실물을 자로 재서 다르면 SIZES 를 고칠 것):
  index 127×76mm (3×5인치 인덱스/정보 카드)  ·  a6 148×105mm  ·  ring 90×55mm (링 단어카드)

글자 크기는 카드 넓이와 글자 수로 **카드마다 자동 계산**한다. 가장 긴 뒷면은 209자라
index 크기에서는 여유가 있지만, ring 에서는 8pt 아래로 내려가는 카드가 나온다 — 실행하면 경고로 알린다.

이 파일들도 생성물이다. 원본은 각 세트의 `필수-키노트.md` 이고,
키노트 → TSV(`make-flashcards.py`) → 인쇄물(이 스크립트) 순으로 파생된다.
"""
import argparse
import html
import math
import re
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CARDS = os.path.join(ROOT, "manuscripts", "플래시카드")
OUT = os.path.join(CARDS, "인쇄")

SETS = ["java", "spring", "python", "javascript", "db", "kafka", "kubernetes", "전체"]

# 저장소에 넣어 두는(그래서 CI 가 최신인지 검사하는) 조합. 나머지 크기는 필요할 때 옵션으로 만든다.
DEFAULTS = [("card", "index"), ("a4", "index")]

# 이름: (너비mm, 높이mm, 안쪽 여백mm, A4 격자 열×행)
SIZES = {
    "index": (127, 76, 6, (1, 3)),
    "a6": (148, 105, 8, (1, 2)),
    "ring": (90, 55, 4, (2, 5)),
}
LABELS = {"index": "인덱스 카드 127×76mm (3×5인치)", "a6": "A6 148×105mm", "ring": "링 단어카드 90×55mm"}
SET_NAMES = {"java": "JAVA", "spring": "SPRING", "python": "PYTHON", "javascript": "JAVASCRIPT",
             "db": "DB", "kafka": "KAFKA", "kubernetes": "KUBERNETES", "전체": "ALL"}


def read_cards(name):
    """TSV 한 벌 → [(세트, 문항, 일본어 질문, 한국어 질문, 일본어 답변, 근거, 태그)]."""
    out = []
    with open(os.path.join(CARDS, f"{name}.tsv"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            front, back, tags = parts
            qno, _, jp_q = front.partition(". ")
            # 뒷면은 "일본어 답변<br><br>— 한국어 질문<br>실행 근거: …" 형태로 만들어져 있다.
            segments = [s for s in back.split("<br>") if s.strip()]
            jp_a = segments[0]
            ko_q = next((s[2:].strip() for s in segments if s.startswith("— ")), "")
            evidence = next((s.split(": ", 1)[1] for s in segments if s.startswith("실행 근거: ")), "")
            tag_list = tags.split()
            deck = tag_list[0] if tag_list else name
            marks = [t for t in tag_list[1:] if not t.startswith("Q") and "-" not in t]
            out.append((deck, qno, jp_q, ko_q, jp_a, evidence, marks))
    return out


def inline_md(text):
    """키노트에서 온 최소한의 마크다운을 HTML 로 바꾼다.

    카드 86장 중 43장에 `코드`, 64장에 **강조**가 들어 있다. 그대로 두면 백틱과 별표가
    인쇄물에 찍힌다 — 첫 판이 실제로 그렇게 나왔다(렌더한 카드를 눈으로 보고 발견).
    """
    out = html.escape(text)
    out = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", out)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    return out


def plain(text):
    """글자 크기를 계산할 때 쓰는, 표시 기호를 걷어낸 길이용 문자열."""
    return re.sub(r"[`*]", "", text)


def font_pt(text, width_mm, height_mm, lo, hi):
    """글자 수와 넓이로 본문 크기를 정한다.

    한 글자가 차지하는 넓이는 대략 (pt × 0.3528mm)² 이고 줄 간격 1.55 배를 곱한다.
    거기서 크기를 역산한 뒤 0.85 를 곱해(제목·여백 몫) lo~hi 로 자른다.
    """
    n = max(len(text), 1)
    pt = 2.835 * math.sqrt((width_mm * height_mm) / (1.55 * n)) * 0.85
    return round(max(lo, min(hi, pt)), 1)


def card_html(kind, deck, qno, jp, ko, extra, marks, w, h, pad):
    inner_w, inner_h = w - 2 * pad, h - 2 * pad
    if kind == "front":
        size = font_pt(plain(jp), inner_w, inner_h * 0.62, 10, 17)
        body = f'<div class="jp" style="font-size:{size}pt">{inline_md(jp)}</div>'
        body += f'<div class="ko">{html.escape(ko)}</div>' if ko else ""
    else:
        size = font_pt(plain(jp), inner_w, inner_h * 0.86, 8, 14)
        body = f'<div class="jp answer" style="font-size:{size}pt">{inline_md(jp)}</div>'
        body += f'<div class="evi">{html.escape(extra)}</div>' if extra else ""
    tags = "".join(f'<span class="tag">{html.escape(m)}</span>' for m in marks)
    return (f'<section class="card {kind}">'
            f'<header><span class="deck">{html.escape(SET_NAMES.get(deck, deck.upper()))} '
            f'{html.escape(qno)}</span><span class="marks">{tags}</span></header>'
            f'<div class="body">{body}</div></section>')


def document(title, size_key, layout, cards):
    w, h, pad, (cols, rows) = SIZES[size_key]
    css_page = (f"@page {{ size: {w}mm {h}mm; margin: 0; }}"
                if layout == "card" else "@page { size: A4; margin: 8mm; }")
    grid = "" if layout == "card" else (
        f".sheet {{ display: grid; grid-template-columns: repeat({cols}, {w}mm);"
        f" grid-template-rows: repeat({rows}, {h}mm); justify-content: center; align-content: start;"
        f" page-break-after: always; }}"
        f".sheet .card {{ border: 0.2mm dashed #999; }}")

    body = []
    if layout == "card":
        for c in cards:
            deck, qno, jp_q, ko_q, jp_a, evi, marks = c
            body.append(card_html("front", deck, qno, jp_q, ko_q, "", marks, w, h, pad))
            body.append(card_html("back", deck, qno, jp_a, "", evi, marks, w, h, pad))
    else:
        per = cols * rows
        for start in range(0, len(cards), per):
            page = cards[start:start + per]
            front = [card_html("front", c[0], c[1], c[2], c[3], "", c[6], w, h, pad) for c in page]
            body.append('<div class="sheet">' + "".join(front) + "</div>")
            # 뒷면은 행마다 좌우를 뒤집어야 양면 인쇄에서 앞뒤가 맞는다(가로로 넘기는 경우).
            back_cards = []
            for r in range(rows):
                row = page[r * cols:(r + 1) * cols]
                back_cards += list(reversed(row))
            back = [card_html("back", c[0], c[1], c[4], "", c[5], c[6], w, h, pad) for c in back_cards]
            body.append('<div class="sheet back-sheet">' + "".join(back) + "</div>")

    return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<title>{html.escape(title)}</title>
<style>
  {css_page}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; font-family: "Noto Sans CJK KR", "Noto Sans JP", "Malgun Gothic", sans-serif;
          color: #111; background: #fff; }}
  {grid}
  .card {{ width: {w}mm; height: {h}mm; padding: {pad}mm; overflow: hidden;
           display: flex; flex-direction: column; page-break-inside: avoid; }}
  {'.card { page-break-after: always; }' if layout == 'card' else ''}
  header {{ display: flex; justify-content: space-between; align-items: baseline;
            font-size: 6.5pt; color: #666; border-bottom: 0.3mm solid #ddd;
            padding-bottom: 1mm; margin-bottom: 1.5mm; letter-spacing: .04em; }}
  .deck {{ font-weight: 700; }}
  .tag {{ margin-left: 1.2mm; padding: 0.2mm 1mm; border: 0.2mm solid #bbb; border-radius: 1mm; }}
  .body {{ flex: 1; display: flex; flex-direction: column; justify-content: center; }}
  .jp {{ line-height: 1.5; word-break: break-word; }}
  .jp b {{ font-weight: 700; background: linear-gradient(transparent 62%, #ffe9a8 62%); }}
  code {{ font-family: "DejaVu Sans Mono", monospace; font-size: 0.92em;
          background: #f2f2f2; padding: 0 0.6mm; border-radius: 0.6mm; }}
  .answer {{ line-height: 1.55; }}
  .ko {{ margin-top: 2.5mm; font-size: 7.5pt; color: #666; line-height: 1.4; }}
  .evi {{ margin-top: 2.5mm; font-size: 6.5pt; color: #777; border-top: 0.2mm dotted #ccc;
          padding-top: 1.2mm; }}
  .back .body {{ justify-content: flex-start; }}
  @media screen {{
    body {{ background: #f4f4f4; padding: 10mm; }}
    .card {{ background: #fff; border: 0.3mm solid #ccc; border-radius: 2mm; margin: 0 auto 4mm; }}
    .sheet {{ background: #fff; margin: 0 auto 8mm; padding: 8mm; }}
  }}
</style></head>
<body>
{"".join(body)}
</body></html>
"""


def build(layout, size_key, check):
    """조합 하나를 만들거나(check=False) 최신인지 검사한다(check=True). (어긋난 파일, 빡빡한 카드)."""
    w, h, pad, _ = SIZES[size_key]
    stale, tight = [], []
    for name in SETS:
        cards = read_cards(name)
        for c in cards:
            if font_pt(plain(c[4]), w - 2 * pad, (h - 2 * pad) * 0.86, 8, 14) <= 8:
                tight.append(f"{c[0]} {c[1]}")
        suffix = "" if (layout, size_key) == ("card", "index") else f"-{layout}-{size_key}"
        target = os.path.join(OUT, f"{name}{suffix}.html")
        text = document(f"{SET_NAMES.get(name, name)} 플래시카드 — {LABELS[size_key]}",
                        size_key, layout, cards)
        if check:
            if not os.path.exists(target) or open(target, encoding="utf-8").read() != text:
                stale.append(os.path.relpath(target, ROOT))
        else:
            with open(target, "w", encoding="utf-8") as f:
                f.write(text)
    return stale, sorted(set(tight))


def main():
    ap = argparse.ArgumentParser(description="플래시카드 인쇄물 생성")
    ap.add_argument("--layout", choices=["card", "a4"], help="한 조합만 만든다 (기본은 저장소에 넣는 두 조합)")
    ap.add_argument("--size", choices=list(SIZES), help="카드 크기 (기본 index)")
    ap.add_argument("--check", action="store_true", help="다시 만들지 않고 최신인지만 검사한다")
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    # 옵션을 하나라도 주면 그 조합만, 안 주면 저장소에 넣는 두 조합을 만든다.
    combos = DEFAULTS if not (args.layout or args.size) else [(args.layout or "card", args.size or "index")]

    stale, tight = [], []
    for layout, size_key in combos:
        s, t = build(layout, size_key, args.check)
        stale += s
        tight += t
        print(f"  {LABELS[size_key]:32s} layout={layout:4s} {len(SETS)}개 파일")

    if args.check:
        if stale:
            print("\n인쇄물이 카드와 어긋난다 — ./scripts/make-flashcard-sheets.py 로 다시 만들 것:")
            for s in stale:
                print(f"  {s}")
            return 1
        print("\n최신이다")
        return 0

    print(f"\n{OUT}")
    if tight:
        print(f"\n※ 뒷면 글씨가 최소 크기(8pt)까지 줄어든 카드 — 더 큰 카드를 권한다: {', '.join(sorted(set(tight)))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
