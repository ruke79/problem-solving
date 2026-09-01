package io.webboy.springtutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static io.webboy.springtutorial.Lesson.fact;
import static io.webboy.springtutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레슨 4 — 트랜잭션 경계 (면접 Q35 · Q37 · Q38 · Q39 · Q42 · Q43 · Q49)
 *
 * <p><b>이 레슨에는 DB 가 없다.</b> 대신 BEGIN/COMMIT/ROLLBACK 을 기록하는 트랜잭션 매니저를
 * 꽂았다. 전파(propagation)와 롤백 규칙의 판단 로직은 DB 가 아니라 스프링의
 * {@code AbstractPlatformTransactionManager} 안에 있으므로, <b>여기서 검증하는 것은 실물</b>이다.
 *
 * <p>단, 이것이 보여주는 것은 <b>스프링이 내리는 경계 결정</b>까지다. 격리 수준·실제 롤백 같은
 * DB 의미론은 여기 없다 — 그것은 실물 PostgreSQL 로 검증하는 {@code verify-labs} 소관이다.
 */
@DisplayName("레슨 4. 트랜잭션 경계 — 어디서 열리고 언제 되돌아가는가")
class Lesson04_TransactionBoundary {

    @Test
    @DisplayName("4-1. 트랜잭션은 프록시 경계에서 열리고 닫힌다 (Q37)")
    void transactionOpensAtTheProxyBoundary() {
        withContext((service, tx) -> {
            fact("메서드 밖에서 트랜잭션 활성인가", TransactionSynchronizationManager.isActualTransactionActive());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

            boolean activeInside = service.wasActiveInside();

            fact("@Transactional 메서드 안에서는", activeInside);
            fact("트랜잭션 매니저가 기록한 것", tx.log);

            assertThat(activeInside).isTrue();
            assertThat(tx.log).containsExactly("BEGIN#1", "COMMIT#1");
        });

        lesson("어노테이션이 마법이 아니다 — 프록시가 '들어갈 때 begin, 나올 때 commit' 을 해 줄 뿐이다");
    }

    @Test
    @DisplayName("4-2. 기본값: 언체크 예외만 롤백, 체크 예외는 커밋된다 (Q42) ★가장 위험")
    void checkedExceptionsCommitByDefault() {
        // 언체크(RuntimeException) — 롤백된다
        withContext((service, tx) -> {
            assertThatThrownBy(service::failsUnchecked).isInstanceOf(IllegalStateException.class);
            fact("언체크 예외 후 기록", tx.log);
            assertThat(tx.log).containsExactly("BEGIN#1", "ROLLBACK#1");
        });

        // 체크(Exception) — 예외가 났는데도 커밋된다!
        withContext((service, tx) -> {
            assertThatThrownBy(service::failsChecked).isInstanceOf(Exception.class);
            fact("체크 예외 후 기록", tx.log);
            assertThat(tx.log).containsExactly("BEGIN#1", "COMMIT#1");   // ← ROLLBACK 이 아니다
        });

        lesson("'예외가 나면 롤백'이 아니다 — '언체크 예외가 프록시를 통과하면 롤백'이다");
        lesson("체크 예외로 실패를 알리는 설계라면 rollbackFor 를 반드시 붙여야 한다");
    }

    @Test
    @DisplayName("4-3. rollbackFor 가 그 기본값을 바꾼다 (Q42)")
    void rollbackForOverridesTheDefault() {
        withContext((service, tx) -> {
            assertThatThrownBy(service::failsCheckedWithRollbackFor).isInstanceOf(Exception.class);
            fact("rollbackFor=Exception 기록", tx.log);
            assertThat(tx.log).containsExactly("BEGIN#1", "ROLLBACK#1");   // 이번엔 롤백된다
        });

        lesson("@Transactional(rollbackFor = Exception.class) — 체크 예외까지 롤백 대상으로 넓힌다");
    }

    @Test
    @DisplayName("4-4. 메서드 안에서 catch 하면 롤백은 없다 (Q43)")
    void caughtExceptionsNeverReachTheProxy() {
        withContext((service, tx) -> {
            service.catchesItsOwnFailure();   // 예외가 밖으로 안 나간다

            fact("catch 한 경우의 기록", tx.log);
            assertThat(tx.log).containsExactly("BEGIN#1", "COMMIT#1");   // 아무 일도 없던 것처럼 커밋

            lesson("롤백 판정은 프록시가 한다. 프록시까지 예외가 안 오면 판정할 재료 자체가 없다");
            lesson("잡아서 복구할 게 아니라면, 잡지 말고 흘려보내는 것이 트랜잭션적으로 옳다");
        });
    }

