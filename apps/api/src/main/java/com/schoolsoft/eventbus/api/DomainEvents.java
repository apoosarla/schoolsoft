package com.schoolsoft.eventbus.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes a domain event to both:
 *  - the chain-scoped outbox table (durable, replayable, queryable for ops)
 *  - the Spring application event bus (in-process, fan-out to listeners)
 *
 * Modulith's {@code @ApplicationModuleListener} consumers will pick up the
 * in-process publication and Spring Modulith persists their state for retry.
 * The outbox row is a coarser, business-facing record.
 */
@Service
public class DomainEvents {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper json = new ObjectMapper();

    public DomainEvents(JdbcTemplate jdbc, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    public void publish(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            jdbc.update(
                "INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload) " +
                "VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), aggregateType, aggregateId, eventType,
                payload == null ? "{}" : json.writeValueAsString(payload)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        publisher.publishEvent(payload);
    }
}
