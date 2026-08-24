package com.medfund.user.status;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.LifePolicy;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import com.medfund.user.repository.LifePolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.UUID;

@Service
public class LifePolicyStatusTransitionService extends PolicyStatusTransitionService<LifePolicy> {

    public LifePolicyStatusTransitionService(LifePolicyRepository repository,
                                             StatusTransitionRecorder recorder,
                                             AuditPublisher auditPublisher,
                                             PolicyStatusChangedPublisher policyStatusChangedPublisher,
                                             TransactionalOperator tx) {
        super(repository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected String policySource() { return "LIFE_POLICY"; }
    @Override protected String insuranceLine() { return "LIFE"; }
    @Override protected String getStatus(LifePolicy e) { return e.getStatus(); }
    @Override protected LifePolicy setStatus(LifePolicy e, String s) { e.setStatus(s); return e; }
    @Override protected UUID getId(LifePolicy e) { return e.getId(); }
    @Override protected String getEntityName(LifePolicy e) { return e.getPolicyNumber(); }
}
