package io.webboy.tutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static io.webboy.tutorial.Lesson.fact;
import static io.webboy.tutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 4 — 컬렉션 (면접 Q35 ~ Q54)
 *
 * <p>4-1 의 <b>가변 객체를 키로 쓰면 못 꺼낸다</b>가 이 레슨의 핵심이다.
 * 레슨 1 의 hashCode 계약이 여기서 실제 사고로 이어진다.
 */
@DisplayName("레슨 4. 컬렉션 — HashMap 의 안쪽")
class Lesson04_Collections {

    @Test
    @DisplayName("4-1. 가변 객체를 키로 쓰면 다시 못 꺼낸다 (Q38) ★가장 위험")
    void mutableKeyIsLostForever() {
        var key = new MutableKey("처음");
        Map<MutableKey, String> map = new HashMap<>();
        map.put(key, "값");

        assertThat(map.get(key)).isEqualTo("값");   // 아직은 꺼내진다

        key.setName("바뀜");                          // ← 키의 상태를 바꾼다

        fact("키를 바꾼 뒤 get 결과", map.get(key));
        fact("map.size()", map.size());
        fact("containsKey", map.containsKey(key));

        // 넣을 때의 해시로 버킷이 정해졌는데, 찾을 때는 새 해시로 간다
        assertThat(map.get(key)).isNull();          // 같은 인스턴스인데도 못 꺼낸다
        assertThat(map).hasSize(1);                  // 그런데 여전히 안에 있다 → 메모리 누수

        lesson("키에는 불변 객체를 쓴다. String 이 키로 선호되는 이유 중 하나다");
    }

    @Test
    @DisplayName("4-2. 해시 충돌이 나도 equals 로 구분한다 (Q36)")
    void collisionsAreResolvedByEquals() {
        // hashCode 를 일부러 상수로 만들어 전부 충돌시킨다
        Map<AlwaysCollide, Integer> map = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            map.put(new AlwaysCollide(i), i);
        }

        fact("전부 충돌시킨 20건의 map.size()", map.size());
        assertThat(map).hasSize(20);                       // 값은 안 섞인다
        assertThat(map.get(new AlwaysCollide(7))).isEqualTo(7);

