# Frame-time comparison protocol

Run ordinary, local-fixture pages on the same Pixel 7 Pro, OS, refresh rate, brightness, thermal state, network and tab/blocker settings. Avoid private or personal pages. Use separate clean profiles for the baseline 0.3.1 and current 0.4.0; do not overwrite or delete the user's production profile. Use the same page/scroll gesture sequence, with static and bounded dynamic fixtures, at least 5 recorded runs each. Record startup cold/warm distributions and all Gecko child-process PSS with the existing Bun runner separately.

`bash tools/scroll-trace.sh static baseline-static.perfetto-trace` captures 15 seconds of Android FrameTimeline, view/input and process scheduling. Scroll the fixture during the capture; use dynamic for the second scene. Analyze traces with Perfetto/trace_processor and distinguish host UI, Gecko surfaces, expected/actual frame timelines and genuine dropped frames. A SurfaceView host frame counter alone cannot establish Gecko rendering FPS.

Store device/build/config metadata and trace hashes with results. Keep failures, outliers, missed frames and incomplete captures visible. The script records traces only; it does not output invented FPS or convert capture success into an optimization claim. Baseline/current CPU/PSS/startup/frame-time comparison and Pixel 7 Pro capture remain NOT_MEASURED in this environment.

Fixtures: `bun tools/bench-runner/src/scroll-fixture-server.ts 8181`, then `adb reverse tcp:8181 tcp:8181`, load `http://127.0.0.1:8181/static` or `/dynamic` in a normal tab. Both contain a fixed footer and draft input; dynamic DOM retention is bounded to 24 nodes. Fixture timers are workload generation only, never browser toolbar logic.
