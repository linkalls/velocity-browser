import { adb, processIds } from './adb';
export function parseStat(text: string) {
  const end = text.lastIndexOf(')');
  if (end < 0) throw Error('CPU stat unavailable');
  const fields = text.slice(end + 1).trim().split(/\s+/);
  const user = Number(fields[11]), system = Number(fields[12]), start = fields[19];
  if (!Number.isSafeInteger(user) || user < 0 || !Number.isSafeInteger(system) || system < 0 || !start || !/^\d+$/.test(start)) throw Error('invalid CPU stat');
  return {ticks:user + system, start};
}
export async function cpu(packageName: string, durationMs: number) {
  if (!Number.isSafeInteger(durationMs) || durationMs < 1000 || durationMs > 60000) throw Error('durationMs must be 1000..60000');
  const clockTicks = Number((await adb(['shell','getconf','CLK_TCK'])).trim());
  if (!Number.isFinite(clockTicks) || clockTicks <= 0) throw Error('clock tick rate unavailable');
  async function snapshot() {
    const pids = processIds(await adb(['shell','ps','-A','-o','PID,UID,NAME']),packageName).sort();
    const processes = new Map<string,ReturnType<typeof parseStat>>();
    for (const pid of pids) processes.set(pid,parseStat(await adb(['shell','cat',`/proc/${pid}/stat`])));
    return {pids,processes};
  }
  const before = await snapshot(), begin = performance.now();
  await Bun.sleep(durationMs);
  const after = await snapshot(), wallMs = performance.now()-begin;
  if (JSON.stringify(before.pids) !== JSON.stringify(after.pids)) throw Error('CPU process set changed; use Perfetto for short-lived children');
  let ticks = 0;
  for (const pid of before.pids) {
    const a = before.processes.get(pid)!, b = after.processes.get(pid)!;
    if (a.start !== b.start || b.ticks < a.ticks) throw Error('CPU process restarted');
    ticks += b.ticks-a.ticks;
  }
  const cpuMs = ticks*1000/clockTicks;
  return {status:'MEASURED',method:'proc-stat-stable-process-set',packageName,timestamp:new Date().toISOString(),pids:before.pids,wallMs,cpuMs,cpuPct:100*cpuMs/wallMs,clockTicks,
    limitation:'snapshot sampling excludes short-lived processes; proc access may be denied; use Perfetto for release comparison'};
}
