import { expect, test } from "bun:test";
import { resolve } from "node:path";
test("app has no URL/user-content logging or secondary page engine", async () => {
  const root = resolve(import.meta.dir, "../../..");
  const glob = new Bun.Glob("app/src/main/java/**/*.kt");
  let files = 0;
  for await (const file of glob.scan({ cwd: root, absolute: true })) {
    const code = await Bun.file(file).text(); files++;
    expect(code).not.toMatch(/\b(?:Log\.[vdiew]|println|print)\s*\(/);
    expect(code).not.toMatch(/import\s+android\.webkit\.WebView|import\s+.*CustomTabs/);
  }
  expect(files).toBeGreaterThan(5);
});
