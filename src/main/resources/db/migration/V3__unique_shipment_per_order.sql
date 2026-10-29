-- One shipment per order (I-6). Existing data may already hold duplicates (a replayed payment.completed or a
-- race): keep the earliest row (lowest id) per order. Its shipment_events stay; the duplicates' events go with
-- them (ON DELETE CASCADE on shipment_events.shipment_id).
DELETE FROM shipments
WHERE id NOT IN (SELECT MIN(id) FROM shipments GROUP BY order_id);

ALTER TABLE shipments ADD CONSTRAINT uq_shipments_order_id UNIQUE (order_id);

-- the unique constraint's index replaces the plain one
DROP INDEX IF EXISTS idx_shipments_order_id;
