package io.webboy.verify.labs.perfbook;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 지금 테스트를 돌리는 것과 같은 JDK 로 자식 JVM 을 띄운다 — GC 로그 형식(PERF-08A)이나 컴파일 로그(PERF-10D)처럼
 * 프로세스 안에서는 볼 수 없는 것을 관측하기 위해서다. {@code verify-labs-cloudnative} 의 {@code Jvm} 과 같은 도구다.
 *
 * <p>자식의 클래스패스는 프로브 클래스가 든 디렉터리 하나뿐이다 — 프로브({@code probe} 패키지)는 JDK 외에 아무것도
 * 의존하지 않는다. {@code JAVA_TOOL_OPTIONS} 는 물려주지 않는다("Picked up ..." 줄이 출력을 오염시킨다).
 */
public final class ChildJvm {

    private ChildJvm() {
    }

    public record Result(int exitCode, String stdout, String stderr) {
        public String all() {
            return stdout + stderr;
        }

        public boolean mentions(String text) {
            return all().contains(text);
        }
    }

    public static Result run(List<String> jvmArgs, Class<?> mainClass, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(new File(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
        command.add(mainClass.getName());
        command.addAll(List.of(args));
        return execute(command);
    }

    public static Result version(String... jvmArgs) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(List.of(jvmArgs));
        command.add("-version");
        return execute(command);
    }

    public static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static Result execute(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.start();
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("자식 JVM 이 120초 안에 끝나지 않았다: " + command);
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
