var gui_s = min(window_get_height() / 480, window_get_width() / 640);
display_set_gui_maximise(gui_s, gui_s, (window_get_width() - (640 * gui_s)) / 2, (window_get_height() - (480 * gui_s)) / 2);
bz = 0;
bx = 0;
bc = 0;
for (var i = 0; i < 5; i += 1)
{
    if (device_mouse_check_button(i, mb_left))
    {
        var tx = device_mouse_x_to_gui(i);
        var ty = device_mouse_y_to_gui(i);
        if (point_in_rectangle(tx, ty, zx, 347, zx + 81, 440))
        {
            bz = 1;
        }
        if (point_in_rectangle(tx, ty, xx, 308, xx + 81, 401))
        {
            bx = 1;
        }
        if (point_in_rectangle(tx, ty, cx, 269, cx + 81, 362))
        {
            bc = 1;
        }
    }
}
var nb = bz + bx + bc;
if (nb >= 2)
{
    combo = 1;
}
if (combo)
{
    // Two or more buttons at once: send no keys (no menus) until every finger is lifted.
    for (var k = 0; k < 3; k += 1)
    {
        if (btn_sent[k])
        {
            keyboard_key_release(btn_key[k]);
            btn_sent[k] = 0;
        }
        btn_time[k] = 0;
        btn_tap[k] = 0;
    }
    if (nb == 3)
    {
        enc_hold += 1;
    }
    else
    {
        enc_hold = 0;
    }
    if (enc_hold == round(room_speed * 5))
    {
        var enc_found = 0;
        with (all)
        {
            if (variable_instance_exists(id, "steps") && variable_instance_exists(id, "alldead"))
            {
                enc_found = 1;
            }
        }
        if (enc_found && room != room_battle && !instance_exists(obj_battlecontroller))
        {
            global.encounter = 99999;
            enc_msg = "Encounter!";
        }
        else
        {
            enc_msg = "No encounters here";
        }
        enc_flash = round(room_speed * 2);
    }
    if (nb == 0)
    {
        combo = 0;
        enc_hold = 0;
    }
}
else
{
    var down;
    down[0] = bz;
    down[1] = bx;
    down[2] = bc;
    for (var k = 0; k < 3; k += 1)
    {
        if (btn_tap[k])
        {
            keyboard_key_release(btn_key[k]);
            btn_tap[k] = 0;
        }
        if (down[k])
        {
            btn_time[k] += 1;
            if (btn_time[k] >= btn_delay && !btn_sent[k])
            {
                keyboard_key_press(btn_key[k]);
                btn_sent[k] = 1;
            }
        }
        else
        {
            if (btn_sent[k])
            {
                keyboard_key_release(btn_key[k]);
                btn_sent[k] = 0;
            }
            else if (btn_time[k] > 0)
            {
                // quick tap that ended before the delay: press now, release next frame
                keyboard_key_press(btn_key[k]);
                btn_tap[k] = 1;
            }
            btn_time[k] = 0;
        }
    }
}
if (enc_flash > 0)
{
    enc_flash -= 1;
}
