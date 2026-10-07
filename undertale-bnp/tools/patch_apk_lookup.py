# The touch APK finds its own APK file via getApplicationInfo("com.jockeholm.undertale").
# Under a renamed package that resolves to the *other* installed app (or crashes if it's
# absent), so look up the app's own package name instead.
import sys
p = sys.argv[1]
s = open(p).read()
old = '    :try_start_0\n    const-string v18, "com.jockeholm.undertale"\n'
new = ('    :try_start_0\n    move-object/from16 v0, p0\n\n'
       '    iget-object v0, v0, Lcom/jockeholm/undertale/DemoRenderer;->m_context:Landroid/content/Context;\n\n'
       '    invoke-virtual {v0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;\n\n'
       '    move-result-object v18\n')
assert s.count(old) == 1
open(p, "w").write(s.replace(old, new))
