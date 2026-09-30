-- Seed catalogue. The last two products exist to make failure paths reachable on demand -
-- see docs/DEMO.md.
INSERT INTO inventory_item (product_id, available_qty, reserved_qty, version) VALUES
    ('P-LAPTOP',        50,  0, 0),
    ('P-KEYBOARD',     200,  0, 0),
    ('P-MOUSE',        200,  0, 0),
    ('P-MONITOR',       30,  0, 0),
    ('P-HEADPHONES',   120,  0, 0),
    ('P-WEBCAM',         5,  0, 0),
    -- Zero stock: any order for this is rejected, triggering the compensation-free failure path.
    ('P-OUT-OF-STOCK',   0,  0, 0),
    -- Plenty of stock, but the consumer throws on it: exercises retries and the dead-letter topic.
    ('P-BOOM',         100,  0, 0);