using System.IO; using UndertaleModLib.Util;
var outd = Environment.GetEnvironmentVariable("OUTD");
var tw = new TextureWorker();
foreach (var n in new[]{"spr_control_zkey","spr_control_xkey","spr_control_ckey","spr_joybase","spr_joystick"}) {
  var s = Data.Sprites.ByName(n);
  for (int i = 0; i < s.Textures.Count; i++) tw.ExportAsPNG(s.Textures[i].Texture, Path.Combine(outd, $"{n}_{i}.png"), null, true);
}
tw.Dispose();
