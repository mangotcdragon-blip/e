var gui_s = min(window_get_height() / 480, window_get_width() / 640);
display_set_gui_maximise(gui_s, gui_s, (window_get_width() - (640 * gui_s)) / 2, (window_get_height() - (480 * gui_s)) / 2);
draw_sprite_ext(spr_control_zkey, (bz || keyboard_check(ord("Z"))), zx, 350, 3, 3, 0, c_white, global.controller_opacity);
draw_sprite_ext(spr_control_xkey, (bx || keyboard_check(ord("X"))), xx, 311, 3, 3, 0, c_white, global.controller_opacity);
draw_sprite_ext(spr_control_ckey, (bc || keyboard_check(ord("C"))), cx, 272, 3, 3, 0, c_white, global.controller_opacity);
var h = padcell / 2;
var a = padcell * 0.22;
var px = padx;
var py = pady;
draw_set_alpha(global.controller_opacity);
if (!dodging)
{
    draw_set_color(joybase_colour);
    draw_rectangle(px - h, py - (padcell * 1.5), px + h, py + (padcell * 1.5), false);
    draw_rectangle(px - (padcell * 1.5), py - h, px + (padcell * 1.5), py + h, false);
    draw_set_color(joystick_colour);
    if (global.joy_up)
    {
        draw_rectangle(px - h, py - (padcell * 1.5), px + h, py - h, false);
    }
    if (global.joy_down)
    {
        draw_rectangle(px - h, py + h, px + h, py + (padcell * 1.5), false);
    }
    if (global.joy_left)
    {
        draw_rectangle(px - (padcell * 1.5), py - h, px - h, py + h, false);
    }
    if (global.joy_right)
    {
        draw_rectangle(px + h, py - h, px + (padcell * 1.5), py + h, false);
    }
    draw_set_color(c_white);
    draw_rectangle(px - h, py - (padcell * 1.5), px + h, py + (padcell * 1.5), true);
    draw_rectangle(px - (padcell * 1.5), py - h, px + (padcell * 1.5), py + h, true);
    draw_triangle(px, py - padcell - a, px - a, (py - padcell) + a, px + a, (py - padcell) + a, 0);
    draw_triangle(px, py + padcell + a, px - a, (py + padcell) - a, px + a, (py + padcell) - a, 0);
    draw_triangle(px - padcell - a, py, (px - padcell) + a, py - a, (px - padcell) + a, py + a, 0);
    draw_triangle(px + padcell + a, py, (px + padcell) - a, py - a, (px + padcell) - a, py + a, 0);
}
else if (!dragmode && !green && !purple && !omega && touchdev != -1)
{
    draw_sprite_ext(spr_joybase, 0, touchstartx, touchstarty, 2, 2, 0, joybase_colour, global.controller_opacity);
    draw_sprite_ext(spr_joystick, 0, touchlastx, touchlasty, 2, 2, 0, joystick_colour, global.controller_opacity);
}
draw_set_alpha(1);
draw_set_color(c_white);
if (dzdraw)
{
    draw_set_alpha(0.5);
    draw_set_color(c_red);
    draw_circle(padx, pady, max(12, global.controller_deadzoner * 24), 0);
    draw_set_alpha(1);
    draw_set_color(c_white);
}
var hn = instance_number(obj_heart) + instance_number(obj_fakeheart) + instance_number(obj_dateheart) + instance_number(obj_heart_sansbattle);
var hinfo = "none";
if (instance_exists(obj_heart))
{
    hinfo = "H" + string(round(obj_heart.x)) + "," + string(round(obj_heart.y)) + " mv" + string(obj_heart.movement);
}
if (instance_exists(obj_fakeheart))
{
    hinfo = "F" + string(round(obj_fakeheart.x)) + "," + string(round(obj_fakeheart.y)) + " mv" + string(obj_fakeheart.movement);
}
var tcount = 0;
var tinfo = "";
for (var k = 0; k < 5; k += 1)
{
    if (device_mouse_check_button(k, mb_left))
    {
        tcount += 1;
        tinfo = tinfo + " t" + string(k) + ":" + string(round(device_mouse_x_to_gui(k))) + "," + string(round(device_mouse_y_to_gui(k)));
    }
}
if (tcount >= 3)
{
    draw_set_alpha(1);
    draw_set_color(c_yellow);
    draw_set_halign(fa_left);
    draw_text(4, 2, "mn" + string(global.mnfight) + " dg" + string(dodging) + " dr" + string(dragmode) + " h" + string(hn) + " " + hinfo + " f" + string(round(dbg_fx)) + "," + string(round(dbg_fy)) + " n" + string(tcount) + tinfo + " md" + string(mode) + " gr" + string(green) + " pu" + string(purple) + " om" + string(omega) + " ws" + string(global.window_scale) + " " + dbg_view);
}
draw_set_color(c_white);
if (enc_flash > 0)
{
    draw_set_halign(fa_center);
    draw_set_color(c_yellow);
    draw_text(320, 40, enc_msg);
    draw_set_halign(fa_left);
    draw_set_color(c_white);
}
