#!/usr/bin/env python3
"""필수 문항(S급) 플래시카드를 `필수-키노트.md` 에서 생성한다.

    ./scripts/make-flashcards.py           # manuscripts/플래시카드/*.tsv 를 다시 만든다
    ./scripts/make-flashcards.py --check   # 최신인지 검사만 한다 (CI 용, 어긋나면 종료 코드 1)

왜 손으로 안 쓰고 생성하는가

  플래시카드는 키노트의 **파생물**이다. 손으로 옮겨 적으면 키노트를 고쳤을 때 조용히 어긋나고,
  어긋난 쪽을 외우게 된다. 그래서 키노트를 유일한 출처로 두고 여기서 뽑는다.
  CI 의 `--check` 가 어긋남을 실패로 잡는다(`.github/workflows/ci.yml` 의 docs 잡).

카드의 구조

  앞면 : 면접에서 실제로 듣는 **일본어 질문**(Part 파일의 문항 제목)
  뒷면 : 그 자리에서 말할 **일본어 한 문장**(키노트의 인용문) + 한국어 뜻 + 실행 근거 케이스 id
  태그 : 세트 이름 · 문항 번호 · ★표시(가장 자주/함정/변별력) · 케이스 id

  탭 구분(TSV)이라 Anki 에 그대로 가져올 수 있다. 필드 안의 줄바꿈은 `<br>` 이다(Anki 는 HTML 을 렌더한다).
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "manuscripts")
OUT = os.path.join(SRC, "플래시카드")

# (디렉터리, 출력 파일 이름, 태그) — 순서가 곧 전체.tsv 의 순서다.
SETS = [
    ("java-면접", "java", "java"),
    ("spring-면접", "spring", "spring"),
    ("python-면접", "python", "python"),
    ("javascript-면접", "javascript", "javascript"),
    ("db-면접", "db", "db"),
    ("kafka-면접", "kafka", "kafka"),
    ("kubernetes-면접", "kubernetes", "kubernetes"),
]

# S급 절만 잘라낸다 — "## 1. S급 …" 부터 "## 2. …" 직전까지.
S_SECTION = re.compile(r"^## 1\. S급.*?(?=^## 2\.)", re.MULTILINE | re.DOTALL)
# **Q35. 제목** ★가장 자주 ▶레슨 1-3  ✅`DB-14`
ENTRY = re.compile(r"^\*\*Q(\d+)\.\s*(.+?)\*\*(.*)$", re.MULTILINE)
# 문항 제목: ### Q35. 日本語の質問\n(한국어)
HEADING = re.compile(r"^### Q(\d+)\.\s*(.+?)\s*$\n^\((.+?)\)\s*$", re.MULTILINE)
CASE_ID = re.compile(r"`([A-Z]+-[0-9A-Z]+)`")
# ★가장 자주 · ★변별력 최고 처럼 뒤에 수식어가 붙으므로, ★ 부터 다음 기호 전까지를 한 덩어리로 본다.
MARK = re.compile(r"★([^★▶✅]+)")
MARK_TAGS = [("가장 자주", "빈출"), ("함정", "함정"), ("변별력", "변별력"), ("실무", "실무"),
             ("보안", "보안"), ("시니어", "시니어"), ("설계", "설계"), ("자바", "자바연계")]


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def questions_of(directory):
    """Part 파일에서 문항 번호 → (일본어 질문, 한국어 질문)."""
    out = {}
    for name in sorted(os.listdir(os.path.join(SRC, directory))):
        if not re.fullmatch(r"Part\d+\.md", name):
            continue
        for num, jp, ko in HEADING.findall(read(os.path.join(SRC, directory, name))):
            out[int(num)] = (jp.strip(), ko.strip())
    return out


def cards_of(directory, tag):
    keynote = os.path.join(SRC, directory, "필수-키노트.md")
    if not os.path.exists(keynote):
        raise SystemExit(f"필수-키노트.md 가 없다: {directory}")
    text = read(keynote)
    section = S_SECTION.search(text)
    if not section:
        raise SystemExit(f"S급 절을 찾지 못했다: {directory}")
    body = section.group(0)
    questions = questions_of(directory)

    cards = []
    for m in ENTRY.finditer(body):
        num, title, trailing = int(m.group(1)), m.group(2).strip(), m.group(3)
        # 항목 바로 뒤의 인용문(> …) 이 그 문항의 일본어 한 문장이다.
        rest = body[m.end():]
        quote = re.search(r"^> (.+?)$", rest, re.MULTILINE)
        if not quote:
            raise SystemExit(f"{directory} Q{num}: 인용문(>)이 없다")
        # 다른 항목을 먼저 만나면 그 문항에는 인용문이 없는 것이다.
        nxt = ENTRY.search(rest)
        if nxt and nxt.start() < quote.start():
            raise SystemExit(f"{directory} Q{num}: 인용문 없이 다음 항목이 나왔다")

        jp_q, ko_q = questions.get(num, ("", ""))
        if not jp_q:
            raise SystemExit(f"{directory} Q{num}: Part 파일에 문항이 없다")

        cases = CASE_ID.findall(trailing)
        marks = [s.strip() for s in MARK.findall(trailing)]

        front = f"Q{num}. {jp_q}"
        back = quote.group(1).strip()
        # 한국어 뜻과 근거는 뒷면 아래에 작게 붙인다.
        extra = [f"— {ko_q}"]
        if cases:
            extra.append("실행 근거: " + " · ".join(cases))
        back = back + "<br><br>" + "<br>".join(extra)

        tags = [tag, f"Q{num}"]
        tags += [name for key, name in MARK_TAGS if any(key in mark for mark in marks)]
        tags += cases
        cards.append((front, back, " ".join(dict.fromkeys(tags)), title))
    return cards


def render(cards, title):
    lines = [
        f"# {title} — 필수(S급) 플래시카드. scripts/make-flashcards.py 가 생성한다 (직접 고치지 말 것)",
        "# 앞면\t뒷면\t태그",
    ]
    for front, back, tags, _ in cards:
        lines.append(f"{front}\t{back}\t{tags}")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description="필수 문항 플래시카드 생성")
    ap.add_argument("--check", action="store_true", help="다시 만들지 않고 최신인지만 검사한다")
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    everything, stale = [], []
    for directory, name, tag in SETS:
        cards = cards_of(directory, tag)
        everything += cards
        for target, text in [(os.path.join(OUT, f"{name}.tsv"), render(cards, directory))]:
            if args.check:
                if not os.path.exists(target) or read(target) != text:
                    stale.append(os.path.relpath(target, ROOT))
            else:
                with open(target, "w", encoding="utf-8") as f:
                    f.write(text)
        print(f"  {directory:18s} S급 {len(cards):2d}장")

    combined = os.path.join(OUT, "전체.tsv")
    text = render(everything, "전 세트")
    if args.check:
        if not os.path.exists(combined) or read(combined) != text:
            stale.append(os.path.relpath(combined, ROOT))
        if stale:
            print("\n플래시카드가 키노트와 어긋난다 — ./scripts/make-flashcards.py 로 다시 만들 것:")
            for s in stale:
                print(f"  {s}")
            return 1
        print(f"\n최신이다 — 전 {len(everything)}장")
        return 0

    with open(combined, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"\n{OUT} 에 {len(SETS) + 1}개 파일 · 전 {len(everything)}장")
    return 0


if __name__ == "__main__":
    sys.exit(main())
