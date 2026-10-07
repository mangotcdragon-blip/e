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
    if ((wh / ww) <= 0.5625 && global.screen_border_id != 0 && global.screen_border_active)
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
# Max HP: LV 19 gives 125 (normally 92) and LV 20 gives 200 (normally 99). Set on level-up and
# re-applied at the start of every battle so existing saves pick it up.
n = "gml_Script_scr_levelup"; s = rd(n)
s = rep(s, "    if (global.lv == 20)\n    {\n        global.maxhp = 99;\n",
        "    if (global.lv == 19)\n    {\n        global.maxhp = 125;\n    }\n    if (global.lv == 20)\n    {\n        global.maxhp = 200;\n")
wr(n, s)
n = "gml_Object_obj_battlecontroller_Create_0"; s = rd(n)
# (the overrides must come before the "hp > maxhp + 15" clamp, or full HP gets cut every battle)
s = rep(s, "global.maxhp = 16 + (global.lv * 4);\nif (global.hp > (global.maxhp + 15))\n",
        "global.maxhp = 16 + (global.lv * 4);\nif (global.lv == 19)\n{\n    global.maxhp = 125;\n}\nif (global.lv == 20)\n{\n    global.maxhp = 200;\n}\nif (global.hp > (global.maxhp + 15))\n")
s = rep(s, "if (global.lv == 20)\n{\n    global.df = 30;\n    global.maxhp = 99;\n}\n", "if (global.lv == 20)\n{\n    global.df = 30;\n}\n")
wr(n, s)
# HP bars are drawn 1.2 px per max HP; above 120 max HP squeeze the bar to 144 px so the numbers stay on screen.
for n in ["gml_Script_scr_binfowrite", "gml_Object_obj_floweydraw_Draw_0", "gml_Object_obj_soulvision_Draw_64"]:
    s = rd(n)
    assert "(global.maxhp * 1.2)" in s
    for v in ["maxhp", "hp", "km"]:
        s = s.replace("(global.%s * 1.2)" % v, "(global.%s * hpk)" % v)
    assert "* 1.2)" not in s, n
    wr(n, "var hpk = 1.2;\nif (global.maxhp > 120)\n{\n    hpk = 144 / global.maxhp;\n}\n" + s)

# obj_expander (Sans fight, Room of Bog) widens the view to the window's pixel size at 1:1 so the
# battle can spill past 640x480. On a phone that renders everything at ~45% size. On Android/iOS
# widen the view to the screen's shape instead, keeping the normal zoom around the original view.
n = "gml_Object_obj_expander_Other_10"; s = rd(n)
s = rep(s, "if (expanded == 0)\n{\n", """if (expanded == 0 && (os_type == os_android || os_type == os_ios))
{
    var xs = min(window_get_width() / origwv, window_get_height() / orighv);
    var vw = round(window_get_width() / xs);
    var vh = round(window_get_height() / xs);
    __view_set(0, 0, origx - round((vw - origwv) / 2));
    __view_set(1, 0, origy - round((vh - orighv) / 2));
    __view_set(2, 0, vw);
    __view_set(3, 0, vh);
    display_set_gui_size(vw, vh);
    surface_resize(application_surface, window_get_width(), window_get_height());
    expanded = 1;
}
if (expanded == 0)
{
""")
wr(n, s)

# Food heals scale with max HP above 99, so items restore the same share of the bar as in vanilla.
n = "gml_Script_scr_recoitem"; s = rd(n)
s = "var heal = argument0;\nif (global.maxhp > 99)\n{\n    heal = round(heal * (global.maxhp / 99));\n}\n" + s
s = rep(s, "script_execute(scr_recover, argument0 + 4);", "script_execute(scr_recover, heal + 4);")
s = rep(s, "script_execute(scr_recover, argument0);", "script_execute(scr_recover, heal);")
wr(n, s)

# Blue soul: jump height depends on "up" still being held, read with keyboard_check_direct, which
# doesn't see touch-simulated keys, so every jump was cut to a minimum hop. Use keyboard_check.
for n in ["gml_Object_obj_heart_Step_0", "gml_Object_obj_heart_sansbattle_Step_0"]:
    s = rd(n)
    assert s.count("keyboard_check_direct(") == 4, n
    wr(n, s.replace("keyboard_check_direct(", "keyboard_check("))

