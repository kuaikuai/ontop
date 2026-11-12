create table parent_bom (
    bom_item_id integer primary key,
    material_code varchar(20) not null,
    parent_material_code varchar(20)
);

insert into parent_bom (bom_item_id, material_code, parent_material_code) values
(1, 'M1', 'M0'),
(2, 'M2', 'M1'),
(3, 'M3', 'M1');
