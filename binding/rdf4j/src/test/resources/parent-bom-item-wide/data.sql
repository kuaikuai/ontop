create table parent_bom (
    bom_item_id varchar(20) primary key,
    material_code varchar(20) not null,
    parent_material_code varchar(20),
    bom_code varchar(20),
    unit varchar(10),
    process_code varchar(20),
    bom_version integer,
    quantity decimal(10,2),
    parent_base_qty decimal(10,2)
);

create table material (
    id integer primary key,
    material_code varchar(20) not null,
    material_name varchar(50),
    color varchar(20),
    category_code varchar(20),
    safety_stock decimal(10,2),
    is_critical tinyint,
    procurement_type varchar(10),
    spec_width decimal(10,2),
    spec_length decimal(10,2),
    plant_code varchar(10),
    base_unit varchar(10)
);

insert into parent_bom (bom_item_id, material_code, parent_material_code, bom_code, unit, process_code, bom_version, quantity, parent_base_qty) values
('1', 'M1', 'M0', 'B1', 'PCS', 'P1', 1, 2.0, 1.0),
('2', 'M2', 'M1', 'B2', 'PCS', 'P1', 2, 3.0, 1.5),
('3', 'M3', 'M1', 'B3', 'KG', 'P2', 1, 4.0, 2.0);

insert into material (id, material_code, material_name, color, category_code, safety_stock, is_critical, procurement_type, spec_width, spec_length, plant_code, base_unit) values
(10, 'M1', 'Mat1', 'red', 'C1', 100.0, 1, 'buy', 10.5, 20.5, 'PL1', 'PCS'),
(11, 'M2', 'Mat2', 'blue', 'C2', 200.0, 0, 'make', 11.5, 21.5, 'PL2', 'KG'),
(12, 'M3', 'Mat3', 'green', 'C3', 300.0, 1, 'buy', 12.5, 22.5, 'PL3', 'PCS');
