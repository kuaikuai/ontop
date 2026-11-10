create table t1 (
    id integer primary key,
    code varchar(20) not null
);

create table t2 (
    id integer primary key,
    code varchar(20) not null
);

insert into t1 (id, code) values (1, 'c1'), (2, 'c2');
insert into t2 (id, code) values (3, 'c3'), (4, 'c4');
