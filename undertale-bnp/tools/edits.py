import sys, os
src, out = sys.argv[1], sys.argv[2]
import re
# The mod quits on Android in ~34 places (`if (os_type == os_android) { game_end(); }`); strip them all.
KILL = re.compile(r'^([ \t]*)if \(os_type == os_android\)\n\1\{\n\1    game_end\(\);\n\1\}\n', re.M)
written = set()
def rd(n): return KILL.sub("", open(os.path.join(src, n + ".gml")).read())
def wr(n, s): written.add(n); open(os.path.join(out, n + ".gml"), "w").write(s)
def rep(s, old, new, count=1):
    c = s.count(old)
    assert c == count, (old[:80], c)
    return s.replace(old, new)

# obj_time Create: stop quitting on Android, set up the touch globals, spawn the controller
n = "gml_Object_obj_time_Create_0"; s = rd(n)
s += """global.controller_opacity = 0.5;
global.controller_deadzoner = 0.5;
global.joy_right = 0;
global.joy_left = 0;
global.joy_up = 0;
global.joy_down = 0;
global.aspectratio = window_get_height() / window_get_width();
global.window_xofs = 0;
global.window_yofs = 0;
if (os_type == os_android || os_type == os_ios)
{
    instance_create(0, 0, obj_mobilecontroller);
}
"""
wr(n, s)

# obj_time Begin Step: keep the aspect ratio the controller reads up to date
n = "gml_Object_obj_time_Step_1"; s = rd(n)
s = "global.aspectratio = window_get_height() / window_get_width();\n" + s
wr(n, s)

# obj_time Pre-Draw: on phones use fractional scaling (integer scaling leaves the game tiny on 720p screens)
n = "gml_Object_obj_time_Draw_76"; s = rd(n)
s = rep(s, "else if (global.osflavor >= 1)\n{", """else if (os_type == os_android || os_type == os_ios)
{
    if ((wh / ww) <= 0.5625 && global.screen_border_id != 0)
    {
        global.window_scale = min(scale_w, scale_h) / 1.125;
    }
    else
    {
        global.window_scale = min(scale_w, scale_h);
    }
}
else if (global.osflavor >= 1)
{""")
wr(n, s)

# obj_intromenu Create: save bridge state
n = "gml_Object_obj_intromenu_Create_0"; s = rd(n)
s = "ut_wait = 0;\nut_msg = \"\";\nut_msg_t = 0;\nut_timeout = 0;\n" + s
wr(n, s)

# scr_namingscreen: Export/Import entries in both title menus
n = "gml_Script_scr_namingscreen"; s = rd(n)
I8, I12, I16 = " " * 8, " " * 12, " " * 16
# -- menu with an existing save: Continue / Reset / Settings / Export save / Import save
s = rep(s, I8 + "scr_drawtext_outline((room_width - string_width(settings_text)) / 2, 125, settings_text);\n" + I8 + "draw_set_color(c_white);\n",
        I8 + "scr_drawtext_outline((room_width - string_width(settings_text)) / 2, 125, settings_text);\n" + I8 + "draw_set_color(c_white);\n"
        + I8 + "if (selected3 == 3)\n" + I8 + "{\n" + I12 + "draw_set_color(c_yellow);\n" + I8 + "}\n"
        + I8 + "scr_drawtext_outline((room_width - string_width(\"Export save\")) / 2, 145, \"Export save\");\n" + I8 + "draw_set_color(c_white);\n"
        + I8 + "if (selected3 == 4)\n" + I8 + "{\n" + I12 + "draw_set_color(c_yellow);\n" + I8 + "}\n"
        + I8 + "scr_drawtext_outline((room_width - string_width(\"Import save\")) / 2, 165, \"Import save\");\n" + I8 + "draw_set_color(c_white);\n")
s = rep(s, I12 + "if (selected3 == 0 || selected3 == 1)\n" + I12 + "{\n" + I16 + "selected3 = 2;\n" + I12 + "}\n",
        I12 + "if (selected3 == 0 || selected3 == 1)\n" + I12 + "{\n" + I16 + "selected3 = 2;\n" + I12 + "}\n"
        + I12 + "else if (selected3 == 2)\n" + I12 + "{\n" + I16 + "selected3 = 3;\n" + I12 + "}\n"
        + I12 + "else if (selected3 == 3)\n" + I12 + "{\n" + I16 + "selected3 = 4;\n" + I12 + "}\n")
