package io.webboy.verify.labs.perfbook.probe;

/**
 * 역최적화(deoptimization)를 일부러 일으키는 프로브 — 1판 10장 「단형 디스패치」와 「JIT 컴파일 소개」의 가드 설명.
 *
 * <p>1단계: {@code Circle} 만 보이는 호출 지점을 수십만 번 돌려 C2 가 {@code total()} 을 단형 인라인으로 컴파일하게 한다
 * (부모가 {@code -Xbatch} 를 주므로 컴파일은 동기적이다). 2단계: {@code Square} 를 처음 넘긴다 — 타입 가드가 깨져
 * 언커먼 트랩 → {@code -XX:+PrintCompilation} 에 {@code total} 의 {@code made not entrant} 가 찍힌다.
 */
public final class DeoptProbe {

    interface Shape {
        double area();
    }

    static final class Circle implements Shape {
        private final double r;

        Circle(double r) {
            this.r = r;
        }

        @Override
        public double area() {
            return Math.PI * r * r;
        }
    }

    static final class Square implements Shape {
        private final double side;

        Square(double side) {
            this.side = side;
        }

        @Override
        public double area() {
            return side * side;
        }
    }

    private static double total(Shape[] shapes) {
        double sum = 0;
        for (Shape shape : shapes) {
            sum += shape.area();
        }
        return sum;
    }

    public static void main(String[] args) {
        Shape[] circles = new Shape[64];
        for (int i = 0; i < circles.length; i++) {
            circles[i] = new Circle(i + 1);
        }
        double sink = 0;
        for (int i = 0; i < 100_000; i++) {
            sink += total(circles);
        }
        System.out.println("PHASE=monomorphic-warm sink=" + sink);

        Shape[] mixed = circles.clone();
        mixed[0] = new Square(3);
        for (int i = 0; i < 20_000; i++) {
            sink += total(mixed);
        }
        System.out.println("PHASE=after-new-type sink=" + sink);
    }
}
