-- Transactional outbox (K4): events are written here in the same transaction as the state change and
-- published to Kafka by OutboxRelay. A row exists until Kafka acknowledges it, then it is deleted.
-- parked_at is set for an event that can never be published (too large, invalid topic); it stays for
-- inspection: UPDATE outbox_event SET parked_at = NULL WHERE id = ? sends it again.
CREATE TABLE outbox_event (
    id         BIGSERIAL    PRIMARY KEY,
    topic      VARCHAR(255) NOT NULL,
    event_key  VARCHAR(255),
    payload    TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    attempts   INTEGER      NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    parked_at  TIMESTAMP
);
CREATE INDEX idx_outbox_event_pending ON outbox_event (parked_at, id);
