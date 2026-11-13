create table parent_bom (
    bom_item_id integer primary key,
    material_code varchar(20) not null,
    parent_material_code varchar(20),
    bom_version integer
);

create table material (
    material_code varchar(20) primary key,
    material_name varchar(50)
);

insert into parent_bom (bom_item_id, material_code, parent_material_code, bom_version) values
(1, 'M1', 'M0', 1),
(2, 'M2', 'M1', 2),
(3, 'M3', 'M1', 1);

insert into material (material_code, material_name) values
('M0', 'Mat0'),
('M1', 'Mat1'),
('M2', 'Mat2'),
('M3', 'Mat3');
