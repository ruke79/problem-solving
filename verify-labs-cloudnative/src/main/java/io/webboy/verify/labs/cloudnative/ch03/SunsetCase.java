package io.webboy.verify.labs.cloudnative.ch03;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Flags;
import io.webboy.verify.labs.cloudnative.Jvm;

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.util.Optional;

/**
 * 3장·부록 B — "옛 스위치는 새 JVM 에서 무시되거나 기동을 막는다" 를 JDK 25 에서 목록으로 확인한다.
 *
 * <p>2판이 여전히 언급하거나(CMS 17회, `PrintGCApplicationStoppedTime`), 1판 검토에서 "JDK 17 에서
 * 사라졌다"고 적었던 것들이 25 에서는 어떤 상태인지 — 기동 실패 / 경고 후 무시 / 그대로 — 를 나눈다.
 * 이 저장소의 {@code PERF-A01}(JDK 17) 의 25 판이다.
 */
public class SunsetCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-03A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 3장·부록 B — 책(과 1판)이 말하는 JVM 스위치·API 중 JDK 25 에서 사라진 것은 무엇인가?";
    }

    @Override
    public String claim() {
        return "CMS·편향 락·PrintGCApplicationStoppedTime·AggressiveOpts 는 '알 수 없는 옵션'으로 JVM 이 뜨지 않고, "
                + "ZGenerational 과 --illegal-access 는 경고만 남기고 무시되며, SecurityManager 는 설정 자체가 예외이고, "
                + "OpenJDK 의 Graal 컴파일러 모듈은 이름만 남은 빈 껍데기다 — 옛 튜닝 팁은 버전 확인 없이는 쓸 수 없다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        evidence.fact("java.version", Runtime.version().toString());

        // ① 기동을 막는 것들
        for (String flag : new String[]{"-XX:+UseConcMarkSweepGC", "-XX:+UseBiasedLocking",
                "-XX:+PrintGCApplicationStoppedTime", "-XX:+AggressiveOpts"}) {
            Jvm.Result result = Jvm.version(flag);
            boolean refused = result.exitCode() != 0
                    && (result.mentions("Unrecognized") || result.mentions("Unrecognized option"));
            evidence.fact(flag, refused ? "기동 실패 (Unrecognized)" : "rc=" + result.exitCode());
            evidence.expect(flag + " 는 JDK 25 에서 JVM 을 띄우지 못한다", refused);
        }

        // ② 경고 후 무시 — 세대형 ZGC 만 남아 플래그가 폐기됐다 (JEP 490, JDK 24). --illegal-access 도 같은 부류다
        //    (JEP 403, JDK 17): 첫 판은 이것을 '기동 실패' 로 기대했다가 REFUTED 가 났다 — 실제로는 경고 후 무시된다.
        Jvm.Result zgen = Jvm.version("-XX:+UseZGC", "-XX:-ZGenerational");
        evidence.fact("-XX:+UseZGC -XX:-ZGenerational", zgen.stderr().lines().findFirst().orElse(""));
        evidence.expect("ZGenerational 은 'support was removed in 24.0' 경고 후 무시된다 (rc=0)",
                zgen.exitCode() == 0 && zgen.mentions("Ignoring option ZGenerational"));
        Jvm.Result illegalAccess = Jvm.version("--illegal-access=permit");
        evidence.fact("--illegal-access=permit", illegalAccess.stderr().lines().findFirst().orElse("(경고 없음)")
                + " [rc=" + illegalAccess.exitCode() + "]");
        evidence.expect("--illegal-access 는 'support was removed in 17.0' 경고 후 무시된다 (rc=0)",
                illegalAccess.exitCode() == 0 && illegalAccess.mentions("Ignoring option --illegal-access"));

        // ③ SecurityManager — JEP 486 (JDK 24) 영구 비활성
        boolean securityManagerRefused;
        try {
            System.setSecurityManager(null);
            securityManagerRefused = false;
        } catch (UnsupportedOperationException e) {
            securityManagerRefused = true;
        }
        evidence.fact("System.setSecurityManager(null)", securityManagerRefused
                ? "UnsupportedOperationException" : "허용됨");
        evidence.expect("SecurityManager 는 설정 자체가 UnsupportedOperationException 이다", securityManagerRefused);

        // ④ Graal 모듈 — 21 의 jdk.internal.vm.compiler 는 빈 껍데기였고 25 는 이름이 바뀌었다.
        //    시스템 모듈 파인더로 찾는다 — 부트 레이어에는 해석되지 않는 모듈이라 그쪽으로 찾으면 늘 '없음'이 나온다.
        Optional<ModuleReference> graal = ModuleFinder.ofSystem().find("jdk.graal.compiler");
        Optional<ModuleReference> oldGraal = ModuleFinder.ofSystem().find("jdk.internal.vm.compiler");
        long classes = graal.isPresent() ? countClasses(graal.get()) : -1;
        evidence.fact("jdk.internal.vm.compiler 모듈 (21 의 이름)", oldGraal.isPresent() ? "실려 있음" : "없음");
        evidence.fact("jdk.graal.compiler 모듈의 .class 수", classes < 0 ? "모듈 없음" : classes + " (module-info 포함)");
        evidence.expect("jdk.internal.vm.compiler 는 더 이상 없다 (이름이 바뀌었다)", oldGraal.isEmpty());
        evidence.expect("jdk.graal.compiler 는 실려 있되 module-info 하나뿐이다 (실제 컴파일러 없음)",
                graal.isPresent() && classes <= 1);
        Jvm.Result jvmci = Jvm.run(java.util.List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+EnableJVMCI",
                "-XX:+UseJVMCICompiler", "-Xcomp"),
                io.webboy.verify.labs.cloudnative.probe.RuntimeProbe.class);
        evidence.fact("-XX:+UseJVMCICompiler -Xcomp", jvmci.all().lines()
                .filter(line -> line.contains("JVMCI")).findFirst().orElse("(JVMCI 관련 출력 없음)"));
        evidence.expect("UseJVMCICompiler 는 기동은 되지만 첫 컴파일에서 'No JVMCI compiler found' 로 실패한다",
                jvmci.mentions("No JVMCI compiler found"));

        // ⑤ 아직 있는 것 — 대조군
        evidence.fact("UseG1GC 기본값", Flags.value("UseG1GC").orElse("없음"));
        evidence.fact("UseCompressedOops 기본값", Flags.value("UseCompressedOops").orElse("없음"));
        evidence.expect("G1 과 압축 oop 은 그대로 기본이다",
                Flags.isTrue("UseG1GC") && Flags.isTrue("UseCompressedOops"));

        evidence.note("책의 부록 B 「민간 전승으로 튜닝」이 경고한 그대로다. 1판 검토가 JDK 17 에서 확인한 목록 "
                + "(CMS·PrintGC*·AggressiveOpts·--illegal-access)에 21 부터 UseBiasedLocking 이, 24 부터 "
                + "ZGenerational 과 SecurityManager 가 더해졌다. 2판 12장은 PrintGCApplicationStoppedTime 을 "
                + "아직 세이프포인트 진단 플래그로 적고 있다 — 지금은 -Xlog:safepoint 다.");
    }

    private static long countClasses(ModuleReference reference) throws Exception {
        try (ModuleReader reader = reference.open()) {
            return reader.list().filter(name -> name.endsWith(".class")).count();
        }
    }
}
