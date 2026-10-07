global.touch_dx = 0;
global.touch_dy = 0;
joy_rightp = 0;
joy_leftp = 0;
joy_upp = 0;
joy_downp = 0;
dzdraw = 0;
joybase_colour = 3158064;
joystick_colour = 6908265;
if ((window_get_height() / window_get_width()) <= 0.5625)
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
jy = 346;
padx = (-((window_get_width() / min(window_get_height() / 480, window_get_width() / 640)) - 640) / 2) + 110;
pady = jy;
padcell = 56;
dodging = 0;
dragmode = 0;
green = 0;
purple = 0;
omega = 0;
touchdev = -1;
touchlastx = 0;
touchlasty = 0;
touchstartx = 0;
touchstarty = 0;
fx = 0;
fy = 0;
tgx = 0;
tgy = 0;
mode = 0;
follow_k = 0.75;
follow_dy = 40;
dbg_fx = -1;
dbg_fy = -1;
device_mouse_dbclick_enable(false);
aspectratio_previous = window_get_height() / window_get_width();
virtual_key_z = virtual_key_add(zx, 347, 81, 93, 90);
virtual_key_x = virtual_key_add(xx, 308, 81, 93, 88);
virtual_key_c = virtual_key_add(cx, 269, 81, 93, 67);
