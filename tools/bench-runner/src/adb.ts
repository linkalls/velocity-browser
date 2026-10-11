export function parsePss(text: string): number {
  const match = text.match(/TOTAL PSS:\s*(\d+)/) ?? text.match(/^\s*TOTAL\s+(\d+)\s+/m);
  if (!match) throw Error("PSS unavailable: measurement failed");
  return Number(match[1]);
}
export function processIds(text: string, packageName: string): string[] {
  if (!/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$/.test(packageName)) throw Error("invalid package");
  const rows = text.trim().split("\n").slice(1).map(row=>row.trim().split(/\s+/));
  const appUid = rows.find(r=>r[2]===packageName)?.[1];
  const pids = rows.filter(r=>r[2]===packageName || r[2]?.startsWith(packageName+":") || (appUid && r[1]===appUid)).map(r=>r[0]!);
  if (!pids.length || pids.some(pid=>!/^\d+$/.test(pid))) throw Error("app processes not found");
  return [...new Set(pids)];
}
export async function adb(args: string[]): Promise<string> {
  const p = Bun.spawn(["adb",...args],{stdout:"pipe",stderr:"pipe"});
  const [out, err, exit] = await Promise.all([new Response(p.stdout).text(),new Response(p.stderr).text(),p.exited]);
  if (exit !== 0) throw Error("ADB failed; check device connection"); // ページ等を含む可能性のあるstderrを転記しない。
  return out;
}
export async function memory(packageName: string) {
  const before = processIds(await adb(["shell","ps","-A","-o","PID,UID,NAME"]),packageName);
  const values = [];
  for (const pid of before) values.push({pid,pssKiB:parsePss(await adb(["shell","dumpsys","meminfo",pid]))});
  const after = processIds(await adb(["shell","ps","-A","-o","PID,UID,NAME"]),packageName);
  if (JSON.stringify([...before].sort()) !== JSON.stringify([...after].sort())) throw Error("process list changed; retry measurement");
  return { status:"MEASURED",packageName,timestamp:new Date().toISOString(), processes:values,totalPssKiB:values.reduce((a,v)=>a+v.pssKiB,0),
    device:(await adb(["shell","getprop","ro.product.model"])).trim(),android:(await adb(["shell","getprop","ro.build.version.release"])).trim() };
}