# Sans fight damage:
# - a bullet hit takes 1 HP every frame of contact; make it every other frame.
n = "gml_Object_obj_sansb_body_Other_12"; s = rd(n)
s = rep(s, "    global.hp -= 1;\n    damageturn = 1;\n", "    global.hp -= 1;\n    damageturn = 2;\n")
wr(n, s)
n = "gml_Object_obj_sansb_body_Draw_0"; s = rd(n)
s = rep(s, "if (inv_check == 0)\n{\n    damageturn = 0;\n}\n", "if (inv_check == 0 && damageturn > 0)\n{\n    damageturn -= 1;\n}\n")
wr(n, s)
# - KR piles on extra while HP >= 60 (less from 30); those thresholds assume ~92 max HP. Scale them
#   with max HP so KR isn't at full strength for most of the fight with 200 HP.
for n in ["gml_Object_obj_sansbullet_parent_Other_17", "gml_Object_obj_menubone_Collision_af950111_6d65_46ff_a063_e0c46ecca201"]:
    s = rd(n)
    k = s.count("global.hp >= 60") + s.count("global.hp >= 30")
    assert k > 0, n
    s = s.replace("global.hp >= 60", "global.hp >= (60 * max(1, global.maxhp / 92))")
    s = s.replace("global.hp >= 30", "global.hp >= (30 * max(1, global.maxhp / 92))")
    wr(n, s)

# ---------------------------------------------------------------------------------------------
# 16 inventory slots instead of 8. Slot 8 was the end-of-list sentinel / scratch value; it moves
# to 16. Slots 9-16 are saved after the last flag so older saves still load (with them empty).
def cur(n):
    return open(os.path.join(out, n + ".gml")).read() if n in written else rd(n)

def sub(s, old, new, count=None):
    c = s.count(old)
    assert c > 0 and (count is None or c == count), (old[:70], c)
    return s.replace(old, new)

for n in ["gml_Script_scr_itemget", "gml_Script_SCR_GAMESTART", "gml_Script_scr_itemshift", "gml_Script_scr_recoitem", "gml_Script_SCR_TEXT"]:
    wr(n, sub(cur(n), "global.item[8]", "global.item[16]"))
# the "recovered N HP" message switched to "maxed out" at 99; heals can exceed that now (maxed out is 9999)
n = "gml_Script_SCR_TEXT"; wr(n, sub(cur(n), "if (global.item[16] < 99)", "if (global.item[16] < 9999)", 1))

loops = {
    "gml_Script_scr_itemname": ["for (i = 0; i < 8; i += 1)"],
    "gml_Script_scr_itemcheck": ["for (i = 0; i < 8; i += 1)"],
    "gml_Script_scr_itemget": [],
    "gml_Script_scr_itemshift": ["for (var i = argument0; i < 8; i += 1)"],
    "gml_Script_scr_itemload": ["for (i = 0; i < 8; i++)"],
    "gml_Script_scr_itemnameb": ["for (var i = 0; i < 8; i++)"],
    "gml_Script_scr_itemroom": ["for (i = 0; i < 8; i += 1)"],
    "gml_Script_SCR_GAMESTART": ["for (var i = 0; i < 8; i += 1)"],
    "gml_Object_obj_rarependant_Other_5": ["for (i = 0; i < 8; i += 1)"],
    "gml_Object_obj_rarependant_Step_1": ["for (i = 0; i < 8; i += 1)"],
    "gml_Object_obj_mainchara_Step_0": ["for (i = 0; i < 8; i++)"],
    "gml_Script_SCR_TEXT": ["for (n = 0; n < 8; n += 1)", "for (var i = 0; i < 8; i += 1)"],
}
for n, olds in loops.items():
    s = cur(n)
    for o in olds:
        s = sub(s, o, o.replace("< 8", "< 16"), 1)
    wr(n, s)
n = "gml_Script_scr_itemget"; s = cur(n)
s = sub(s, "else if (i == 8)", "else if (i == 16)", 1)
wr(n, s)
n = "gml_Script_scr_itemget"; print("itemget:", cur(n)[:400].replace("\n", " | "))
n = "gml_Script_scr_itemroom"; wr(n, sub(cur(n), "itemfree = 8;", "itemfree = 16;", 1))
# "is the inventory full" checks
n = "gml_Script_scr_itemuseb"; wr(n, sub(cur(n), "if (global.item[7] == 0)", "if (global.item[15] == 0)", 6))
n = "gml_Script_SCR_TEXT"; wr(n, sub(cur(n), "global.flag[380] > 0 && global.item[7] != 0", "global.flag[380] > 0 && global.item[15] != 0", 1))
# scr_itemremove checked slots 0-7 one by one
n = "gml_Script_scr_itemremove"; s = cur(n)
i0 = s.index("    loc = 0;\n    skip = 0;\n"); i1 = s.index("    scr_itemshift(loc, 0);")
s = s[:i0] + "    loc = 0;\n    for (var k = 15; k >= 0; k -= 1)\n    {\n        if (global.item[k] == argument0)\n        {\n            loc = k;\n        }\n    }\n" + s[i1:]
wr(n, s)