s = rep(s, I12 + "if (selected3 == 2)\n" + I12 + "{\n" + I16 + "selected3 = 0;\n" + I12 + "}\n",
        I12 + "if (selected3 == 2)\n" + I12 + "{\n" + I16 + "selected3 = 0;\n" + I12 + "}\n"
        + I12 + "else if (selected3 == 3)\n" + I12 + "{\n" + I16 + "selected3 = 2;\n" + I12 + "}\n"
        + I12 + "else if (selected3 == 4)\n" + I12 + "{\n" + I16 + "selected3 = 3;\n" + I12 + "}\n")
s = rep(s, I8 + "if (action == 2)\n" + I8 + "{\n" + I12 + "caster_free(all);\n" + I12 + "room_goto(room_settings);\n" + I8 + "}\n",
        I8 + "if (action == 2)\n" + I8 + "{\n" + I12 + "caster_free(all);\n" + I12 + "room_goto(room_settings);\n" + I8 + "}\n"
        + I8 + "if (action == 3)\n" + I8 + "{\n" + I12 + "scr_ut_export();\n" + I8 + "}\n"
        + I8 + "if (action == 4)\n" + I8 + "{\n" + I12 + "scr_ut_import();\n" + I8 + "}\n"
        + I8 + "scr_ut_savebridge();\n")
# -- menu without a save: Begin Game / Settings / Import save
s = rep(s, I8 + "draw_text(xx, yy2, string_hash_to_newline(scr_gettext(\"settings_name\")));\n",
        I8 + "draw_text(xx, yy2, string_hash_to_newline(scr_gettext(\"settings_name\")));\n"
        + I8 + "draw_set_color(c_white);\n"
        + I8 + "if (selected3 == 2)\n" + I8 + "{\n" + I12 + "draw_set_color(c_yellow);\n" + I8 + "}\n"
        + I8 + "draw_text(xx, yy2 + 20, string_hash_to_newline(\"Import save\"));\n")
s = rep(s, I8 + "if (keyboard_check_pressed(global.keybind[3]))\n" + I8 + "{\n" + I12 + "if (selected3 == 0)\n" + I12 + "{\n" + I16 + "selected3 = 1;\n" + I12 + "}\n" + I8 + "}\n"
        + I8 + "if (keyboard_check_pressed(global.keybind[1]))\n" + I8 + "{\n" + I12 + "if (selected3 == 1)\n" + I12 + "{\n" + I16 + "selected3 = 0;\n" + I12 + "}\n" + I8 + "}\n",
        I8 + "if (keyboard_check_pressed(global.keybind[3]))\n" + I8 + "{\n" + I12 + "if (selected3 == 0)\n" + I12 + "{\n" + I16 + "selected3 = 1;\n" + I12 + "}\n"
        + I12 + "else if (selected3 == 1)\n" + I12 + "{\n" + I16 + "selected3 = 2;\n" + I12 + "}\n" + I8 + "}\n"
        + I8 + "if (keyboard_check_pressed(global.keybind[1]))\n" + I8 + "{\n" + I12 + "if (selected3 == 1)\n" + I12 + "{\n" + I16 + "selected3 = 0;\n" + I12 + "}\n"
        + I12 + "else if (selected3 == 2)\n" + I12 + "{\n" + I16 + "selected3 = 1;\n" + I12 + "}\n" + I8 + "}\n")
s = rep(s, I8 + "if (action == 1)\n" + I8 + "{\n" + I12 + "caster_free(all);\n" + I12 + "room_goto(room_settings);\n" + I8 + "}\n",
        I8 + "if (action == 1)\n" + I8 + "{\n" + I12 + "caster_free(all);\n" + I12 + "room_goto(room_settings);\n" + I8 + "}\n"
        + I8 + "if (action == 2)\n" + I8 + "{\n" + I12 + "scr_ut_import();\n" + I8 + "}\n"
        + I8 + "scr_ut_savebridge();\n")
