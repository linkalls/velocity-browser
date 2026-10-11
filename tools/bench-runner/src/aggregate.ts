export type BlockerMode = "ALL_OFF" | "ETP_ONLY" | "LITE" | "ADGUARD";
export type Config = {
  blockerMode: BlockerMode;
  browser: string; version: string; android: string; device: string; soc: string;
  ramMiB: number; refreshHz: number; network: string; pageLabel: string;
  cache: "cold" | "warm" | "hot"; adaptive: boolean; preconnect: boolean;
  tabKind: "warm" | "cold" | "none"; timestamp: string; temperatureC: number; batteryPct: number;
};
export type Sample = { config: Config; metric: string; unit: string; value: number | null; failed: boolean };
export type Dataset = { kind: "MEASURED" | "SYNTHETIC_TEST_ONLY"; samples: Sample[] };
const metrics = new Set(["TTFF", "TTUI", "TTFirstContent", "TabSwitchLatency", "ColdTabRestore", "MemoryPSS1", "MemoryPSS5", "MemoryPSS20", "MemoryAfterIdle120s", "Jank", "EnergyPerJourney", "RestoreSuccess", "PageLoad", "CpuTime", "TransferredBytes"]);
export function validate(raw: unknown): Dataset {
  const d = raw as Dataset;
  if (!d || !["MEASURED", "SYNTHETIC_TEST_ONLY"].includes(d.kind) || !Array.isArray(d.samples)) throw Error("invalid dataset");
  for (const s of d.samples) {
    if (!s || typeof s.failed !== "boolean" || !metrics.has(s.metric) || !["ms", "KiB", "%", "mWh", "count", "bytes"].includes(s.unit)) throw Error("invalid sample");
    if (s.failed ? s.value !== null : typeof s.value !== "number" || !Number.isFinite(s.value) || s.value < 0) throw Error("invalid sample value");
    const c = s.config;
    if (!c || !["ALL_OFF", "ETP_ONLY", "LITE", "ADGUARD"].includes(c.blockerMode) || ["browser", "version", "android", "device", "soc", "network", "pageLabel", "timestamp"].some(k => typeof (c as any)[k] !== "string" || !(c as any)[k])) throw Error("missing configuration");
    if (["ramMiB", "refreshHz", "temperatureC", "batteryPct"].some(k => !Number.isFinite((c as any)[k]))) throw Error("invalid configuration number");
    if (!["cold", "warm", "hot"].includes(c.cache) || !["warm", "cold", "none"].includes(c.tabKind) || typeof c.adaptive !== "boolean" || typeof c.preconnect !== "boolean") throw Error("invalid conditions");
    if (c.batteryPct < 0 || c.batteryPct > 100 || c.ramMiB <= 0 || c.refreshHz <= 0 || !Number.isFinite(Date.parse(c.timestamp))) throw Error("invalid device conditions");
    if (Object.keys(c).some(k => !["blockerMode","browser","version","android","device","soc","ramMiB","refreshHz","network","pageLabel","cache","adaptive","preconnect","tabKind","timestamp","temperatureC","batteryPct"].includes(k))) throw Error("unknown config key");
    if (Object.values(c).some(v => typeof v === "string" && (/https?:\/\//i.test(v) || /[\r\n]/.test(v)))) throw Error("use a non-sensitive page label, not a URL");
  }
  return d;
}
export function percentile(values: number[], p: number): number | null {
  if (!values.length) return null;
  const sorted = [...values].sort((a,b) => a-b), index = (sorted.length-1)*p;
  const lower = Math.floor(index), upper = Math.ceil(index);
  return sorted[lower]! + (sorted[upper]! - sorted[lower]!) * (index-lower);
}
export function aggregate(data: Dataset) {
  validate(data);
  const groups = new Map<string, { sample: Sample; values: number[]; failures: number; attempts: number; timestamps: string[]; temperatures: number[]; batteries: number[] }>();
  for (const s of data.samples) {
    const { timestamp, temperatureC, batteryPct, ...conditions } = s.config;
    const key = JSON.stringify([...Object.entries(conditions).sort(([a],[b])=>a.localeCompare(b)), ["metric",s.metric], ["unit",s.unit]]);
    const g = groups.get(key) ?? {sample:s, values:[], failures:0, attempts:0, timestamps:[], temperatures:[], batteries:[]};
    g.attempts++; g.timestamps.push(timestamp); g.temperatures.push(temperatureC); g.batteries.push(batteryPct);
    if (s.failed) g.failures++; else g.values.push(s.value!);
    groups.set(key,g);
  }
  return { kind: data.kind, status: data.samples.length ? data.kind : "NOT_MEASURED", groups: [...groups.values()].map(g=> ({
    config:g.sample.config, metric:g.sample.metric, unit:g.sample.unit, n:g.values.length,
    attempts:g.attempts, failures:g.failures, under30:g.values.length<30,
    p50:percentile(g.values,0.5), p90:percentile(g.values,0.9), p95:percentile(g.values,0.95),
    timestamps:g.timestamps, temperatureRangeC:[Math.min(...g.temperatures),Math.max(...g.temperatures)], batteryRangePct:[Math.min(...g.batteries),Math.max(...g.batteries)]
  })) };
}
export function csv(result: ReturnType<typeof aggregate>): string {
  const quote = (v: unknown) => '"' + String(v ?? "NOT_MEASURED").replaceAll('"','""') + '"';
  return ["kind,config,metric,unit,n,attempts,failures,p50,p90,p95", ...result.groups.map(g=> [result.kind,JSON.stringify(g.config),g.metric,g.unit,g.n,g.attempts,g.failures,g.p50,g.p90,g.p95].map(quote).join(","))].join("\n")+"\n";
}
