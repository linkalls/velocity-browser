# Performance — NOT TESTED

Native engine/APKをビルドできていないため、全指標が未測定。
Java-lessは子プロセスの生成・ホスティング方式の変更であり、描画高速化を保証しない。

| 指標 | Java baseline | Native | 比較/信頼区間 |
|---|---|---|---|
| Cold / warm start、初回ページ表示 | NOT TESTED | NOT TESTED | 算出不可 |
| 全browser/renderer/GPU/network合計PSS、peak | NOT TESTED | NOT TESTED | 算出不可 |
| Java heap / native memory / process CPU | NOT TESTED | NOT TESTED | 算出不可 |
| 実際のCEF提示frame time P50/P95/P99、最大停止 | NOT TESTED | NOT TESTED | 算出不可 |
| reverse jump、crash、ANR | NOT TESTED | NOT TESTED | 算出不可 |

実測時はPixel 7 Proで同一Chromium revision・toolchain・GN条件・ページ・UI・
キャッシュ・ネットワーク・熱状態・background appsを揃える。60Hz/120Hz各5回以上、
ABBA等で交互順、cold/warmは別集計。動的広告などページ内容が変われば比較不可と記録。
`am start -W` のActivity起動と、ページ初回表示完了は別に記録する。
main processのみのPSSやAndroid Window FrameMetricsだけでCEFの改善を断定しない。
SurfaceViewを含むframe提示をPerfetto等で確認し、raw dataと集計法・ばらつきを残す。
CPU/PSS改善がなくnativeだけ不安定ならJava版を推奨する。
