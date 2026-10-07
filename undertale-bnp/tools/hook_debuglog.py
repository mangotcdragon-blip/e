# Starts com.utmod.DebugLog first thing in RunnerActivity.onCreate.
import sys
p = sys.argv[1]
s = open(p).read()
old = '    .prologue\n    invoke-static {p0}, Lcom/utmod/SaveBridge;->start(Landroid/app/Activity;)V\n'
new = ('    .prologue\n    invoke-static {p0}, Lcom/utmod/DebugLog;->start(Landroid/content/Context;)V\n\n'
       '    invoke-static {p0}, Lcom/utmod/SaveBridge;->start(Landroid/app/Activity;)V\n')
assert s.count(old) == 1
open(p, "w").write(s.replace(old, new))
