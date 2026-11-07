CREATE TABLE customers (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    region TEXT NOT NULL
);

CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL,
    amount REAL NOT NULL,
    order_date TEXT NOT NULL,
    status TEXT NOT NULL
);

CREATE TABLE inventory (
    product_id TEXT PRIMARY KEY,
    stock INTEGER NOT NULL,
    ordered INTEGER NOT NULL,
    unit_cost REAL NOT NULL
);

CREATE TABLE daily_sales (
    sale_date TEXT PRIMARY KEY,
    revenue REAL NOT NULL
);

CREATE TABLE categories (
    id INTEGER PRIMARY KEY,
    category TEXT NOT NULL
);

CREATE TABLE products (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    category_id INTEGER
);

INSERT INTO customers (id, name, region) VALUES
    (1, 'Acme', 'North'),
    (2, 'Globex', 'West'),
    (3, 'Initech', 'East'),
    (4, 'Umbrella', 'South');

INSERT INTO orders (id, customer_id, amount, order_date, status) VALUES
    (1, 1, 100.0, '2026-10-28', 'completed'),
    (2, 1, 150.0, '2026-10-29', 'completed'),
    (3, 2, 250.0, '2026-10-30', 'completed'),
    (4, 3, 300.0, '2026-10-31', 'completed'),
    (5, 3, 200.0, '2026-11-01', 'completed'),
    (6, 2, 75.0, '2026-11-02', 'cancelled');

INSERT INTO inventory (product_id, stock, ordered, unit_cost) VALUES
    ('P-100', 15, 7, 12.5),
    ('P-200', 30, 25, 4.0),
    ('P-300', 10, 10, 8.0);

INSERT INTO daily_sales (sale_date, revenue) VALUES
    ('2026-10-29', 100.5),
    ('2026-10-30', 180.0),
    ('2026-10-31', 80.25),
    ('2026-11-01', 300.0),
    ('2026-11-02', 150.0);

INSERT INTO categories (id, category) VALUES
    (1, 'Hardware'),
    (2, 'Software');

INSERT INTO products (id, name, category_id) VALUES
    (1, 'Widget', 1),
    (2, 'Gadget', 2),
    (3, 'Mystery', NULL);
