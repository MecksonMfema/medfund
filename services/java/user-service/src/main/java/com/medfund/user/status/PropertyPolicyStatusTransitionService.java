package com.medfund.user.status;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.Property;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import com.medfund.user.repository.PropertyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.UUID;

@Service
public class PropertyPolicyStatusTransitionService extends PolicyStatusTransitionService<Property> {

    public PropertyPolicyStatusTransitionService(PropertyRepository repository,
                                                 StatusTransitionRecorder recorder,
                                                 AuditPublisher auditPublisher,
                                                 PolicyStatusChangedPublisher policyStatusChangedPublisher,
                                                 TransactionalOperator tx) {
        super(repository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected String policySource() { return "PROPERTY_POLICY"; }
    @Override protected String insuranceLine() { return "PROPERTY"; }
    @Override protected String getStatus(Property e) { return e.getStatus(); }
    @Override protected Property setStatus(Property e, String s) { e.setStatus(s); return e; }
    @Override protected UUID getId(Property e) { return e.getId(); }
    @Override protected String getEntityName(Property e) { return e.getPropertyName(); }
}
