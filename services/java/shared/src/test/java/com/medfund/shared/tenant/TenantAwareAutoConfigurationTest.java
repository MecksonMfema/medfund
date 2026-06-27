package com.medfund.shared.tenant;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAwareAutoConfigurationTest {

    /**
     * A naked {@link ConnectionFactory} stub — Mockito is unnecessary here
     * because the post-processor only cares about identity / type, never
     * invokes any methods.
     */
    private static ConnectionFactory stubConnectionFactory() {
        return new ConnectionFactory() {
            @Override public org.reactivestreams.Publisher<? extends io.r2dbc.spi.Connection> create() { return null; }
            @Override public ConnectionFactoryMetadata getMetadata() { return () -> "stub"; }
        };
    }

    @Test
    void postProcessor_wrapsRawConnectionFactory() {
        BeanPostProcessor pp = TenantAwareAutoConfiguration.tenantAwareConnectionFactoryPostProcessor();
        ConnectionFactory raw = stubConnectionFactory();

        Object out = pp.postProcessAfterInitialization(raw, "connectionFactory");

        assertThat(out).isInstanceOf(TenantAwareConnectionFactory.class);
        assertThat(out).isNotSameAs(raw);
    }

    @Test
    void postProcessor_leavesAlreadyWrappedFactoryAlone() {
        // Idempotency guard — the post-processor must not double-wrap if it
        // sees a TenantAwareConnectionFactory pass through a second time
        // (e.g. via proxy generation).
        BeanPostProcessor pp = TenantAwareAutoConfiguration.tenantAwareConnectionFactoryPostProcessor();
        ConnectionFactory wrapped = new TenantAwareConnectionFactory(stubConnectionFactory());

        Object out = pp.postProcessAfterInitialization(wrapped, "connectionFactory");

        assertThat(out).isSameAs(wrapped);
    }

    @Test
    void postProcessor_ignoresNonConnectionFactoryBeans() {
        BeanPostProcessor pp = TenantAwareAutoConfiguration.tenantAwareConnectionFactoryPostProcessor();
        String bean = "not a ConnectionFactory";

        Object out = pp.postProcessAfterInitialization(bean, "somethingElse");

        assertThat(out).isSameAs(bean);
    }
}
