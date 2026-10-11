import { aggregate, csv, validate } from "./aggregate";
import { cpu } from "./cpu";
import { memory } from "./adb";
const [command,...args] = Bun.argv.slice(2);
try {
  if (command === "aggregate") {
    if (args.length !== 2) throw Error("usage: aggregate input.json output-prefix");
    const data = validate(await Bun.file(args[0]!).json()), report = aggregate(data);
    await Bun.write(args[1]!+".json",JSON.stringify(report,null,2)+"\n");
    await Bun.write(args[1]!+".csv",csv(report));
    console.log(`${report.status}: ${report.groups.length} groups`);
  } else if (command === "memory") {
    if (args.length !== 2) throw Error("usage: memory package.name output.json");
    await Bun.write(args[1]!,JSON.stringify(await memory(args[0]!),null,2)+"\n");
    console.log("PSS measured including matching child processes");
  } else if (command === "cpu") {
    if (args.length !== 3) throw Error("usage: cpu package.name durationMs output.json");
    await Bun.write(args[2]!,JSON.stringify(await cpu(args[0]!,Number(args[1]!)),null,2)+"\n");
  } else if (command === "size") {
    if (args.length !== 3) throw Error("usage: size browser.apk baseline.apk output.json");
    const app=Bun.file(args[0]!), baseline=Bun.file(args[1]!);
    if (!(await app.exists()) || !(await baseline.exists())) throw Error("APK missing");
    await Bun.write(args[2]!,JSON.stringify({status:"MEASURED",apkBytes:app.size,baselineBytes:baseline.size,additionalBytes:app.size-baseline.size,withinEffortBudget:app.size-baseline.size<=2*1024*1024,installSize:"NOT_MEASURED"},null,2)+"\n");
  } else throw Error("usage: bun src/cli.ts aggregate|memory|cpu|size ...");
} catch (error) { console.error(error instanceof Error ? error.message : "measurement failed"); process.exitCode=1; }
