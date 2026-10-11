import { expect, test } from "bun:test";
import { aggregate, percentile, validate, csv, type Dataset } from "./aggregate";
import { parsePss, processIds } from "./adb";
const config = {blockerMode:"ETP_ONLY" as const,browser:"Velocity",version:"0.1",android:"36",device:"Pixel 7 Pro",soc:"Tensor G2",ramMiB:12288,refreshHz:120,network:"fixed-wifi",pageLabel:"fixture-root",cache:"cold" as const,adaptive:true,preconnect:true,tabKind:"warm" as const,timestamp:"2026-10-11T00:00:00Z",temperatureC:25,batteryPct:80};
function dataset(): Dataset { return {kind:"SYNTHETIC_TEST_ONLY",samples:[1,2,3,4,1000].map(value=>({config,metric:"TTUI",unit:"ms",value,failed:false}))}; }
test("quantiles retain outliers",()=>{expect(percentile([1,2,3,4,1000],.5)).toBe(3);expect(percentile([1,2,3,4,1000],.95)).toBeCloseTo(800.8);});
test("failure counted, not silently removed",()=>{const d=dataset();d.samples.push({...d.samples[0]!,failed:true,value:null});const r=aggregate(d);expect(r.groups[0]!.failures).toBe(1);expect(r.groups[0]!.attempts).toBe(6);expect(r.groups[0]!.n).toBe(5);expect(r.status).toBe("SYNTHETIC_TEST_ONLY");expect(csv(r)).toContain("SYNTHETIC_TEST_ONLY");});
test("warm cold and preconnect conditions separated",()=>{const d=dataset();d.samples.push({...d.samples[0]!,config:{...config,tabKind:"cold"}});d.samples.push({...d.samples[0]!,config:{...config,preconnect:false}});expect(aggregate(d).groups.length).toBe(3);});
test("empty is explicitly NOT_MEASURED",()=>{expect(aggregate({kind:"MEASURED",samples:[]}).status).toBe("NOT_MEASURED");expect(percentile([],.95)).toBeNull();});
test("invalid data rejected",()=>{const d=dataset();d.samples[0]!.value=NaN;expect(()=>validate(d)).toThrow();d.samples[0]!.value=1;d.samples[0]!.config={...config,pageLabel:"https://secret.example/?token=x"};expect(()=>validate(d)).toThrow();});
test("all failures have no made up quantiles",()=>{const d=dataset();d.samples=d.samples.map(s=>({...s,failed:true,value:null}));expect(aggregate(d).groups[0]!.p95).toBeNull();});
test("PSS formats and errors",()=>{expect(parsePss(" TOTAL PSS: 12345 TOTAL RSS: 22000")).toBe(12345);expect(parsePss(" TOTAL 1200 500 3")).toBe(1200);expect(()=>parsePss("no process")).toThrow();});
test("all app children, no unrelated browser",()=>{expect(processIds("PID UID NAME\n100 u0_a1 dev.velocity.browser\n101 u0_i1 dev.velocity.browser:tab0\n102 u0_a1 dev.velocity.browser:gpu\n103 u0_a2 com.chrome", "dev.velocity.browser")).toEqual(["100","101","102"]);expect(()=>processIds("PID UID NAME", "x;rm")).toThrow();});

test("blocker modes do not mix",()=>{const d=dataset();for(const blockerMode of ["ALL_OFF","LITE","ADGUARD"] as const) d.samples.push({...d.samples[0]!,config:{...config,blockerMode}});expect(aggregate(d).groups.length).toBe(4);});

import { parseStat } from './cpu';
test("CPU stat parses spaces in process names and rejects denied reads",()=>{
  const fields=Array(20).fill('0');fields[0]='S';fields[11]='25';fields[12]='15';fields[19]='999';
  expect(parseStat('1 (name with spaces) '+fields.join(' '))).toEqual({ticks:40,start:'999'});
  expect(()=>parseStat('Permission denied')).toThrow();
});
