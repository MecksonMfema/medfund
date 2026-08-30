"""Report job Kafka pipeline — renamed from ``app/actuarial`` per Phase 15 §1.

Canonical topics are ``medfund.report.job-requested`` and
``medfund.report.job-completed``. Legacy ``medfund.actuarial.job-*`` topics
stay live throughout the rename dual-write window per I10 Phase A; §22
drops them from the producer side, §23 deletes them at the broker.
"""