    @Test
    @DisplayName("4-5. REQUIRED 는 참여, REQUIRES_NEW 는 중단하고 새로 연다 (Q38·Q39)")
    void propagationRequiredVsRequiresNew() {
        // REQUIRED(기본값) — 바깥 트랜잭션에 올라탄다. BEGIN 은 한 번뿐이다
        withContext((service, tx) -> {
            service.outerCallingRequiredInner();
            fact("REQUIRED 참여 기록", tx.log);
            assertThat(tx.log).containsExactly("BEGIN#1", "COMMIT#1");
        });

        // REQUIRES_NEW — 바깥을 잠시 멈추고(suspend) 새 트랜잭션을 연다
        withContext((service, tx) -> {
            service.outerCallingRequiresNewInner();
            fact("REQUIRES_NEW 기록", tx.log);
            assertThat(tx.log).containsExactly(
                    "BEGIN#1", "SUSPEND#1", "BEGIN#2", "COMMIT#2", "RESUME#1", "COMMIT#1");
        });

        lesson("REQUIRES_NEW 는 '안쪽만 따로 확정'이 필요할 때 쓴다 — 감사 로그, 실패 기록 등");
        lesson("바깥이 커넥션을 쥔 채 안쪽이 또 하나를 요구한다 — 풀 고갈 데드락의 단골 원인(Q39)");
    }

    @Test
    @DisplayName("4-6. 자기 호출로는 REQUIRES_NEW 도 헛수고다 (Q35·Q47) ★레슨 3 의 재현")
    void selfInvocationIgnoresPropagation() {
        withContext((service, tx) -> {
            service.outerCallingInnerViaThis();   // this.requiresNewInner()

            fact("자기 호출 기록", tx.log);
            // SUSPEND 도 BEGIN#2 도 없다 — 어노테이션이 통째로 무시됐다
            assertThat(tx.log).containsExactly("BEGIN#1", "COMMIT#1");
        });

        lesson("레슨 3-2 의 자기 호출 함정이 트랜잭션에서 그대로 재현된다 — 예외도 경고도 없이");
    }

    @Test
    @DisplayName("4-7. 참여한 안쪽의 실패는 바깥의 커밋까지 뒤집는다 (Q43) ★변별력")
    void innerFailurePoisonsTheOuterTransaction() {
        withContext((service, tx) -> {
            // 바깥은 안쪽(REQUIRED 참여)의 예외를 catch 해서 '복구했다'고 생각한다.
            // 그러나 안쪽 프록시가 이미 공유 트랜잭션에 rollback-only 를 새겨 놨다.
            assertThatThrownBy(service::outerCatchingInnerFailure)
                    .isInstanceOf(UnexpectedRollbackException.class);

            fact("기록", tx.log);
            fact("바깥이 받은 예외", "UnexpectedRollbackException — 커밋하려는 순간에 터진다");

            assertThat(tx.log).containsExactly("BEGIN#1", "SET_ROLLBACK_ONLY#1", "ROLLBACK#1");
        });

        lesson("같은 트랜잭션을 나눠 쓰는 한, '안쪽 실패를 잡아서 없던 일로' 는 불가능하다");
        lesson("안쪽 실패를 살리고 싶으면 REQUIRES_NEW 로 트랜잭션 자체를 분리해야 한다");
    }

    @Test
    @DisplayName("4-8. '커밋 후에 실행'은 동기화 콜백으로 보장한다 (Q45·Q49)")
    void afterCommitCallbacksFireOnlyOnCommit() {
        // 커밋되면 → afterCommit 이 불린다
        withContext((service, tx) -> {
            List<String> sideEffects = new ArrayList<>();
            service.registerAfterCommit(sideEffects);

            fact("커밋된 경우의 부수효과", sideEffects);
            assertThat(sideEffects).containsExactly("커밋 후 알림 발송");
        });

        // 롤백되면 → 불리지 않는다. 이게 '트랜잭션 안에서 직접 알림을 쏘면 안 되는' 이유다
        withContext((service, tx) -> {
            List<String> sideEffects = new ArrayList<>();
            assertThatThrownBy(() -> service.registerAfterCommitThenFail(sideEffects))
                    .isInstanceOf(IllegalStateException.class);

            fact("롤백된 경우의 부수효과", sideEffects);
            assertThat(sideEffects).isEmpty();   // 알림이 나가지 않았다 — 정확히 원하는 동작
        });

        lesson("메일·메시지 발행을 트랜잭션 안에서 직접 하면, 롤백돼도 발송은 못 돌린다(Q45)");
        lesson("@TransactionalEventListener(AFTER_COMMIT) 의 바탕이 바로 이 동기화 콜백이다");
    }

    // ── 테스트 골격 ────────────────────────────────────────────────