# save / load: slots 9-16 appended after flag 1023
n = "gml_Script_scr_saveprocess"; s = cur(n)
s = sub(s, "ossafe_file_text_close(myfileid);", "for (i = 8; i < 16; i += 1)\n{\n    ossafe_file_text_write_real(myfileid, global.item[i]);\n    ossafe_file_text_writeln(myfileid);\n}\nossafe_file_text_close(myfileid);", 1)
wr(n, s)
n = "gml_Script_scr_load"; s = cur(n)
s = sub(s, "global.lastsavedkills = global.kills;\nglobal.lastsavedtime = obj_time.time;\nglobal.lastsavedlv = global.lv;\nossafe_file_text_close(myfileid);",
        "for (i = 8; i < 16; i += 1)\n{\n    global.item[i] = 0;\n    if (!ossafe_file_text_eof(myfileid))\n    {\n        global.item[i] = ossafe_file_text_read_real(myfileid);\n        ossafe_file_text_readln(myfileid);\n    }\n}\nglobal.lastsavedkills = global.kills;\nglobal.lastsavedtime = obj_time.time;\nglobal.lastsavedlv = global.lv;\nossafe_file_text_close(myfileid);", 1)
wr(n, s)

# overworld item menu: 8 visible rows that scroll with the cursor
n = "gml_Object_obj_overworldcontroller_Draw_0"; s = cur(n)
s = sub(s, """        if (global.menuno == 1 || global.menuno == 5)
        {
            for (i = 0; i < 8; i += 1)
            {
                draw_text(116 + xx, 30 + yy + (i * 16), string_hash_to_newline(global.itemname[i]));
            }""", """        if (global.menuno == 1 || global.menuno == 5)
        {
            var iofs = clamp(global.menucoord[1] - 7, 0, 8);
            for (i = 0; i < 8; i += 1)
            {
                draw_text(116 + xx, 30 + yy + (i * 16), string_hash_to_newline(global.itemname[i + iofs]));
            }
            if (iofs > 0)
            {
                draw_text(216 + xx, 22 + yy, "^");
            }
            if (iofs < 8 && global.item[iofs + 8] != 0)
            {
                draw_text(216 + xx, 146 + yy, "v");
            }""", 1)
s = sub(s, """    if (global.menuno == 6)
    {
        scr_itemname();
        for (i = 0; i < 8; i += 1)
        {
            draw_text(116 + xx, 30 + yy + (i * 16), string_hash_to_newline(global.itemname[i]));
        }""", """    if (global.menuno == 6)
    {
        scr_itemname();
        var iofs6 = clamp(global.menucoord[6] - 7, 0, 8);
        for (i = 0; i < 8; i += 1)
        {
            draw_text(116 + xx, 30 + yy + (i * 16), string_hash_to_newline(global.itemname[i + iofs6]));
        }""", 1)
s = sub(s, "draw_sprite(spr_heartsmall, 0, 104 + xx, heart_y + yy + (16 * global.menucoord[1]));",
        "draw_sprite(spr_heartsmall, 0, 104 + xx, heart_y + yy + (16 * (global.menucoord[1] - clamp(global.menucoord[1] - 7, 0, 8))));", 1)
s = sub(s, "draw_sprite(spr_heartsmall, 0, 104 + xx, heart_y + yy + (16 * global.menucoord[6]));",
        "draw_sprite(spr_heartsmall, 0, 104 + xx, heart_y + yy + (16 * (global.menucoord[6] - clamp(global.menucoord[6] - 7, 0, 8))));", 1)
s = sub(s, "            if (global.menucoord[1] != 7)\n", "            if (global.menucoord[1] != 15)\n", 1)
s = sub(s, "            if (global.menucoord[6] != 7)\n", "            if (global.menucoord[6] != 15)\n", 1)
wr(n, s)

# Dimensional Box: inventory column scrolls the same way
n = "gml_Object_obj_itemswapper_Draw_0"; s = cur(n)
s = sub(s, """    scr_itemname();
    for (i = 0; i < 8; i += 1)
    {
        draw_set_color(c_white);
        draw_text(xx + boxofs + 3 + itemofs, yy + 30 + (i * 16), string_hash_to_newline(global.itemname[i]));
        if (global.item[i] == 0)""", """    scr_itemname();
    var iofs = clamp(c0y - 7, 0, 8);
    for (i = 0; i < 8; i += 1)
    {
        draw_set_color(c_white);
        draw_text(xx + boxofs + 3 + itemofs, yy + 30 + (i * 16), string_hash_to_newline(global.itemname[i + iofs]));
        if (global.item[i + iofs] == 0)""", 1)
