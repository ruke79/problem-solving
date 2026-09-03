package io.webboy.verify.labs.perfbook.ch15;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.perfbook.ChildJvm;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * <i>Optimizing Java</i> 1판(2018) 15장 「Java 9 와 미래」의 예측 성적표(00-검토 §5)를 상시 실행 케이스로 옮긴 것 —
 * §7 의 마지막 제안("15장 예측 검증 케이스: 상시 실행하면 다음 JDK 에서 또 무엇이 바뀌는지 자동으로 잡힌다").
 *
 * <p>확인하는 것: Compact Strings, 분할 코드 캐시, `--illegal-access` 제거, `jaotc` 부재, Graal 모듈의 공동화,
 * `AtomicInteger` 가 아직 Unsafe 위에 있음, 문자열 연결의 `invokedynamic`, VarHandle 존재.
 */
@Component
public class Java9PredictionsCase extends VerificationCase {

    @Override
    public String id() {
        return "PERF-15A";
    }

    @Override
    public String category() {
        return "perfbook";
    }

    @Override
    public String question() {
        return "Optimizing Java 15장 — 2018년에 예고한 'Java 9 와 그 이후' 는 지금 JVM 에서 어떻게 됐는가?";
    }

    @Override
    public String claim() {
        return "맞은 것: Compact Strings·분할 코드 캐시·VarHandle·문자열 연결의 invokedynamic 은 기본이 됐다. 방식이 달라진 것: "
                + "jaotc 는 제거됐고 OpenJDK 의 Graal 모듈은 빈 껍데기다. 그대로인 것: AtomicInteger 는 아직 Unsafe 위에 있다. "
                + "그리고 --illegal-access 는 사라졌다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        evidence.fact("java.version", Runtime.version().toString());

        // ① 맞은 예측 — 기본이 된 것들
        evidence.fact("CompactStrings", diagnostics.getVMOption("CompactStrings").getValue());
        evidence.fact("SegmentedCodeCache", diagnostics.getVMOption("SegmentedCodeCache").getValue());
        evidence.expect("Compact Strings 는 기본이다", "true".equals(diagnostics.getVMOption("CompactStrings").getValue()));
        evidence.expect("분할 코드 캐시는 기본이다", "true".equals(diagnostics.getVMOption("SegmentedCodeCache").getValue()));
        evidence.expect("VarHandle 이 있다", classExists("java.lang.invoke.VarHandle"));
        boolean indy = classFileMentions(Java9PredictionsCase.class, "makeConcatWithConstants");
        evidence.fact("이 클래스의 상수 풀에 makeConcatWithConstants", indy ? "있음" : "없음");
        evidence.expect("문자열 연결(+)은 StringBuilder 가 아니라 invokedynamic(JEP 280)으로 컴파일된다", indy);

        // ② 방식이 달라진 것 — AOT
        boolean jaotc = Files.exists(Path.of(System.getProperty("java.home"), "bin", "jaotc"))
                || Files.exists(Path.of(System.getProperty("java.home"), "bin", "jaotc.exe"));
        evidence.fact("$JAVA_HOME/bin/jaotc", jaotc ? "있음" : "없음");
        evidence.expect("책이 예고한 jaotc 는 없다 (JDK 17 에서 제거, JEP 410)", !jaotc);
        long graalClasses = graalCompilerClasses();
        evidence.fact("Graal 컴파일러 모듈(jdk.internal.vm.compiler / jdk.graal.compiler)의 .class 수",
                graalClasses < 0 ? "모듈 없음" : graalClasses + " (module-info 포함)");
        evidence.expect("OpenJDK 빌드의 Graal 모듈은 없거나 module-info 하나뿐이다 — 실제 컴파일러 코드가 없다", graalClasses <= 1);

        // ③ 그대로인 것 — Unsafe
        Optional<Field> unsafeField = Arrays.stream(java.util.concurrent.atomic.AtomicInteger.class.getDeclaredFields())
                .filter(f -> f.getType().getName().endsWith(".Unsafe")).findFirst();
        evidence.fact("AtomicInteger 의 Unsafe 필드", unsafeField.map(f -> f.getType().getName() + " " + f.getName()).orElse("없음"));
        evidence.expect("AtomicInteger 는 여전히 (내부) Unsafe 위에 있다 — VarHandle 로 바뀌지 않았다", unsafeField.isPresent());

        // ④ 사라진 것 — 모듈 캡슐화 완화 스위치
        ChildJvm.Result illegalAccess = ChildJvm.version("--illegal-access=permit");
        evidence.fact("--illegal-access=permit", illegalAccess.all().lines().findFirst().orElse("").trim() + " [rc=" + illegalAccess.exitCode() + "]");
        // JEP 403(JDK 17)은 이 옵션의 '기능'을 제거했다 — 옵션 자체는 경고("Ignoring option --illegal-access; support was
        // removed in 17.0")를 내고 무시된다. 00-검토 §5 의 "제거" 는 이 뜻이다(기동 실패가 아니다).
        evidence.expect("--illegal-access 는 JDK 17 부터 경고 후 무시된다 (JEP 403) — 캡슐화를 풀 방법이 없다",
                illegalAccess.exitCode() == 0 && illegalAccess.mentions("Ignoring option --illegal-access"));

        evidence.note("00-검토 §5 의 성적표(맞음 10 / 빗나감 4 / 방식 달라짐 2 / 그대로 1)에서 실행으로 확인 가능한 항목만 골랐다. "
                + "JDK 25 쪽의 후속(세대형 ZGC·컴팩트 헤더·AOT 캐시·Unsafe 경고)은 verify-labs-cloudnative 가 잇는다. "
                + "이 케이스가 어느 날 REFUTED 가 나면 그것이 곧 '다음 JDK 에서 또 바뀐 것' 이다.");
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 클래스 파일 바이트에서 UTF8 상수를 찾는다 — javac 가 어떤 연결 전략을 골랐는지는 상수 풀에 그대로 남는다. */
    private static boolean classFileMentions(Class<?> type, String constant) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return bytes.contains(constant);
        }
    }

    /**
     * 부트 레이어가 아니라 {@link ModuleFinder#ofSystem()} 으로 찾는다 — Graal 모듈은 어떤 루트 모듈도 요구하지 않아
     * 부트 레이어에 해석되지 않으므로, 부트 레이어로 찾으면 "실려 있지 않다"와 "해석되지 않았다"를 구분할 수 없다
     * (첫 판이 그렇게 '모듈 없음'을 잘못 보고했다).
     */
    private static long graalCompilerClasses() throws Exception {
        for (String name : new String[]{"jdk.internal.vm.compiler", "jdk.graal.compiler"}) {
            var module = ModuleFinder.ofSystem().find(name);
            if (module.isPresent()) {
                try (ModuleReader reader = module.get().open()) {
                    return reader.list().filter(n -> n.endsWith(".class")).count();
                }
            }
        }
        return -1;
    }
}
