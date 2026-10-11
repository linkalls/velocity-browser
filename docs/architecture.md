# 構成

`MainActivity/BrowserChromeView` → `AdaptiveTabCoordinator` → `AdaptiveTabScheduler` → Command → `GeckoSessionAdapter`。

SchedulerはPure Kotlin。ClockはelapsedRealtime、イベントを単一メインスレッドのキューで直列実行。再入コールバックもキューへ入れる。タブ状態（COLD/RESTORING/ACTIVE/WARM/SAVING/FAILED）とProtectionの集合を直交させる。

選択前に空き枠を確保する。候補は選択中でなく、未保護で、最新revisionが認証済みのLRU。候補なしでは元のACTIVEを維持し通知。close前に状態をCOLDへ遷移し、identityの一致したadapterだけを閉じる。API追加窓はscheduler経由の独立URLタブへ誘導、Geckoへ新sessionを直接返さない。

Runtimeはプロセス共有の遅延生成。Activity/ViewはCoordinatorにbind/unbindし、sessionは回転時に存続。viewはGeckoView標準SurfaceView、1個のみ。表示前にreleaseSession。非表示/アプリ背景でsetFocused(false)、setActive(false)。背景タイマーなし。

Gecko状態通知は要求IDとepochなし。Webのsnapshotは厳密な最新性を判断できないため、実adapterは保持・復元しない。未操作blankのメモリ内snapshotのみ許可。Schedulerの汎用snapshot経路は、対応するepochを証明できるengine/test double向け。URL_RELOADが基本復元。完全フォーム/JSヒープ復元の主張はしない。

通常メタデータはsingle-thread I/Oでcommit。読み込みを初回フレームの後へ遅延し、先に来た外部Intentタブも保持。privateの履歴・snapshot・URLは保存なし。書き込み完了前の電源断/即時強制終了では最新メタデータが失われる可能性がある。
