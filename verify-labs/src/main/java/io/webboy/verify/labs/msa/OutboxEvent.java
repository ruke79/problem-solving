package io.webboy.verify.labs.msa;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateId;
    private String payload;
    private boolean published;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateId, String payload) {
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.published = false;
    }

    public Long getId() {
        return id;
    }

    public String getPayload() {
        return payload;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public boolean isPublished() {
        return published;
    }

    public void markPublished() {
        this.published = true;
    }
}
