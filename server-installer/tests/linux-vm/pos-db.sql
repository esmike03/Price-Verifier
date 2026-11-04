-- A stand-in for the client's POS database, with the same table and column
-- names the pos-sync settings use. Test data only.
CREATE DATABASE IF NOT EXISTS rdmssql CHARACTER SET utf8mb4;
USE rdmssql;
DROP TABLE IF EXISTS RD5500;
CREATE TABLE RD5500 (
    IN_CODE  VARCHAR(20) NOT NULL PRIMARY KEY,
    ITE_DESC VARCHAR(120) NOT NULL,
    UNT_PRIC DECIMAL(12,2) NOT NULL,
    UNT_CODE VARCHAR(10) NULL,
    DEP_CODE VARCHAR(20) NULL
);
INSERT INTO RD5500 VALUES
    ('0000069025310', 'SNICKERS 20G', 25.00, 'PC', 'CANDY'),
    ('0013000002127', 'HEINZ Yellow Mustard', 177.50, 'BTL', 'CONDIMENTS'),
    ('4800016644504', 'Noodles, "Spicy" 55g', 14.75, 'PC', 'GROCERY'),
    ('0000000039826', 'Store code item', 99.99, NULL, 'MISC'),
    ('8851234567890', 'Piña Colada Soda', 45.00, 'CAN', 'DRINKS'),
    ('4806501234567', 'Rice 5kg', 1299.00, 'BAG', NULL);
CREATE USER IF NOT EXISTS 'installer_test'@'%' IDENTIFIED BY 'not-a-real-password';
GRANT SELECT ON rdmssql.RD5500 TO 'installer_test'@'%';
FLUSH PRIVILEGES;
