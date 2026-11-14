package com.elipair.church.global.viewcount;

import com.elipair.church.global.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 조회수 버퍼를 주기적으로 DB에 반영한다(스펙 §9). VIEW_FLUSH_INTERVAL(ms, 기본 60000)마다 실행.
 * 등록된 모든 ViewCountFlushTarget에 대해 store.drain → 교회별 target.applyDeltas. 크래시 시 최대 1주기 유실(허용).
 */
@Slf4j
@Component
public class ViewCountFlushScheduler {

    private final ViewCountStore store;
    private final List<ViewCountFlushTarget> targets;

    public ViewCountFlushScheduler(ViewCountStore store, List<ViewCountFlushTarget> targets) {
        this.store = store;
        this.targets = targets;
    }

    @Scheduled(fixedDelayString = "${view.flush-interval:60000}")
    public void flush() {
        for (ViewCountFlushTarget target : targets) {
            String namespace = target.namespace();
            store.drain(namespace).forEach((churchId, deltas) -> flushChurch(target, namespace, churchId, deltas));
        }
    }

    /**
     * 한 교회 분을 반영한다(#86). 이 잡은 요청 밖에서 돌아 교회가 지정돼 있지 않으므로 runAs로 교회를 지정한다.
     * 테넌트는 applyDeltas가 트랜잭션을 열 때 고정되므로 runAs는 그 바깥이어야 한다(이 메서드에 트랜잭션을 걸지 말 것).
     */
    private void flushChurch(ViewCountFlushTarget target, String namespace, long churchId, Map<Long, Long> deltas) {
        try {
            TenantContext.runAs(churchId, () -> target.applyDeltas(deltas));
            log.debug("조회수 플러시: church={}, namespace={}, rows={}", churchId, namespace, deltas.size());
        } catch (Exception e) {
            // 반영 실패 시 드레인분을 버퍼에 재적재해 다음 주기에 재시도(유실 방지).
            // applyDeltas는 @Transactional이라 실패 시 롤백 → 부분커밋 없음 → 전량 재적재가 안전.
            // 교회·target 단위로 격리해 한 곳의 실패가 다른 교회·도메인 플러시를 막지 않게 한다.
            deltas.forEach((id, delta) -> store.incrementBy(churchId, namespace, id, delta));
            log.error("조회수 플러시 실패 — 버퍼 재적재: church={}, namespace={}, rows={}", churchId, namespace, deltas.size(), e);
        }
    }
}
