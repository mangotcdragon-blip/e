if (ossafe_file_exists("file0") == 0)
{
    ut_msg = "There is no save#to export yet.";
    ut_msg_t = 180;
    exit;
}
file_delete("ut_res");
var xb = buffer_load("file0");
buffer_save(xb, "ut_x_file0");
buffer_delete(xb);
scr_ut_request("export");
ut_wait = 1;
ut_timeout = 300;
ut_msg = "Exporting...";
ut_msg_t = 9999;
