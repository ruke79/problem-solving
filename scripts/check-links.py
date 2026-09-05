#!/usr/bin/env python3
"""저장소 문서의 링크를 검사한다 — 깨진 링크와 '어디서도 닿지 않는 문서'를 함께 본다.

    ./scripts/check-links.py            # 세 검사 전부, 문제가 있으면 종료 코드 1
    ./scripts/check-links.py --quiet    # 문제만 출력 (CI 용)
    ./scripts/check-links.py --no-orphans   # 도달성 검사는 건너뛴다

무엇을 보는가

  (1) 깨진 링크    — 마크다운의 상대 링크가 실제 파일을 가리키는가.
  (2) 깨진 앵커    — `문서.md#절-제목` 의 절이 그 문서에 실제로 있는가.
  (3) 고아 문서    — README.md 에서 링크를 따라가 닿지 않는 .md 가 있는가.

(3) 을 넣은 이유가 이 스크립트의 요점이다. (1) 만 보면 "링크가 다 살아 있다"는 말은
할 수 있지만 **문서를 새로 만들고 어디에도 걸지 않은 것**은 잡히지 않는다. 실제로 이 저장소는
(1) 이 0건인 상태에서 원고 Part 파일 33개가 디렉터리 링크로만 걸려 있어 문서에서 닿지 않았다.
`docs/00` §8 의 정직성 원칙과 같은 종류다 — **없다는 사실조차 적혀 있지 않은 상태**를 막는다.

고아가 나오면 고치는 방법은 링크를 다는 것이지 이 검사를 끄는 것이 아니다. 다만 의도적으로
색인에 넣지 않는 문서가 생기면 아래 ORPHAN_ALLOWLIST 에 이유와 함께 적는다.
"""
import argparse
import os
import re
import sys
import unicodedata
from collections import deque

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 검사 대상에서 뺄 경로 — 생성물과 도구 디렉터리.
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".idea"}

# 도달성 검사의 출발점. 여기서 링크를 따라가 닿는 .md 가 "연결된 문서"다.
ROOTS = ["README.md"]

# 고아여도 통과시킬 문서 — 반드시 이유를 함께 적는다. (지금은 없다)
ORPHAN_ALLOWLIST: dict[str, str] = {}

LINK = re.compile(r"\]\(([^)\s]+?)(?:\s+\"[^\"]*\")?\)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$", re.MULTILINE)
INLINE_MD = re.compile(r"`([^`]*)`|\[([^\]]*)\]\([^)]*\)|[*_]{1,2}([^*_]+)[*_]{1,2}")


def walk_files(ext=None):
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if ext is None or name.endswith(ext):
                yield os.path.relpath(os.path.join(dirpath, name), ROOT)


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def links_in(rel):
    """(줄번호, 경로, 앵커) 목록. 외부 URL 과 순수 앵커 링크는 뺀다."""
    text = read(rel)
    out = []
    for m in LINK.finditer(text):
        target = m.group(1)
        if target.startswith(("http://", "https://", "mailto:", "#", "<")):
            continue
        path, _, anchor = target.partition("#")
        if not path:
            continue
        line = text.count("\n", 0, m.start()) + 1
        out.append((line, path, anchor))
    return out


def slugify(heading):
    """GitHub 의 제목 → 앵커 변환을 흉내낸다.

    소문자로 바꾸고, 마크다운 장식(코드·링크·강조)을 벗기고, 단어 문자와 공백만 남긴 뒤
    공백을 하이픈으로. 한글은 그대로 남는다 (`## Part 별 파일` → `part-별-파일`).

    주의 — **연속 공백은 합치지 않는다.** GitHub 는 공백 하나당 하이픈 하나를 넣으므로,
    구두점이 지워져 공백이 둘이 되면 하이픈도 둘이다
    (`## 5. 15장의 예측 성적표 — 책이 …` → `5-15장의-예측-성적표--책이-…`).
    첫 판이 `\\s+` 로 합쳤다가 멀쩡한 앵커를 깨졌다고 보고했다.
    """
    text = INLINE_MD.sub(lambda m: m.group(1) or m.group(2) or m.group(3) or "", heading)
    text = unicodedata.normalize("NFC", text).lower().strip()
    text = "".join(c for c in text if c.isalnum() or c in " -_")
    return re.sub(r"\s", "-", text)


def anchors_of(rel):
    slugs = set()
    for _, title in HEADING.findall(read(rel)):
        slug = slugify(title)
        if not slug:
            continue
        # 같은 제목이 여러 번 나오면 GitHub 는 -1, -2 를 붙인다.
        candidate, n = slug, 1
        while candidate in slugs:
            candidate, n = f"{slug}-{n}", n + 1
        slugs.add(candidate)
    return slugs


def main():
    ap = argparse.ArgumentParser(description="문서 링크·앵커·도달성 검사")
    ap.add_argument("--quiet", action="store_true", help="문제만 출력한다")
    ap.add_argument("--no-orphans", action="store_true", help="도달성 검사를 건너뛴다")
    args = ap.parse_args()

    docs = sorted(walk_files(".md"))
    broken, bad_anchor, total = [], [], 0
    anchor_cache = {}

    for rel in docs:
        for line, path, anchor in links_in(rel):
            total += 1
            target = os.path.normpath(os.path.join(os.path.dirname(rel), path))
            if not os.path.exists(os.path.join(ROOT, target)):
                broken.append((rel, line, path))
                continue
            if anchor and target.endswith(".md"):
                if target not in anchor_cache:
                    anchor_cache[target] = anchors_of(target)
                if anchor.lower() not in anchor_cache[target]:
                    bad_anchor.append((rel, line, f"{path}#{anchor}"))

    # 도달성 — README 에서 .md 링크를 따라간다.
    orphans = []
    if not args.no_orphans:
        seen, queue = set(), deque(ROOTS)
        while queue:
            rel = queue.popleft()
            if rel in seen or not os.path.exists(os.path.join(ROOT, rel)):
                continue
            seen.add(rel)
            if not rel.endswith(".md"):
                continue
            for _, path, _ in links_in(rel):
                target = os.path.normpath(os.path.join(os.path.dirname(rel), path))
                if target.endswith(".md") and target not in seen:
                    queue.append(target)
        orphans = [d for d in docs if d not in seen and d not in ORPHAN_ALLOWLIST]

    if not args.quiet:
        print(f"문서 {len(docs)}개 · 상대 링크 {total}개 검사")

    ok = True
    if broken:
        ok = False
        print(f"\n깨진 링크 {len(broken)}건 — 가리키는 파일이 없다")
        for rel, line, path in broken:
            print(f"  {rel}:{line}  ->  {path}")
    if bad_anchor:
        ok = False
        print(f"\n깨진 앵커 {len(bad_anchor)}건 — 문서는 있으나 그 절이 없다")
        for rel, line, path in bad_anchor:
            print(f"  {rel}:{line}  ->  {path}")
    if orphans:
        ok = False
        print(f"\n고아 문서 {len(orphans)}건 — {'/'.join(ROOTS)} 에서 링크를 따라가 닿지 않는다")
        for rel in orphans:
            print(f"  {rel}")
        print("  (색인에 링크를 달아라. 일부러 빼는 것이라면 스크립트의 ORPHAN_ALLOWLIST 에 이유와 함께 적는다)")

    if ok and not args.quiet:
        print("깨진 링크 0 · 깨진 앵커 0" + ("" if args.no_orphans else f" · 고아 문서 0 (전부 {ROOTS[0]} 에서 도달)"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
