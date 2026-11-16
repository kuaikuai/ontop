create table parent_bom (
    bom_item_id integer primary key,
    material_code varchar(20) not null,
    parent_material_code varchar(20),
    bom_version integer
);

create table material (
    material_code varchar(20) primary key,
    material_name varchar(50),
    color varchar(20),
    category_code varchar(20),
    safety_stock varchar(20),
    is_critical varchar(20),
    procurement_type varchar(20),
    spec_width varchar(20),
    spec_length varchar(20),
    plant_code varchar(20),
    base_unit varchar(20),
    spec_thickness varchar(20),
    density varchar(20),
    material_type varchar(20),
    material_desc varchar(20)
);

insert into parent_bom (bom_item_id, material_code, parent_material_code, bom_version) values
(1, 'M1', 'M0', 1),
(2, 'M2', 'M1', 2),
(3, 'M3', 'M1', 1);

insert into material (material_code, material_name, color, category_code, safety_stock, is_critical,
    procurement_type, spec_width, spec_length, plant_code, base_unit, spec_thickness, density,
    material_type, material_desc) values
('M0', 'Mat0', 'red', 'C0', '10', 'N', 'buy', '1', '2', 'P0', 'EA', '3', '4', 'T0', 'D0'),
('M1', 'Mat1', 'blue', 'C1', '11', 'N', 'buy', '1', '2', 'P1', 'EA', '3', '4', 'T1', 'D1'),
('M2', 'Mat2', 'green', 'C2', '12', 'Y', 'make', '1', '2', 'P2', 'EA', '3', '4', 'T2', 'D2'),
('M3', 'Mat3', 'black', 'C3', '13', 'N', 'buy', '1', '2', 'P3', 'EA', '3', '4', 'T3', 'D3');
