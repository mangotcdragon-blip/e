var qb = buffer_create(16, buffer_fixed, 1);
buffer_write(qb, buffer_string, argument0);
buffer_save(qb, "ut_req");
buffer_delete(qb);
