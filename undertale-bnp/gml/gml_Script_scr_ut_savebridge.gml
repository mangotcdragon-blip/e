if (ut_wait == 1 || ut_wait == 2)
{
    ut_timeout -= 1;
    if (file_exists("ut_res"))
    {
        var rb = buffer_load("ut_res");
        var res = buffer_read(rb, buffer_string);
        buffer_delete(rb);
        file_delete("ut_res");
        var res_ok = string_char_at(res, 1) == "1";
        ut_msg = string_delete(res, 1, 1);
        ut_msg_t = 240;
        if (ut_wait == 2 && res_ok)
        {
            var ib = buffer_load("ut_x_import");
            var imp = buffer_read(ib, buffer_string);
            var nl = 0;
            var rest = imp;
            var ln;
            while (string_length(rest) > 0 && nl < 600)
            {
                var p = string_pos("\n", rest);
                var one;
                if (p > 0)
                {
                    one = string_copy(rest, 1, p - 1);
                    rest = string_delete(rest, 1, p);
                }
                else
                {
                    one = rest;
                    rest = "";
                }
                ln[nl] = string_replace_all(one, "\r", "");
                nl += 1;
            }
            if (nl >= 549)
            {
                buffer_save(ib, "file0");
                ossafe_ini_open("undertale.ini");
                ini_write_string("General", "Name", ln[0]);
                ini_write_real("General", "Love", real(ln[1]));
                ini_write_real("General", "Kills", real(ln[11]));
                ini_write_real("General", "Room", real(ln[547]));
                ini_write_real("General", "Time", real(ln[548]));
                ossafe_ini_close();
                ut_msg += "#Loading...";
                ut_msg_t = 9999;
                ut_wait = 3;
                ut_timeout = 30;
            }
            else
            {
                ut_msg = "That file0 doesn't look#like an Undertale save.";
                ut_wait = 0;
            }
            buffer_delete(ib);
            file_delete("ut_x_import");
        }
        else
        {
            ut_wait = 0;
        }
    }
    else if (ut_timeout <= 0)
    {
        ut_wait = 0;
        ut_msg = "The save helper#didn't respond.";
        ut_msg_t = 180;
    }
}
if (ut_wait == 3)
{
    ut_timeout -= 1;
    if (ut_timeout <= 0)
    {
        ut_wait = 0;
        caster_free(all);
        room_restart();
    }
}
if (ut_msg_t > 0)
{
    if (ut_msg_t < 9999)
    {
        ut_msg_t -= 1;
    }
    draw_set_color(c_black);
    draw_rectangle(8, 88, 312, 156, false);
    draw_set_color(c_white);
    draw_rectangle(8, 88, 312, 156, true);
    draw_set_halign(fa_center);
    draw_text(160, 94, string_hash_to_newline(ut_msg));
    draw_set_halign(fa_left);
}
