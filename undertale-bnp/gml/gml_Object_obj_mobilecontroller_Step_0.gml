var gui_s = min(window_get_height() / 480, window_get_width() / 640);
display_set_gui_maximise(gui_s, gui_s, (window_get_width() - (640 * gui_s)) / 2, (window_get_height() - (480 * gui_s)) / 2);
var vk_w = __view_get(2, 0);
var vk_h = __view_get(3, 0);
if (vk_w <= 0 || vk_h <= 0)
{
    vk_w = 640;
    vk_h = 480;
}
var sk_x = vk_w / surface_get_width(application_surface);
var sk_y = vk_h / surface_get_height(application_surface);
var vk_x = __view_get(0, 0);
var vk_y = __view_get(1, 0);
dbg_view = "v" + string(vk_x) + "," + string(vk_y) + "," + string(vk_w) + "x" + string(vk_h) + " s" + string(surface_get_width(application_surface)) + "x" + string(surface_get_height(application_surface)) + " xo" + string(global.window_xofs);
if (global.aspectratio <= 0.5625)
{
    zx = 476;
    xx = 557;
    cx = 638;
    jx = 0;
}
else
{
    zx = 387;
    xx = 468;
    cx = 549;
    jx = 100;
}
var gscale = min(window_get_height() / 480, window_get_width() / 640);
padx = (-((window_get_width() / gscale) - 640) / 2) + 110;
pady = jy;
aspectratio_previous = global.aspectratio;
var joy_rightp = global.joy_right;
var joy_leftp = global.joy_left;
var joy_upp = global.joy_up;
var joy_downp = global.joy_down;
global.joy_right = 0;
global.joy_left = 0;
global.joy_up = 0;
global.joy_down = 0;
dodging = 0;
dragmode = 0;
mode = 0;
dbg_fx = -1;
dbg_fy = -1;
with (obj_heart)
{
    if (global.mnfight == 2 && movement != 0)
    {
        other.dodging = 1;
        other.mode = movement;
    }
}
with (obj_heart_sansbattle)
{
    if (global.mnfight == 2 && movement != 0)
    {
        other.dodging = 1;
        other.mode = movement;
    }
}
with (obj_fakeheart)
{
    if (movement == 1)
    {
        other.dodging = 1;
        other.mode = 1;
    }
}
with (obj_dateheart)
{
    if (movement == 1)
    {
        other.dodging = 1;
        other.mode = 1;
    }
}
if (mode == 1 || mode == 2 || mode == 11 || mode == 12 || mode == 13)
{
    dragmode = 1;
}
green = 0;
if (instance_exists(obj_spearblocker))
{
    green = 1;
    dodging = 1;
    dragmode = 0;
}
purple = 0;
if (instance_exists(obj_purpleheart))
{
    purple = 1;
    dodging = 1;
    dragmode = 0;
}
omega = 0;
if (instance_exists(obj_vsflowey_heart))
{
    if (obj_vsflowey_heart.move == 1)
    {
        omega = 1;
        dodging = 1;
        dragmode = 0;
    }
}
if (dodging)
{
    if (touchdev != -1 && !device_mouse_check_button(touchdev, mb_left))
    {
        touchdev = -1;
    }
    if (touchdev == -1)
    {
        for (var i = 0; i < 5; i += 1)
        {
            if (touchdev == -1 && device_mouse_check_button(i, mb_left))
            {
                var tx = device_mouse_x_to_gui(i);
                var ty = device_mouse_y_to_gui(i);
                var ok = 1;
                if (tx == 0 && ty == 0)
                {
                    ok = 0;
                }
                if (tx < -400 || tx > 1040 || ty < -400 || ty > 880)
                {
                    ok = 0;
                }
                if (point_in_rectangle(tx, ty, zx, 347, zx + 81, 440) || point_in_rectangle(tx, ty, xx, 308, xx + 81, 401) || point_in_rectangle(tx, ty, cx, 269, cx + 81, 362))
                {
                    ok = 0;
                }
                if (ok)
                {
                    touchdev = i;
                    touchlastx = tx;
                    touchlasty = ty;
                    touchstartx = tx;
                    touchstarty = ty;
                }
            }
        }
    }
    if (touchdev != -1)
    {
        var tx = device_mouse_x_to_gui(touchdev);
        var ty = device_mouse_y_to_gui(touchdev);
        if (tx == 0 && ty == 0)
        {
            tx = touchlastx;
            ty = touchlasty;
        }
        touchlastx = tx;
        touchlasty = ty;
        if (green == 1)
        {
            var ww3 = window_get_width();
            var wh3 = window_get_height();
            var gs3 = min(wh3 / 480, ww3 / 640);
            var ws3 = global.window_scale;
            if (ws3 <= 0)
            {
                ws3 = gs3;
            }
            var rx = ((((ww3 - (640 * gs3)) / 2) + (tx * gs3)) - global.window_xofs) / ws3;
            var ry = ((((wh3 - (480 * gs3)) / 2) + (ty * gs3)) - global.window_yofs) / ws3;
            rx = vk_x + (rx * sk_x);
            ry = vk_y + (ry * sk_y);
            var cx3 = obj_spearblocker.x;
            var cy3 = obj_spearblocker.y;
            var ddx = rx - cx3;
            var ddy = ry - cy3;
            dbg_fx = rx;
            dbg_fy = ry;
            if ((abs(ddx) + abs(ddy)) > 10)
            {
                var gdir = 270;
                if (abs(ddx) > abs(ddy))
                {
                    if (ddx > 0)
                    {
                        gdir = 180;
                    }
                    else
                    {
                        gdir = 0;
                    }
                }
                else if (ddy > 0)
                {
                    gdir = 90;
                }
                with (obj_spearblocker)
                {
                    idealdir = gdir;
                }
            }
        }
        else if (omega == 1)
        {
            var ww5 = window_get_width();
            var wh5 = window_get_height();
            var gs5 = min(wh5 / 480, ww5 / 640);
            var ws5 = global.window_scale;
            if (ws5 <= 0)
            {
                ws5 = gs5;
            }
            var ox5 = ((((ww5 - (640 * gs5)) / 2) + (tx * gs5)) - global.window_xofs) / ws5;
            var oy5 = ((((wh5 - (480 * gs5)) / 2) + (ty * gs5)) - global.window_yofs) / ws5;
            ox5 = vk_x + (ox5 * sk_x);
            oy5 = (vk_y + (oy5 * sk_y)) - follow_dy;
            dbg_fx = ox5;
            dbg_fy = oy5;
            with (obj_vsflowey_heart)
            {
                if (move == 1)
                {
                    var dark = 0;
                    if (instance_exists(obj_flowey_master))
                    {
                        dark = obj_flowey_master.darkmode;
                    }
                    var lo_x = 0;
                    var hi_x = room_width - sprite_width;
                    var lo_y = 0;
                    var hi_y = room_height - sprite_height;
                    if (dark == 0)
                    {
                        lo_x = 108;
                        hi_x = min(hi_x, 512);
                        lo_y = 268;
                    }
                    var gx5 = (ox5 - (sprite_width / 2)) + sprite_xoffset;
                    var gy5 = (oy5 - (sprite_height / 2)) + sprite_yoffset;
                    gx5 = max(lo_x, min(hi_x, gx5));
                    gy5 = max(lo_y, min(hi_y, gy5));
                    x = round(x + ((gx5 - x) * other.follow_k));
                    y = round(y + ((gy5 - y) * other.follow_k));
                }
            }
        }
        else if (purple == 1)
        {
            var ww4 = window_get_width();
            var wh4 = window_get_height();
            var gs4 = min(wh4 / 480, ww4 / 640);
            var ws4 = global.window_scale;
            if (ws4 <= 0)
            {
                ws4 = gs4;
            }
            var px4 = ((((ww4 - (640 * gs4)) / 2) + (tx * gs4)) - global.window_xofs) / ws4;
            var py4 = ((((wh4 - (480 * gs4)) / 2) + (ty * gs4)) - global.window_yofs) / ws4;
            px4 = vk_x + (px4 * sk_x);
            py4 = (vk_y + (py4 * sk_y)) - follow_dy;
            dbg_fx = px4;
            dbg_fy = py4;
            with (obj_purpleheart)
            {
                if (type == 0 && visible)
                {
                    var tx4 = max(xmid - xlen, min(xmid + xlen, px4));
                    x = round(x + ((tx4 - x) * other.follow_k));
                    if (moving == 0)
                    {
                        var tline = round((py4 - yzero - yoff) / yspace) + 1;
                        tline = max(1, min(yamt, tline));
                        if (tline < yno && yno > 1)
                        {
                            moving = 1;
                        }
                        else if (tline > yno && yno < yamt)
                        {
                            moving = 2;
                        }
                    }
                }
            }
        }
        else if (dragmode)
        {
            var ww = window_get_width();
            var wh = window_get_height();
            var gs = min(wh / 480, ww / 640);
            var gx0 = (ww - (640 * gs)) / 2;
            var gy0 = (wh - (480 * gs)) / 2;
            var ws = global.window_scale;
            if (ws <= 0)
            {
                ws = gs;
            }
            fx = ((gx0 + (tx * gs)) - global.window_xofs) / ws;
            fy = ((gy0 + (ty * gs)) - global.window_yofs) / ws;
            fx = vk_x + (fx * sk_x);
            fy = vk_y + (fy * sk_y);
            dbg_fx = fx;
            dbg_fy = fy;
            tgx = fx - 8;
            tgy = fy - 8 - follow_dy;
            with (obj_heart)
            {
                if (global.mnfight == 2 && ignore_border == 0 && (movement == 1 || movement == 2 || movement == 11 || movement == 12 || movement == 13))
                {
                    other.mode = movement;
                    if (movement == 1)
                    {
                        x += ((other.tgx - x) * other.follow_k);
                        y += ((other.tgy - y) * other.follow_k);
                    }
                    if (movement == 2 || movement == 12)
                    {
                        x += ((other.tgx - x) * other.follow_k);
                    }
                    if (movement == 11 || movement == 13)
                    {
                        y += ((other.tgy - y) * other.follow_k);
                    }
                    if (movement == 2 && other.tgy < (y - 4))
                    {
                        global.joy_up = 1;
                    }
                    if (movement == 12 && other.tgy > (y + 4))
                    {
                        global.joy_down = 1;
                    }
                    if (movement == 11 && other.tgx < (x - 4))
                    {
                        global.joy_left = 1;
                    }
                    if (movement == 13 && other.tgx > (x + 4))
                    {
                        global.joy_right = 1;
                    }
                    if (instance_exists(obj_lborder) && instance_exists(obj_rborder) && instance_exists(obj_uborder) && instance_exists(obj_dborder))
                    {
                        x = max(x, obj_lborder.x + 5);
                        x = min(x, obj_rborder.x - 16);
                        y = max(y, obj_uborder.y + 5);
                        y = min(y, obj_dborder.y - 16);
                    }
                    else
                    {
                        x = max(x, global.idealborder[0] + 4);
                        x = min(x, global.idealborder[1] - 16);
                        y = max(y, global.idealborder[2] + 4);
                        y = min(y, global.idealborder[3] - 16);
                    }
                    x = round(x);
                    y = round(y);
                }
            }
            with (obj_heart_sansbattle)
            {
                if (global.mnfight == 2 && ignore_border == 0 && (movement == 1 || movement == 2 || movement == 11 || movement == 12 || movement == 13))
                {
                    other.mode = movement;
                    if (movement == 1)
                    {
                        x += ((other.tgx - x) * other.follow_k);
                        y += ((other.tgy - y) * other.follow_k);
                    }
                    if (movement == 2 || movement == 12)
                    {
                        x += ((other.tgx - x) * other.follow_k);
                    }
                    if (movement == 11 || movement == 13)
                    {
                        y += ((other.tgy - y) * other.follow_k);
                    }
                    if (movement == 2 && other.tgy < (y - 4))
                    {
                        global.joy_up = 1;
                    }
                    if (movement == 12 && other.tgy > (y + 4))
                    {
                        global.joy_down = 1;
                    }
                    if (movement == 11 && other.tgx < (x - 4))
                    {
                        global.joy_left = 1;
                    }
                    if (movement == 13 && other.tgx > (x + 4))
                    {
                        global.joy_right = 1;
                    }
                    if (instance_exists(obj_lborder) && instance_exists(obj_rborder) && instance_exists(obj_uborder) && instance_exists(obj_dborder))
                    {
                        x = max(x, obj_lborder.x + 5);
                        x = min(x, obj_rborder.x - 16);
                        y = max(y, obj_uborder.y + 5);
                        y = min(y, obj_dborder.y - 16);
                    }
                    else
                    {
                        x = max(x, global.idealborder[0] + 4);
                        x = min(x, global.idealborder[1] - 16);
                        y = max(y, global.idealborder[2] + 4);
                        y = min(y, global.idealborder[3] - 16);
                    }
                    x = round(x);
                    y = round(y);
                }
            }
            with (obj_fakeheart)
            {
                if (movement == 1)
                {
                    other.mode = movement;
                    if (movement == 1)
                    {
                        x += ((other.tgx - x) * other.follow_k);
                        y += ((other.tgy - y) * other.follow_k);
                    }
                    if (movement == 2 || movement == 12)
                    {
                        x += ((other.tgx - x) * other.follow_k);
                    }
                    if (movement == 11 || movement == 13)
                    {
                        y += ((other.tgy - y) * other.follow_k);
                    }
                    if (movement == 2 && other.tgy < (y - 4))
                    {
                        global.joy_up = 1;
                    }
                    if (movement == 12 && other.tgy > (y + 4))
                    {
                        global.joy_down = 1;
                    }
                    if (movement == 11 && other.tgx < (x - 4))
                    {
                        global.joy_left = 1;
                    }
                    if (movement == 13 && other.tgx > (x + 4))
                    {
                        global.joy_right = 1;
                    }
                    if (instance_exists(obj_lborder) && instance_exists(obj_rborder) && instance_exists(obj_uborder) && instance_exists(obj_dborder))
                    {
                        x = max(x, obj_lborder.x + 5);
                        x = min(x, obj_rborder.x - 16);
                        y = max(y, obj_uborder.y + 5);
                        y = min(y, obj_dborder.y - 16);
                    }
                    else
                    {
                        x = max(x, global.idealborder[0] + 4);
                        x = min(x, global.idealborder[1] - 16);
                        y = max(y, global.idealborder[2] + 4);
                        y = min(y, global.idealborder[3] - 16);
                    }
                    x = round(x);
                    y = round(y);
                }
            }
            with (obj_dateheart)
            {
                if (movement == 1)
                {
                    other.mode = movement;
                    if (movement == 1)
                    {
                        x += ((other.tgx - x) * other.follow_k);
                        y += ((other.tgy - y) * other.follow_k);
                    }
                    if (movement == 2 || movement == 12)
                    {
                        x += ((other.tgx - x) * other.follow_k);
                    }
                    if (movement == 11 || movement == 13)
                    {
                        y += ((other.tgy - y) * other.follow_k);
                    }
                    if (movement == 2 && other.tgy < (y - 4))
                    {
                        global.joy_up = 1;
                    }
                    if (movement == 12 && other.tgy > (y + 4))
                    {
                        global.joy_down = 1;
                    }
                    if (movement == 11 && other.tgx < (x - 4))
                    {
                        global.joy_left = 1;
                    }
                    if (movement == 13 && other.tgx > (x + 4))
                    {
                        global.joy_right = 1;
                    }
                    if (instance_exists(obj_lborder) && instance_exists(obj_rborder) && instance_exists(obj_uborder) && instance_exists(obj_dborder))
                    {
                        x = max(x, obj_lborder.x + 5);
                        x = min(x, obj_rborder.x - 16);
                        y = max(y, obj_uborder.y + 5);
                        y = min(y, obj_dborder.y - 16);
                    }
                    else
                    {
                        x = max(x, global.idealborder[0] + 4);
                        x = min(x, global.idealborder[1] - 16);
                        y = max(y, global.idealborder[2] + 4);
                        y = min(y, global.idealborder[3] - 16);
                    }
                    x = round(x);
                    y = round(y);
                }
            }
        }
        else
        {
            var dz = max(12, global.controller_deadzoner * 24);
            if ((tx - touchstartx) > dz)
            {
                global.joy_right = 1;
            }
            if ((touchstartx - tx) > dz)
            {
                global.joy_left = 1;
            }
            if ((ty - touchstarty) > dz)
            {
                global.joy_down = 1;
            }
            if ((touchstarty - ty) > dz)
            {
                global.joy_up = 1;
            }
        }
    }
}
else
{
    touchdev = -1;
    var reach = (padcell * 1.5) + 24;
    for (var i = 0; i < 5; i += 1)
    {
        if (device_mouse_check_button(i, mb_left))
        {
            var tx = device_mouse_x_to_gui(i) - padx;
            var ty = device_mouse_y_to_gui(i) - pady;
            if (abs(tx) <= reach && abs(ty) <= reach)
            {
                if (tx < (-padcell / 2))
                {
                    global.joy_left = 1;
                }
                if (tx > (padcell / 2))
                {
                    global.joy_right = 1;
                }
                if (ty < (-padcell / 2))
                {
                    global.joy_up = 1;
                }
                if (ty > (padcell / 2))
                {
                    global.joy_down = 1;
                }
            }
        }
    }
}
if (global.joy_right != joy_rightp && global.joy_right)
{
    keyboard_key_press(vk_right);
}
if (global.joy_left != joy_leftp && global.joy_left)
{
    keyboard_key_press(vk_left);
}
if (global.joy_down != joy_downp && global.joy_down)
{
    keyboard_key_press(vk_down);
}
if (global.joy_up != joy_upp && global.joy_up)
{
    keyboard_key_press(vk_up);
}
if (global.joy_right != joy_rightp && global.joy_right == 0)
{
    keyboard_key_release(vk_right);
}
if (global.joy_left != joy_leftp && global.joy_left == 0)
{
    keyboard_key_release(vk_left);
}
if (global.joy_down != joy_downp && global.joy_down == 0)
{
    keyboard_key_release(vk_down);
}
if (global.joy_up != joy_upp && global.joy_up == 0)
{
    keyboard_key_release(vk_up);
}
