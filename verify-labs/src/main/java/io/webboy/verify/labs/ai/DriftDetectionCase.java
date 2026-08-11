package io.webboy.verify.labs.ai;

import io.webboy.verify.core.Evidence;
import io.webboy.verify.core.VerificationCase;
import org.springframework.stereotype.Component;

/** Q77 — 정답 라벨이 늦게 오는 상황에서 대리 지표(PSI)로 드리프트를 감지한다. */
@Component
public class DriftDetectionCase extends VerificationCase {

    /** 업계 관례: 0.1 미만 안정, 0.1~0.25 관찰, 0.25 이상 유의미한 드리프트. */
    private static final double WATCH_THRESHOLD = 0.1;
    private static final double ALERT_THRESHOLD = 0.25;

    @Override
    public String id() {
        return "AI-02";
    }

    @Override
    public String category() {
        return "ai";
    }

    @Override
    public String question() {
        return "모델 드리프트는 무엇이고 운영에서 어떻게 대응합니까?";
    }

    @Override
    public String claim() {
        return "정답 라벨은 시차가 있거나 아예 없으므로, 입력 특징량 분포의 통계적 괴리(PSI 등)를 대리 지표로 계속 측정해 임계치 초과 시 알린다";
    }

    @Override
    protected void verify(Evidence evidence) {
        double[] baseline = {0.10, 0.20, 0.40, 0.20, 0.10};
        double[] sameShape = {0.10, 0.20, 0.40, 0.20, 0.10};
        double[] slightShift = {0.12, 0.22, 0.36, 0.19, 0.11};
        double[] majorShift = {0.35, 0.30, 0.20, 0.10, 0.05};

        double psiSame = psi(baseline, sameShape);
        double psiSlight = psi(baseline, slightShift);
        double psiMajor = psi(baseline, majorShift);

        evidence.fact("기준 분포", java.util.Arrays.toString(baseline));
        evidence.fact("동일 분포 PSI", String.format("%.4f", psiSame));
        evidence.fact("소폭 변화 PSI", String.format("%.4f", psiSlight));
        evidence.fact("대폭 변화 PSI", String.format("%.4f", psiMajor));
        evidence.fact("관찰/경보 임계치", WATCH_THRESHOLD + " / " + ALERT_THRESHOLD);

        evidence.expect("분포가 같으면 PSI 는 0 이다", psiSame < 1e-9);
        evidence.expect("소폭 변화는 관찰 구간에 머문다", psiSlight < WATCH_THRESHOLD);
        evidence.expect("대폭 변화는 경보 임계치를 넘는다", psiMajor > ALERT_THRESHOLD);
        evidence.expect("PSI 는 변화 크기에 단조 증가한다", psiSame < psiSlight && psiSlight < psiMajor);

        evidence.note("Data Drift(입력 분포 변화)와 Concept Drift(입력과 정답의 관계 변화)는 원인이 달라 구분해 다뤄야 한다. PSI 는 전자만 잡는다.");
        evidence.note("재학습을 자동화해도 평가 기준 자체가 오래되면 의미가 없다 — 비즈니스 쪽과 정기적으로 지표를 맞춘다.");
        evidence.note("새 모델은 바로 전면 반영하지 않고 섀도 모드나 일부 트래픽 카나리로 기존 모델과 비교한 뒤 전환한다.");
    }

    private double psi(double[] expected, double[] actual) {
        double sum = 0;
        for (int i = 0; i < expected.length; i++) {
            double e = Math.max(expected[i], 1e-6);
            double a = Math.max(actual[i], 1e-6);
            sum += (a - e) * Math.log(a / e);
        }
        return sum;
    }
}
