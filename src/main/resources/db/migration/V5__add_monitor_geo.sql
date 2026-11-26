    -- V5__add_monitor_geo.sql
-- Stores optional resolved IP and GeoIP coordinates for the monitored target server.

ALTER TABLE monitors
    ADD COLUMN target_ip   VARCHAR(45),
    ADD COLUMN geo_country VARCHAR(100),
    ADD COLUMN geo_city    VARCHAR(100),
    ADD COLUMN geo_lat     DOUBLE PRECISION,
    ADD COLUMN geo_lon     DOUBLE PRECISION;
