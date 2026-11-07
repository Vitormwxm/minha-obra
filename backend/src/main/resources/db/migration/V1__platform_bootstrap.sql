CREATE TABLE platform_bootstrap (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    initialized_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE platform_bootstrap IS
    'Technical initialization marker. No user or financial data is stored here.';

INSERT INTO platform_bootstrap (id) VALUES (1);