s = sub(s, "            if (c0y > 7)\n            {\n                c0y = 7;\n            }", "            if (c0y > 15)\n            {\n                c0y = 15;\n            }", 1)
s = sub(s, "        if (column == 0 && c0y < 7)\n", "        if (column == 0 && c0y < 15)\n", 1)
s = sub(s, "draw_sprite(spr_heartsmall, 0, xx + boxofs + 3 + heartofs, yy + 35 + (16 * c0y));",
        "draw_sprite(spr_heartsmall, 0, xx + boxofs + 3 + heartofs, yy + 35 + (16 * (c0y - clamp(c0y - 7, 0, 8))));", 1)
wr(n, s)

# Battle item menu. Classic: pages of 4 at bmenuno 3, 3.25, 3.5, 3.75 (was 3 and 3.5). New-style
# scrolling list: one item per 0.0625 step (was 0.125). In both, slot = bmenucoord[3] + (bmenuno - 3) * 16.
for n in ["gml_Object_obj_battlecontroller_Step_0", "gml_Object_obj_battlecontroller_Step_1", "gml_Object_obj_battlecontroller_Draw_0"]:
    s = cur(n)
    s = s.replace("((global.bmenuno - 3) * 8)", "((global.bmenuno - 3) * 16)")
    s = s.replace("(global.bmenuno - 3) * 8;", "(global.bmenuno - 3) * 16;")
    s = s.replace("global.bmenuno -= 0.125;", "global.bmenuno -= 0.0625;").replace("global.bmenuno += 0.125;", "global.bmenuno += 0.0625;")
    s = s.replace("if (tempcheck < 7 && global.item[tempcheck + 1] != 0)", "if (tempcheck < 15 && global.item[tempcheck + 1] != 0)")
    wr(n, s)
n = "gml_Object_obj_battlecontroller_Step_0"; s = cur(n)
s = sub(s, "                                for (var i = 0; i < 8; i++)\n", "                                for (var i = 0; i < 16; i++)\n", 1)
wr(n, s)
n = "gml_Object_obj_battlecontroller_Draw_0"; s = cur(n)
s = sub(s, "        for (var i = 0; i < 8; i++)\n        {\n            global.itemnameb[i] = global.itemname[i];", "        for (var i = 0; i < 16; i++)\n        {\n            global.itemnameb[i] = global.itemname[i];", 1)
s = sub(s, "    var num_items = 8;\n", "    var num_items = 16;\n", 1)
wr(n, s)

# classic menu up/down: page offset was "+4 if on page 2"
n = "gml_Object_obj_battlecontroller_Step_1"; s = cur(n)
s = sub(s, "                if (global.bmenuno == 3.5)\n                {\n                    tempcheck += 4;\n                }\n",
        "                tempcheck += round((global.bmenuno - 3) * 16);\n", 1)

# classic menu left/right: replace the hard-coded two-page logic with a general version
LEFT = """var mv = 0;
if (global.bmenuno >= 3 && global.bmenuno < 4)
{
    var cnt = 0;
    while (cnt < 16 && global.item[cnt] != 0)
    {
        cnt += 1;
    }
    var lastpg = max(0, floor((cnt - 1) / 4));
    var pg = round((global.bmenuno - 3) * 4);
    var c = global.bmenucoord[3];
    var r = c - (c % 2);
    if ((c % 2) == 1)
    {
        global.bmenucoord[3] = c - 1;
        mv = 1;
    }
    else
    {
        var np = pg - 1;
        if (np < 0)
        {
            np = lastpg;
        }
        if (np != pg)
        {
            if (global.item[(np * 4) + r + 1] != 0)
            {
                global.bmenucoord[3] = r + 1;
            }
            else if (global.item[(np * 4) + r] != 0)
            {
                global.bmenucoord[3] = r;
            }
            else
            {
                global.bmenucoord[3] = 0;
            }
            global.bmenuno = 3 + (np * 0.25);
            mv = 1;
        }
        else if (global.item[(pg * 4) + r + 1] != 0)
        {
            global.bmenucoord[3] = r + 1;
            mv = 1;
        }
    }
    if (mv != 0)
    {
        snd_play(snd_squeak);
    }
    if (round((global.bmenuno - 3) * 4) != pg)
    {
        script_execute(scr_itemrewrite);
    }
}
"""
RIGHT = """var mv = 0;
if (global.bmenuno >= 3 && global.bmenuno < 4)
{
    var cnt = 0;
    while (cnt < 16 && global.item[cnt] != 0)
    {
        cnt += 1;
    }
    var lastpg = max(0, floor((cnt - 1) / 4));
    var pg = round((global.bmenuno - 3) * 4);
    var c = global.bmenucoord[3];
    var r = c - (c % 2);
    if ((c % 2) == 0 && global.item[(pg * 4) + r + 1] != 0)
    {
        global.bmenucoord[3] = r + 1;
    }
    else
    {
        var np = pg + 1;
        if (np > lastpg)
        {
            np = 0;
        }
        if (np != pg)
        {
            if (global.item[(np * 4) + r] != 0)
            {
                global.bmenucoord[3] = r;
            }
            else
            {
                global.bmenucoord[3] = 0;
            }
            global.bmenuno = 3 + (np * 0.25);
        }
        else
        {
            global.bmenucoord[3] = r;
        }
    }
    mv = 1;
    snd_play(snd_squeak);
    if (round((global.bmenuno - 3) * 4) != pg)
    {
        script_execute(scr_itemrewrite);
    }
}
"""
def indent(block, n):
    return "".join((" " * n + l if l.strip() else l) for l in block.splitlines(True))
