using System.IO; using System.Linq; using ImageMagick; using UndertaleModLib.Util; using UndertaleModLib.Compiler;
var port = Environment.GetEnvironmentVariable("PORT");

// --- touch controller sprites, packed side by side on one new texture page
string[] names = { "spr_control_zkey", "spr_control_xkey", "spr_control_ckey", "spr_joybase", "spr_joystick" };
int[] origin = { 0, 0, 0, 0, 0, 0, 29, 29, 20, 20 };
var frames = new List<(string spr, int idx, MagickImage img)>();
foreach (var n in names)
    for (int i = 0; File.Exists(Path.Combine(port, "spr", $"{n}_{i}.png")); i++)
        frames.Add((n, i, TextureWorker.ReadBGRAImageFromFile(Path.Combine(port, "spr", $"{n}_{i}.png"))));
int atlasW = frames.Sum(f => (int)f.img.Width + 2), atlasH = frames.Max(f => (int)f.img.Height);
var atlas = new MagickImage(MagickColors.Transparent, (uint)atlasW, (uint)atlasH);
var tex = new UndertaleEmbeddedTexture();
tex.Name = new UndertaleString("Texture " + Data.EmbeddedTextures.Count);
int ax = 0;
var pages = new List<UndertaleTexturePageItem>();
foreach (var f in frames) {
    atlas.Composite(f.img, ax, 0, CompositeOperator.Copy);
    var tpi = new UndertaleTexturePageItem {
        Name = new UndertaleString("PageItem " + Data.TexturePageItems.Count),
        SourceX = (ushort)ax, SourceY = 0, SourceWidth = (ushort)f.img.Width, SourceHeight = (ushort)f.img.Height,
        TargetX = 0, TargetY = 0, TargetWidth = (ushort)f.img.Width, TargetHeight = (ushort)f.img.Height,
        BoundingWidth = (ushort)f.img.Width, BoundingHeight = (ushort)f.img.Height, TexturePage = tex };
    Data.TexturePageItems.Add(tpi);
    pages.Add(tpi);
    ax += (int)f.img.Width + 2;
}
tex.TextureData.Image = GMImage.FromMagickImage(atlas).ConvertToPng();
Data.EmbeddedTextures.Add(tex);
var template = Data.Sprites.ByName("spr_heart");
for (int k = 0; k < names.Length; k++) {
    if (Data.Sprites.ByName(names[k]) != null) throw new Exception(names[k] + " already exists");
    var fr = frames.Select((f, i) => (f, i)).Where(t => t.f.spr == names[k]).ToList();
    var img = fr[0].f.img;
    var spr = new UndertaleSprite {
        Name = Data.Strings.MakeString(names[k]), Width = img.Width, Height = img.Height,
        MarginLeft = 0, MarginTop = 0, MarginRight = (int)img.Width - 1, MarginBottom = (int)img.Height - 1,
        OriginX = origin[k * 2], OriginY = origin[k * 2 + 1], BBoxMode = 0, SepMasks = UndertaleSprite.SepMaskType.AxisAlignedRect,
        Transparent = template.Transparent, Smooth = template.Smooth, Preload = template.Preload,
        SVersion = template.SVersion, SSpriteType = template.SSpriteType, IsSpecialType = template.IsSpecialType,
        GMS2PlaybackSpeed = template.GMS2PlaybackSpeed, GMS2PlaybackSpeedType = template.GMS2PlaybackSpeedType };
    foreach (var t in fr) spr.Textures.Add(new UndertaleSprite.TextureEntry { Texture = pages[t.i] });
    if (template.CollisionMasks.Count > 0) spr.CollisionMasks.Add(spr.NewMaskEntry(Data));
    Data.Sprites.Add(spr);
}

// --- new code: the controller object and the save bridge scripts
string Gml(string n) => File.ReadAllText(Path.Combine(port, "gml", n + ".gml"));
var created = new[] { "gml_Object_obj_mobilecontroller_Create_0", "gml_Object_obj_mobilecontroller_Destroy_0",
    "gml_Object_obj_mobilecontroller_Other_4", "gml_Object_obj_mobilecontroller_Step_0", "gml_Object_obj_mobilecontroller_Draw_64",
    "gml_Script_scr_ut_request", "gml_Script_scr_ut_export", "gml_Script_scr_ut_import", "gml_Script_scr_ut_savebridge" };
foreach (var n in created) if (Data.Code.ByName(n) != null) throw new Exception(n + " already exists");
var g1 = new CodeImportGroup(Data);
foreach (var n in created) g1.QueueReplace(n, Gml(n));
g1.Import();
var mc = Data.GameObjects.ByName("obj_mobilecontroller");
mc.Persistent = true; mc.Visible = true;

// --- edited existing code
var g2 = new CodeImportGroup(Data) { AutoCreateAssets = false };
foreach (var f in Directory.GetFiles(Path.Combine(port, "gml"), "*.gml")) {
    var n = Path.GetFileNameWithoutExtension(f);
    if (created.Contains(n)) continue;
    if (Data.Code.ByName(n) == null) throw new Exception("missing " + n);
    g2.QueueReplace(n, File.ReadAllText(f));
}
g2.Import();
Console.WriteLine("PORT OK: sprites=" + names.Length + " frames=" + frames.Count);
