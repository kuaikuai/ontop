create table material (
    id integer primary key,
    material_code varchar(20) not null
);

create table bom_item (
    id integer primary key,
    material_code varchar(20) not null
);

create table material_mould_output (
    id integer primary key,
    material_code varchar(20) not null
);

create table material_equipment_capability (
    id integer primary key,
    material_code varchar(20) not null
);

create table line_side_stock (
    id integer primary key,
    material_code varchar(20) not null
);

insert into material (id, material_code) values (1, '1649607'), (2, '999');
insert into bom_item (id, material_code) values (3, '1649607'), (4, '999');
insert into material_mould_output (id, material_code) values (5, '1649607'), (6, '999');
insert into material_equipment_capability (id, material_code) values (7, '1649607'), (8, '999');
insert into line_side_stock (id, material_code) values (9, '1649607'), (10, '999');