        lesson("충돌해도 정확성은 유지된다. 잃는 건 성능이다 — 그래서 8개 넘으면 트리로 바뀐다");
    }

    @Test
    @DisplayName("4-3. null 키·값의 허용 여부가 구현마다 다르다 (Q39·Q55)")
    void nullHandlingDiffers() {
        Map<String, String> hashMap = new HashMap<>();
        hashMap.put(null, "널 키도 된다");
        hashMap.put("널 값도 된다", null);
        assertThat(hashMap).hasSize(2);

        // 반면 불변 컬렉션과 동시성 컬렉션은 null 을 거부한다
        assertThatThrownBy(() -> Map.of("k", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> List.of("a", null)).isInstanceOf(NullPointerException.class);

        lesson("표준 라이브러리 자체가 null 을 줄이는 방향으로 간다 — 넣지 않는 편이 낫다");
    }

    @Test
    @DisplayName("4-4. 순회 중 수정하면 ConcurrentModificationException (Q45)")
    void failFastEvenInSingleThread() {
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));

        // 이름과 달리 단일 스레드에서도 난다
        assertThatThrownBy(() -> {
            for (String s : list) {
                if (s.equals("a")) {
                    list.remove(s);      // ← 순회 중 직접 수정
                }
            }
        }).isInstanceOf(ConcurrentModificationException.class);

        lesson("fail-fast — 깨진 상태로 계속하느니 즉시 실패시키는 설계다");
    }

    @Test
    @DisplayName("4-4b. 그런데 fail-fast 는 '보장'이 아니다 (Q45) ★이 튜토리얼을 만들다 발견")
    void failFastIsBestEffortNotGuaranteed() {
        // 끝에서 두 번째 요소를 지우면 예외가 나지 않는다.
        // remove 후 size=2, cursor=2 라서 hasNext() 가 먼저 false 가 되고,
        // next() 가 호출되지 않으므로 변경 검사 자체를 지나친다.
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        for (String s : list) {
            if (s.equals("b")) {
                list.remove(s);
            }
        }

        fact("끝에서 두 번째를 지운 뒤 결과", list);
        fact("예외가 났는가", false);
        assertThat(list).containsExactly("a", "c");   // 조용히 끝났다

        lesson("CME 는 best-effort 다 — '안 났으니 안전하다'는 성립하지 않는다");
        lesson("이 레슨을 만들다 실제로 밟았다. 처음엔 b 를 지우게 짜서 테스트가 실패했다");
    }

    @Test
    @DisplayName("4-5. 순회 중 삭제는 iterator.remove 나 removeIf 로 (Q45·Q48)")
    void howToRemoveDuringIteration() {
        List<String> viaIterator = new ArrayList<>(List.of("a", "b", "c"));
        for (Iterator<String> it = viaIterator.iterator(); it.hasNext(); ) {
            if (it.next().equals("b")) {
                it.remove();             // 이터레이터가 자기 상태도 같이 갱신한다
            }
        }
        assertThat(viaIterator).containsExactly("a", "c");

        List<String> viaRemoveIf = new ArrayList<>(List.of("a", "b", "c"));
        viaRemoveIf.removeIf(s -> s.equals("b"));   // 대부분 이쪽이 읽기 좋다
        assertThat(viaRemoveIf).containsExactly("a", "c");

        lesson("조건 삭제는 removeIf 한 줄이면 된다");
    }

    @Test
    @DisplayName("4-6. Arrays.asList 는 고정 길이 뷰다 (Q46) ★함정")
    void arraysAsListIsFixedSize() {
        List<String> view = Arrays.asList("a", "b");

        view.set(0, "A");                            // 교체는 된다
        assertThat(view.get(0)).isEqualTo("A");

        assertThatThrownBy(() -> view.add("c"))      // 크기 변경은 안 된다
                .isInstanceOf(UnsupportedOperationException.class);

        // List.of 는 더 엄격하다 — 교체조차 안 된다
        assertThatThrownBy(() -> List.of("a").set(0, "A"))
                .isInstanceOf(UnsupportedOperationException.class);

        lesson("Arrays.asList = 고정 길이 / List.of = 완전 불변. 가변이 필요하면 new ArrayList<>(...)");
    }

    @Test
    @DisplayName("4-7. 순서가 필요하면 Linked·Tree 계열 (Q41·Q42·Q53)")
    void orderingGuarantees() {
        List<String> input = List.of("banana", "apple", "cherry", "apple");

        Set<String> hash = new HashSet<>(input);            // 순서 보장 없음
        Set<String> linked = new LinkedHashSet<>(input);    // 넣은 순서
        Map<String, Integer> tree = new TreeMap<>();        // 키 정렬 순서
        input.forEach(s -> tree.put(s, s.length()));

        fact("LinkedHashSet (삽입 순)", linked);
        fact("TreeMap 키 (정렬 순)", tree.keySet());
        fact("HashSet (순서 미보장)", hash);

        assertThat(linked).containsExactly("banana", "apple", "cherry");
        assertThat(tree.keySet()).containsExactly("apple", "banana", "cherry");
        assertThat(hash).hasSize(3);   // 내용만 단정하고 순서는 단정하지 않는다

        lesson("HashSet 의 순서에 의존하는 코드를 쓰면 안 된다 — 보장이 없다");
    }

    @Test
    @DisplayName("4-8. LinkedHashMap 으로 LRU 캐시를 몇 줄에 만든다 (Q41)")
    void lruCacheInAFewLines() {
        var lru = new LruCache<String, Integer>(3);
        lru.put("a", 1);
        lru.put("b", 2);
        lru.put("c", 3);
        lru.get("a");        // a 를 최근 사용으로 올린다
        lru.put("d", 4);     // 상한 초과 → 가장 오래된 b 가 밀려난다

        fact("LRU 캐시 내용", lru.keySet());
        assertThat(lru.keySet()).containsExactly("c", "a", "d");
        assertThat(lru).doesNotContainKey("b");

        lesson("접근 순서 모드 + removeEldestEntry 재정의면 끝이다");
    }

    @Test
    @DisplayName("4-9. synchronizedList 는 순회까지 지켜주지 않는다 (Q51) ★함정")
    void synchronizedListDoesNotCoverIteration() {
        List<String> sync = Collections.synchronizedList(new ArrayList<>(List.of("a", "b")));

        // 개별 메서드는 원자적이지만…
        sync.add("c");
        assertThat(sync).hasSize(3);

        // 순회는 여전히 직접 감싸야 한다 (여기서는 단일 스레드라 예외만 확인)
        assertThatThrownBy(() -> {
            for (String s : sync) {
                if (s.equals("a")) sync.remove(s);
            }
        }).isInstanceOf(ConcurrentModificationException.class);

        lesson("'스레드 세이프한 컬렉션'이라도 여러 연산의 조합은 직접 지켜야 한다");
    }

    // ── 레슨용 클래스 ──────────────────────────────────────────────

    /** 가변 키 — 실무에서 절대 하면 안 되는 예. */
    static final class MutableKey {
        private String name;

        MutableKey(String name) {
            this.name = name;
        }

        void setName(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MutableKey k && Objects.equals(k.name, name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);   // 상태가 바뀌면 해시도 바뀐다
        }
    }

    /** 해시를 상수로 만들어 전부 충돌시키는 키. */
    record AlwaysCollide(int id) {
        @Override
        public int hashCode() {
            return 42;
        }
    }

    /** LinkedHashMap 의 접근 순서 모드를 쓴 LRU 캐시. */
    static final class LruCache<K, V> extends LinkedHashMap<K, V> {
        private final int capacity;

        LruCache(int capacity) {
            super(16, 0.75f, true);   // 세 번째 인자 true = 접근 순서
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;
        }
    }
}
