# 第三者コンポーネント

Velocity独自コードはMIT。Mozilla GeckoView Stable 157.0.20261005135250はMPL-2.0および内包する第三者ライセンスを含みます。Geckoエンジンのソースを変更していません。

- MPL全文: [licenses/MPL-2.0.txt](licenses/MPL-2.0.txt)
- 同版Java APIソース: https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/157.0.20261005135250/geckoview-157.0.20261005135250-sources.jar
- Geckoソース・リリース: https://firefox-source-docs.mozilla.org/
- Geckoライセンス一覧: https://www.mozilla.org/en-US/about/legal/
- KotlinはApache-2.0、AndroidXはApache-2.0、JUnit4はEPL-1.0。詳細は各採用artifactのPOM/配布ライセンスに従います。
- Geckoの推移依存（Google Play Services FIDO、AndroidX Media3、SnakeYAML等）も同梱されます。Android buildのdependenciesレポートで最終採用版と配布条件を確認してください。

このファイルはGecko内包の全ライセンス一覧の代替ではありません。配布前にGeckoパッケージ内のlicense資料・NOTICE・ソース提供義務を確認し、配布物へ必要な表示を同梱してください。リリースゲートに含めています。

Velocity Block Lite独自コード・同梱最小規則はMIT。Peter Loweのドメインリストはユーザーの明示操作時だけHTTPS取得し、APK/ソースZIPへ同梱しません。提供者は統合・Web掲載を公開許可しています。SPDXライセンスを推定せず、公開許諾とcredit（Peter Lowe / pgl@yoyo.org）を記録しています。AdGuard本体は公式AMOから任意導入し、再配布・APK同梱しません。source LICENSE GPL-3.0とAMO metadata LGPL-3.0-onlyの不一致を記録しています。[詳細](docs/adblock-decisions.md)。
