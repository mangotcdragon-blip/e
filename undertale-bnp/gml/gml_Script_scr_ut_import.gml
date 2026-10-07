file_delete("ut_res");
file_delete("ut_x_import");
if (ossafe_file_exists("file0"))
{
    var xb = buffer_load("file0");
    buffer_save(xb, "ut_x_file0");
    buffer_delete(xb);
}
scr_ut_request("import");
ut_wait = 2;
ut_timeout = 300;
ut_msg = "Importing...";
ut_msg_t = 9999;
