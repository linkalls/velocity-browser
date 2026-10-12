"""Preserve per-run data and failures; summaries do not infer optimization success."""
import json,sys
from pathlib import Path
root=Path(sys.argv[1]);samples=[]
for f in sorted(root.glob('*-frames.json')):
    data=json.loads(f.read_text());prefix=f.name.removesuffix('-frames.json')
    def read(suffix):
        p=root/(prefix+suffix)
        return json.loads(p.read_text()) if p.exists() else {'status':'UNVERIFIED'}
    launch=(root/(prefix+'-launch.txt')).read_text()
    samples.append(dict(sample=prefix,frames=data,memory=read('-memory.json'),cpu=read('-cpu.json'),activityLaunchRaw=launch))
(root/'summary.json').write_text(json.dumps(dict(environment='API36 x86_64 disposable emulator',build='R8 benchmark, profileable, x86_64 ABI override and identical Kotlin runtime keep rules',expectedSamples=20,actualSamples=len(samples),status='CAPTURED' if len(samples)==20 else 'INCOMPLETE',physicalPixel7Pro='UNVERIFIED',optimizationSuccess='NOT_ESTABLISHED',startupMeaning='instrumented am start -W activity TotalTime; not FCP or guaranteed cold start',osPageCache='UNCONTROLLED',instrumentation='same UiAutomation readiness driver in both APKs; extra test code overhead',samples=samples),indent=2)+'\n')
if len(samples)!=20:raise SystemExit('Incomplete capture; not a successful comparison')