def replace_classic(s, start_marker, indent_n):
    # body of the "else" after "if (global.newmenu) { }" at the given indentation
    pad = " " * indent_n
    head = pad + "if (global.newmenu)\n" + pad + "{\n" + pad + "}\n" + pad + "else\n" + pad + "{\n"
    i = s.index(head, s.index(start_marker))
    b0 = i + len(head)
    depth = 1; j = b0
    while depth:
        if s[j] == "{": depth += 1
        elif s[j] == "}": depth -= 1
        j += 1
    end = s.rindex("\n", 0, j - 1) + 1   # start of the closing-brace line
    return s[:b0], s[end:]
pre, post = replace_classic(s, "if (global.myfight != 4)", 12)
s = pre + indent(LEFT, 16) + post
pre, post = replace_classic(s, "if (keyboard_check_pressed(global.keybind[2])" if "if (keyboard_check_pressed(global.keybind[2])" in s else "    if (global.myfight != 4)\n    {\n        if (global.newmenu)", 8)
s = pre + indent(RIGHT, 12) + post
assert "3.5" not in s, "leftover 3.5"
wr(n, s)

# page text: one generic page instead of hard-coded pages 1 and 2
n = "gml_Script_scr_itemrewrite"; s = cur(n)
s = sub(s, "if (global.bmenuno == 3)\n{\n    global.msc = 9;\n}\nif (global.bmenuno == 3.5)\n{\n    global.msc = 10;\n}\n",
        "if (global.bmenuno >= 3 && global.bmenuno < 4)\n{\n    global.msc = 9;\n}\n", 1)
wr(n, s)
n = "gml_Script_SCR_TEXT"; s = cur(n)
i0 = s.index("    case 9:\n"); i1 = s.index("    case 11:\n")
s = s[:i0] + """    case 9:
    case 10:
        var ipg = round((global.bmenuno - 3) * 4);
        if (ipg < 0 || ipg > 3)
        {
            ipg = 0;
        }
        var ib = ipg * 4;
        global.msg[0] = scr_gettext("item_menub_header") + global.itemnameb[ib];
        if (global.item[ib + 1] != 0)
        {
            global.msg[0] += scr_gettext("item_menub_header") + global.itemnameb[ib + 1];
        }
        global.msg[0] += "&";
        if (global.item[ib + 2] != 0)
        {
            global.msg[0] += scr_gettext("item_menub_header") + global.itemnameb[ib + 2];
        }
        if (global.item[ib + 3] != 0)
        {
            global.msg[0] += scr_gettext("item_menub_header") + global.itemnameb[ib + 3];
        }
        global.msg[0] += "&" + string_replace(scr_gettext("item_menub_page1"), "1", string(ipg + 1));
        global.msg[1] = "%%%";
        break;
""" + s[i1:]
wr(n, s)

# Game over, pause and the boss-defeat scenes deactivate every instance and re-activate a fixed
# list; put the touch controller back so its buttons stay usable.
for n in ["gml_Object_obj_gameover_anim_Step_0", "gml_Object_obj_pausecontroller_Step_0",
          "gml_Object_obj_robog_heartdefeated_Create_0", "gml_Object_obj_vsflowey_heartdefeated_Create_0"]:
    s = cur(n)
    k = s.count("instance_deactivate_all(true);")
    assert k >= 1, n
    s = s.replace("instance_deactivate_all(true);", "instance_deactivate_all(true);\ninstance_activate_object(obj_mobilecontroller);")
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