    private void withContext(java.util.function.BiConsumer<BankService, RecordingTransactionManager> body) {
        try (var context = new AnnotationConfigApplicationContext(TxConfig.class)) {
            body.accept(context.getBean(BankService.class),
                    context.getBean(RecordingTransactionManager.class));
        }
    }

    /**
     * BEGIN/COMMIT/ROLLBACK/SUSPEND 를 기록만 하는 트랜잭션 매니저.
     * 전파·롤백 규칙의 판단은 전부 부모 클래스(스프링 실물)가 한다.
     */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final List<String> log = new ArrayList<>();
        // JDBC 에서 ConnectionHolder 가 하는 역할 — 참여자들이 '공유'하는 트랜잭션 상태.
        // 이게 스레드에 붙어 있어야 안쪽의 rollback-only 표식이 바깥의 커밋 시도에 보인다.
        private final ThreadLocal<TxState> current = new ThreadLocal<>();
        private int sequence;

        static final class TxState {
            final int id;
            boolean rollbackOnly;

            TxState(int id) {
                this.id = id;
            }
        }

        /**
         * {@link SmartTransactionObject} 구현이 필수다 — 바깥 트랜잭션의 커밋 시도가
         * "누가 rollback-only 를 새겼나"를 이 인터페이스로 물어보기 때문이다.
         * 처음에 이것 없이 만들었더니 4-7 의 UnexpectedRollbackException 이 나오지 않았다.
         */
        static final class TxObject implements SmartTransactionObject {
            TxState state;   // null 이면 진행 중인 트랜잭션 없음

            @Override
            public boolean isRollbackOnly() {
                return state != null && state.rollbackOnly;
            }
        }

        @Override
        protected Object doGetTransaction() {
            var tx = new TxObject();
            tx.state = current.get();
            return tx;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TxObject) transaction).state != null;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            var state = new TxState(++sequence);
            ((TxObject) transaction).state = state;
            current.set(state);
            log.add("BEGIN#" + state.id);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            log.add("COMMIT#" + ((TxObject) status.getTransaction()).state.id);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            log.add("ROLLBACK#" + ((TxObject) status.getTransaction()).state.id);
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            var state = ((TxObject) status.getTransaction()).state;
            state.rollbackOnly = true;
            log.add("SET_ROLLBACK_ONLY#" + state.id);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            TxState suspended = current.get();
            current.remove();
            ((TxObject) transaction).state = null;
            log.add("SUSPEND#" + suspended.id);
            return suspended;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            var state = (TxState) suspendedResources;
            current.set(state);
            log.add("RESUME#" + state.id);
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            current.remove();
        }
    }

    static class BankService {
        private ObjectProvider<BankService> self;

        @Transactional
        public boolean wasActiveInside() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Transactional
        public void failsUnchecked() {
            throw new IllegalStateException("언체크 실패");
        }

        @Transactional
        public void failsChecked() throws Exception {
            throw new Exception("체크 실패");
        }

        @Transactional(rollbackFor = Exception.class)
        public void failsCheckedWithRollbackFor() throws Exception {
            throw new Exception("체크 실패 — 하지만 rollbackFor 가 있다");
        }

        @Transactional
        public void catchesItsOwnFailure() {
            try {
                throw new IllegalStateException("안에서 잡힌 실패");
            } catch (IllegalStateException ignored) {
                // 복구했다고 치자
            }
        }

        @Transactional
        public void outerCallingRequiredInner() {
            self.getObject().requiredInner();
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void requiredInner() {
        }

        @Transactional
        public void outerCallingRequiresNewInner() {
            self.getObject().requiresNewInner();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNewInner() {
        }

        @Transactional
        public void outerCallingInnerViaThis() {
            requiresNewInner();   // this. — 프록시를 지나가지 않는다
        }

        @Transactional
        public void outerCatchingInnerFailure() {
            try {
                self.getObject().requiredInnerThatFails();
            } catch (IllegalStateException ignored) {
                // '복구했다'고 생각하지만, 트랜잭션에는 이미 rollback-only 가 새겨졌다
            }
        }

        @Transactional
        public void requiredInnerThatFails() {
            throw new IllegalStateException("참여한 안쪽의 실패");
        }

        @Transactional
        public void registerAfterCommit(List<String> sideEffects) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sideEffects.add("커밋 후 알림 발송");
                }
            });
        }

        @Transactional
        public void registerAfterCommitThenFail(List<String> sideEffects) {
            registerAfterCommit(sideEffects);   // 같은 트랜잭션에 콜백만 건다 (자기 호출이지만 여기선 등록이 목적)
            throw new IllegalStateException("등록 후 실패 → 롤백");
        }

        void setSelf(ObjectProvider<BankService> self) {
            this.self = self;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TxConfig {
        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        BankService bankService(ObjectProvider<BankService> self) {
            var service = new BankService();
            service.setSelf(self);
            return service;
        }
    }
}
