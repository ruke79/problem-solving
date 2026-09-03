package io.webboy.verify.labs.cloudnative;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 지금 이 테스트를 돌리는 것과 <b>같은 JDK</b> 로 자식 JVM 을 띄운다.
 *
 * <p>2판의 명제 상당수는 "JVM 을 이런 플래그로 띄우면 이렇게 된다"(GC 에르고노믹, 컴팩트 헤더,
 * AOT 캐시, 가상 스레드 스케줄러 병렬성)라서 프로세스 안에서는 관측할 수 없다. 그래서 프로브
 * 클래스({@code probe} 패키지)를 자식 프로세스로 실행하고 표준 출력·오류를 그대로 증거로 쓴다.
 *
 * <p>자식의 클래스패스는 프로브 클래스가 들어 있는 디렉터리/jar 하나뿐이다 — 프로브는 JDK 외에
 * 아무것도 의존하지 않도록 짠다. {@code JAVA_TOOL_OPTIONS} 는 물려주지 않는다(프록시 트러스트스토어
 * 같은 환경 설정이 "Picked up JAVA_TOOL_OPTIONS" 줄로 출력을 오염시키기 때문).
 */
public final class Jvm {

    private Jvm() {
    }

    public record Result(int exitCode, String stdout, String stderr) {
        public String all() {
            return stdout + stderr;
        }

        public boolean mentions(String text) {
            return all().contains(text);
        }
    }

    /** {@code java <jvmArgs> -cp <probe classpath> <mainClass> <args>} 를 실행한다. */
    public static Result run(List<String> jvmArgs, Class<?> mainClass, String... args) throws Exception {
        return run(jvmArgs, probeClasspath(mainClass), mainClass, args);
    }

    /** 클래스패스를 직접 준다 — AOT/CDS 는 디렉터리 클래스패스를 거부하므로 jar 가 필요한 케이스(CN-06A)가 쓴다. */
    public static Result run(List<String> jvmArgs, String classpath, Class<?> mainClass, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass.getName());
        command.addAll(List.of(args));
        return execute(command, 120);
    }

    /**
     * 프로브 클래스 하나(와 같은 패키지의 중첩 클래스)를 담은 임시 jar 를 만든다.
     * CDS·AOT 캐시는 "Cannot have non-empty directory in paths" 로 디렉터리 클래스패스를 거부한다 — 첫 판 CN-06A 가 그렇게 실패했다.
     */
    public static Path jarOf(Class<?> mainClass, Path directory) throws Exception {
        Path root = Path.of(probeClasspath(mainClass));
        String prefix = mainClass.getName().replace('.', '/');
        Path jar = directory.resolve(mainClass.getSimpleName().toLowerCase() + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar));
             var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String entry = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (!entry.startsWith(prefix)) {
                    continue;
                }
                out.putNextEntry(new JarEntry(entry));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    /** {@code java <jvmArgs> -version} — 플래그 수용 여부와 기동 로그만 볼 때 쓴다. */
    public static Result version(String... jvmArgs) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(List.of(jvmArgs));
        command.add("-version");
        return execute(command, 60);
    }

    public static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static String probeClasspath(Class<?> mainClass) throws Exception {
        return new File(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
    }

    private static Result execute(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.start();
        // 출력이 커도(-Xlog:class+load 는 수천 줄) 파이프가 막히지 않게 둘 다 별도 스레드로 읽는다
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("자식 JVM 이 " + timeoutSeconds + "초 안에 끝나지 않았다: " + command);
        }
        return new Result(process.exitValue(),
                new String(out.join(), StandardCharsets.UTF_8),
                new String(err.join(), StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
