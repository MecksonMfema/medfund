package com.medfund.keycloak;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers the MedFund Kafka event listener with Keycloak's SPI mechanism.
 *
 * <h3>Installation</h3>
 * <ol>
 *   <li>Build: {@code ./gradlew build} → produces {@code build/libs/keycloak-event-listener-1.0.0.jar}</li>
 *   <li>Copy the JAR to Keycloak's {@code providers/} directory and restart Keycloak.</li>
 *   <li>In each realm: <em>Realm Settings → Events → Event Listeners → add {@code medfund-kafka}</em></li>
 * </ol>
 *
 * <h3>Configuration</h3>
 * Set the Kafka bootstrap servers via an environment variable or {@code keycloak.conf}:
 * <pre>
 *   # keycloak.conf
 *   spi-events-listener-medfund-kafka-bootstrap-servers=kafka:9092
 *
 *   # or environment variable
 *   KC_SPI_EVENTS_LISTENER_MEDFUND_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
 * </pre>
 */
public class MedfundEventListenerProviderFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "medfund-kafka";

    private SecurityEventPublisher publisher;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new MedfundEventListenerProvider(publisher);
    }

    @Override
    public void init(Config.Scope config) {
        String bootstrapServers = config.get("bootstrap-servers", "localhost:9092");
        publisher = new SecurityEventPublisher(bootstrapServers);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (publisher != null) publisher.close();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
