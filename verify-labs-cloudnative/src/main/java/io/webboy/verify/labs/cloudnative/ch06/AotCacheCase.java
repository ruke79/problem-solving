package io.webboy.verify.labs.cloudnative.ch06;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import io.webboy.verify.labs.cloudnative.Jvm;
import io.webboy.verify.labs.cloudnative.probe.WorkloadProbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 6장·15장 — 책(JDK 21)은 "HotSpot 은 프로파일을 저장하지 않는다", "Leyden 은 아직 별로 실린 게 없다" 고 쓴다.
 * JDK 24(JEP 483)·25(JEP 514/515)의 AOT 캐시가 그 전제를 바꿨다: 훈련 실행 한 번으로 클래스 로딩·링킹
 * (그리고 메서드 프로파일)을 다음 기동에 넘긴다. 여기서는 <b>클래스가 실제로 캐시에서 로드되는가</b>만 센다 —
 * 기동 시간 단축은 재지 않는다(측정 규칙상 자릿수 차이가 아니다).
 */
public class AotCacheCase extends VerificationCase {

    @Override
    public String id() {
        return "CN-06A";
    }

    @Override
    public String category() {
        return "cloudnative";
    }

    @Override
    public String question() {
        return "2판 6장·15장 — Project Leyden 의 AOT 캐시는 JDK 25 에서 실제로 쓸 수 있는가?";
    }

    @Override
    public String claim() {
        return "-XX:AOTCacheOutput=app.aot 한 번으로 캐시가 만들어지고, -XX:AOTCache=app.aot 로 띄우면 "
                + "JDK 기본 CDS 아카이브보다 더 많은 클래스(앱 클래스 포함)가 '공유 아카이브'에서 로드된다";
    }

    @Override
    protected void verify(Evidence evidence) throws Exception {
        Path dir = Files.createTempDirectory("cn06a-aot");
        Path cache = dir.resolve("app.aot");
        try {
            // 첫 판은 build/classes 디렉터리를 클래스패스로 줬다가 "Cannot have non-empty directory in paths" 로 실패했다 —
            // CDS 시절부터의 제약이다. 그래서 프로브를 jar 로 묶어 준다. 디렉터리 실패도 증거로 남긴다.
            Jvm.Result fromDirectory = Jvm.run(List.of("-XX:AOTCacheOutput=" + dir.resolve("dir.aot")), WorkloadProbe.class);
            evidence.fact("디렉터리 클래스패스로 훈련 실행", fromDirectory.exitCode() + " / "
                    + fromDirectory.all().lines().filter(l -> l.contains("directory")).findFirst().orElse(""));
            evidence.expect("디렉터리 클래스패스는 거부된다 — jar 만 된다", fromDirectory.exitCode() != 0
                    && fromDirectory.mentions("Cannot have non-empty directory in paths"));

            String jar = Jvm.jarOf(WorkloadProbe.class, dir).toString();
            Jvm.Result training = Jvm.run(List.of("-XX:AOTCacheOutput=" + cache), jar, WorkloadProbe.class);
            evidence.fact("jar 클래스패스로 훈련 실행 rc / 마지막 stderr 줄", training.exitCode() + " / "
                    + training.all().lines().filter(l -> l.contains("AOTCache")).findFirst().orElse(""));
            evidence.expect("한 단계(-XX:AOTCacheOutput) 훈련 실행이 성공한다", training.exitCode() == 0);
            evidence.expect("캐시 파일이 생겼다", Files.exists(cache));
            evidence.fact("캐시 크기", Files.exists(cache) ? Files.size(cache) / 1024 + " KB" : "없음");

            Jvm.Result cached = Jvm.run(List.of("-XX:AOTCache=" + cache, "-Xlog:class+load"), jar, WorkloadProbe.class);
            long fromCache = sharedLoads(cached);
            long fromDefaultCds = sharedLoads(Jvm.run(List.of("-Xlog:class+load"), jar, WorkloadProbe.class));
            long withoutSharing = sharedLoads(Jvm.run(List.of("-Xshare:off", "-Xlog:class+load"), jar, WorkloadProbe.class));
            evidence.fact("공유 아카이브에서 로드된 클래스 수 — AOT 캐시", fromCache);
            evidence.fact("공유 아카이브에서 로드된 클래스 수 — JDK 기본 CDS", fromDefaultCds);
            evidence.fact("공유 아카이브에서 로드된 클래스 수 — -Xshare:off", withoutSharing);
            evidence.expect("-Xshare:off 면 0 이다", withoutSharing == 0);
            evidence.expect("AOT 캐시가 기본 CDS 보다 많은 클래스를 아카이브에서 준다", fromCache > fromDefaultCds);
            evidence.expect("앱 클래스(WorkloadProbe)도 캐시에서 온다", appClassFromCache(cached));
            evidence.note("JDK 21 에서는 AOTCacheOutput·AOTCache 가 'Unrecognized VM option' 이다(이 세션에서 확인). "
                    + "책이 15장에서 '미래'로 미룬 Leyden 의 첫 결과물이 24·25 에 실렸다. 메서드 프로파일(JEP 515)이 "
                    + "워밍업을 얼마나 줄이는지는 이 케이스가 재지 않는다 — JMH 급 측정이 필요하다. "
                    + "운영 팁: Spring Boot 의 exploded-jar 이미지(디렉터리 클래스패스)에서는 이 캐시를 만들 수 없다 — jar 여야 한다.");
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static long sharedLoads(Jvm.Result result) {
        return result.stdout().lines().filter(line -> line.contains("source: shared objects file")).count();
    }

    private static boolean appClassFromCache(Jvm.Result result) {
        return result.stdout().lines().anyMatch(line -> line.contains(WorkloadProbe.class.getName())
                && line.contains("shared objects file"));
    }
}
