package io.webboy.verify.labs.perfbook.ch11;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;

/**
 * <i>Optimizing Java</i> 1판(2018) 11장의 보충 — 책은 문자열을 "가장 흔한 힙 객체" 로만 다루고 JDK 9 의
 * Compact Strings(JEP 254)를 모른다(00-검토 §7: "11장은 Compact Strings 만 보충하면 된다").
 *
 * <p>{@code String} 의 {@code byte[] value} 길이와 {@code coder} 를 직접 읽는다 — 라틴-1 만이면 문자당 1바이트,
 * 한 글자라도 비라틴이면 전체가 2바이트. 테스트 JVM 에 {@code --add-opens java.base/java.lang=ALL-UNNAMED} 가 필요하다
 * (perfbook 의 test 태스크가 준다). 없으면 판정하지 않고 INCONCLUSIVE 로 남긴다.
 */
@Component
public class CompactStringsCase extends VerificationCase {

    @Override
    public String id() {
        return "PERF-11E";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "Optimizing Java 11장 보충 — JDK 9+ 의 String 은 문자 하나에 몇 바이트를 쓰는가?";
    }

    @Override
    public String claim() {
        return "Compact Strings(JEP 254, 기본 켜짐) 때문에 String 은 char[] 가 아니라 byte[] + coder 다. 라틴-1 만이면 문자당 1바이트, "
                + "한글이 하나라도 섞이면 전체가 UTF-16 으로 문자당 2바이트다 — ASCII 10자와 한글 5자가 같은 10바이트다";
    }

    @Override
    public boolean nondeterministic() {
        return true;   // --add-opens 가 없는 환경에서는 판정하지 않는다
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        String compactStrings = diagnostics.getVMOption("CompactStrings").getValue();
        evidence.fact("CompactStrings", compactStrings);
        evidence.expect("CompactStrings 는 기본으로 켜져 있다", "true".equals(compactStrings));

        Field valueField;
        Field coderField;
        try {
            valueField = String.class.getDeclaredField("value");
            coderField = String.class.getDeclaredField("coder");
            valueField.setAccessible(true);
            coderField.setAccessible(true);
        } catch (RuntimeException inaccessible) {   // InaccessibleObjectException
            evidence.fact("String 내부 접근", inaccessible.getClass().getSimpleName());
            evidence.expectFlaky("테스트 JVM 에 --add-opens java.base/java.lang=ALL-UNNAMED 가 있어야 한다 — 지금은 없다", false);
            return;
        }
        evidence.fact("String.value 의 타입", valueField.getType().getSimpleName());
        evidence.expect("String 의 내부 배열은 char[] 가 아니라 byte[] 다", valueField.getType() == byte[].class);

        String ascii10 = "abcdefghij";
        String hangul5 = "가나다라마";
        String hangul10 = "가나다라마바사아자차";
        String mixed = "abcdefghi가";
        Layout[] layouts = {
                layout(valueField, coderField, "ASCII 10자", ascii10),
                layout(valueField, coderField, "한글 5자", hangul5),
                layout(valueField, coderField, "한글 10자", hangul10),
                layout(valueField, coderField, "ASCII 9자 + 한글 1자", mixed),
        };
        for (Layout l : layouts) {
            evidence.fact(l.label(), l.chars() + "자 → value " + l.bytes() + " bytes, coder=" + (l.coder() == 0 ? "LATIN1" : "UTF16"));
        }
        evidence.expectEquals("ASCII 10자는 10바이트 (문자당 1바이트)", 10, layouts[0].bytes());
        evidence.expectEquals("한글 5자는 10바이트 (문자당 2바이트)", 10, layouts[1].bytes());
        evidence.expectEquals("한글 10자는 20바이트", 20, layouts[2].bytes());
        evidence.expectEquals("한글 한 글자만 섞여도 전체가 20바이트 — 문자열 단위로 인코딩이 정해진다", 20, layouts[3].bytes());
        evidence.expect("coder 는 라틴-1 이 0, UTF-16 이 1 이다",
                layouts[0].coder() == 0 && layouts[1].coder() == 1 && layouts[3].coder() == 1);
        evidence.note("1판 15장이 예고한 그대로다(byte[] value / coder 필드). 한국어 서비스에서 문자열 힙이 영어 서비스의 두 배라는 뜻이며, "
                + "jmap -histo 에서 String 뒤에 오는 것은 이제 [C 가 아니라 [B 다(2판 12장이 이렇게 고쳐 적었다). "
                + "이 실측은 java-tutorial 이 아니라 여기 있는 이유 — 내부 필드 접근에 --add-opens 가 필요해 튜토리얼의 '순수 자바' 방침 밖이다.");
    }

    private static Layout layout(Field valueField, Field coderField, String label, String s) throws IllegalAccessException {
        byte[] value = (byte[]) valueField.get(s);
        byte coder = coderField.getByte(s);
        return new Layout(label, s.length(), value.length, coder);
    }

    private record Layout(String label, int chars, int bytes, byte coder) {}
}
