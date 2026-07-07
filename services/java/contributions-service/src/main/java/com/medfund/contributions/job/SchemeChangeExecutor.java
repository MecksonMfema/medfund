package com.medfund.contributions.job;

import com.medfund.contributions.repository.SchemeChangeRepository;
import com.medfund.contributions.service.SchemeChangeService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.scheduler.JobExecutor;
import com.medfund.shared.scheduler.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Daily sweep of APPROVED scheme_changes rows whose {@code effective_date}
 * has arrived. Mirrors the group-change branch of {@code
 * ScheduledStatusExecutor} in user-service — per-row errors are logged and
 * swallowed so a single bad row does not block the rest of the sweep.
 *
 * <p>JobDispatcher currently has no distributed lock — dedupe relies on
 * running the scheduler role on a single pod per service.
 */
@Component
public class SchemeChangeExecutor implements JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(SchemeChangeExecutor.class);

    private final SchemeChangeRepository schemeChangeRepository;
    private final SchemeChangeService schemeChangeService;

    public SchemeChangeExecutor(SchemeChangeRepository schemeChangeRepository,
                                SchemeChangeService schemeChangeService) {
        this.schemeChangeRepository = schemeChangeRepository;
        this.schemeChangeService = schemeChangeService;
    }

    @Override
    public JobType getJobType() {
        return JobType.SCHEME_CHANGE_ROLL;
    }

    @Override
    public Mono<Void> execute(String tenantId, String settings) {
        log.info("Rolling due scheme changes for tenant: {}", tenantId);
        LocalDate today = LocalDate.now();
        String actorId = AuditActor.SYSTEM_ID;
        String actorEmail = AuditActor.SYSTEM_EMAIL;

        return schemeChangeRepository.findReadyToApply(today)
                .flatMap(sc -> schemeChangeService.makeEffective(sc.getId(), actorId, actorEmail)
                        .doOnNext(saved -> log.info(
                                "Applied scheme change {} for member {} → scheme {} ({})",
                                saved.getId(), saved.getMemberId(), saved.getToSchemeId(),
                                saved.getChangeKind()))
                        .then(Mono.<Void>empty())
                        .onErrorResume(err -> {
                            log.warn("Scheme change apply failed for {}: {}",
                                    sc.getId(), err.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }
}