# -- don't start a new action while the helper is busy
s = rep(s, I8 + "if (control_check_pressed(0))\n" + I8 + "{\n" + I12 + "action = selected3;\n",
        I8 + "if (control_check_pressed(0) && ut_wait == 0)\n" + I8 + "{\n" + I12 + "action = selected3;\n", count=2)
wr(n, s)

# FileManager wrappers fall through on Android and return undefined; return 0 instead
for n in ["file_exists_fmns", "file_copy_fmns", "file_delete_fmns", "file_rename_fmns",
          "directory_exists_fmns", "directory_copy_fmns", "directory_create_fmns", "directory_destroy_fmns", "directory_rename_fmns"]:
    n = "gml_Script_" + n
    wr(n, rd(n).rstrip("\n") + "\nreturn 0;\n")
print("ok")

# obj_mirrorbg Draw: skip the distortion shaders if the device couldn't compile them
n = "gml_Object_obj_mirrorbg_Draw_0"; s = rd(n)
I4 = " " * 4
s = rep(s, I4 + "surface_set_target(surface);\n" + I4 + "draw_clear_alpha(bg_color, 1);\n" + I4 + "shader_set(sh_distort);\n",
        I4 + "var shok = shader_is_compiled(sh_distort) && shader_is_compiled(sh_saturation);\n"
        + I4 + "surface_set_target(surface);\n" + I4 + "draw_clear_alpha(bg_color, 1);\n" + I4 + "if (shok)\n" + I4 + "{\n" + I4 + "    shader_set(sh_distort);\n")
s = rep(s, I4 + "shader_set_uniform_f(axis_shift, rem_timer % w, 0);\n", I4 + "shader_set_uniform_f(axis_shift, rem_timer % w, 0);\n" + I4 + "}\n")
s = rep(s, I4 + "draw_sprite_ext(sprite, 0, -w, -h, 1, 1, 0, blend ? image_blend : c_white, image_alpha);\n" + I4 + "shader_reset();\n",
        I4 + "draw_sprite_ext(sprite, 0, -w, -h, 1, 1, 0, blend ? image_blend : c_white, image_alpha);\n" + I4 + "if (shok)\n" + I4 + "{\n" + I4 + "    shader_reset();\n" + I4 + "}\n")
s = rep(s, I4 + "shader_set(sh_saturation);\n" + I4 + "shader_set_uniform_f(saturation, sat, 0);\n",
        I4 + "if (shok)\n" + I4 + "{\n" + I4 + "    shader_set(sh_saturation);\n" + I4 + "    shader_set_uniform_f(saturation, sat, 0);\n" + I4 + "}\n")
s = rep(s, I4 + "draw_surface_ext(surface, xx, yy + h, 1, -1, 0, c_white, 0.5);\n" + I4 + "shader_reset();\n",
        I4 + "draw_surface_ext(surface, xx, yy + h, 1, -1, 0, c_white, 0.5);\n" + I4 + "if (shok)\n" + I4 + "{\n" + I4 + "    shader_reset();\n" + I4 + "}\n")
wr(n, s)
# LV 20 gives 120 max HP instead of 99 (set on level-up and re-applied at the start of every battle)
n = "gml_Script_scr_levelup"; s = rd(n)
s = rep(s, "    if (global.lv == 20)\n    {\n        global.maxhp = 99;\n", "    if (global.lv == 20)\n    {\n        global.maxhp = 120;\n")
wr(n, s)
n = "gml_Object_obj_battlecontroller_Create_0"; s = rd(n)
s = rep(s, "    global.df = 30;\n    global.maxhp = 99;\n", "    global.df = 30;\n    global.maxhp = 120;\n")
wr(n, s)

for f in sorted(os.listdir(src)):
    n = f[:-4]
    if f.endswith(".gml") and n not in written:
        raw = open(os.path.join(src, f)).read()
        if KILL.search(raw):
            wr(n, KILL.sub("", raw))
left = [n for n in written if KILL.search(open(os.path.join(out, n + ".gml")).read())]
assert not left, left
print("ok", len(written), "entries")
