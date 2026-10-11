"""Report actual Android FrameTimeline rows; no conversion to Gecko FPS."""
import csv, io, json, math, subprocess, sys, tempfile
from pathlib import Path
query = "SELECT layer_name, dur, jank_type FROM actual_frame_timeline_slice WHERE layer_name LIKE '%dev.velocity.browser%';"
with tempfile.NamedTemporaryFile(mode='w',suffix='.sql') as f:
    f.write(query);f.flush()
    r=subprocess.run([sys.argv[2],'--csv','-q',f.name,sys.argv[1]],capture_output=True,text=True)
    if r.returncode: raise SystemExit('FrameTimeline query failed: '+r.stderr[-2000:])
rows=list(csv.DictReader(io.StringIO(r.stdout)))
groups={}
for row in rows:
    layer=row['layer_name'];dur=int(row['dur']);jank=row['jank_type']
    g=groups.setdefault(layer,dict(durations=[],incomplete=0,jank=0,unknownJank=0))
    if dur<0:g['incomplete']+=1;continue
    g['durations'].append(dur/1e6)
    if jank in ('None','No Jank'):pass
    elif jank in ('','[NULL]'):g['unknownJank']+=1
    else:g['jank']+=1
report=[]
for layer,g in groups.items():
    values=sorted(g.pop('durations'));n=len(values)
    def q(p):return values[max(0,math.ceil(p*n)-1)] if n else None
    report.append(dict(layer=layer,frames=n,durationMs=dict(p50=q(.5),p95=q(.95),p99=q(.99)),**g))
Path(sys.argv[3]).write_text(json.dumps(dict(status='MEASURED' if report else 'NOT_MEASURED',metric='Android FrameTimeline actual duration, not Gecko FPS',layers=report),indent=2)+'\n')
