# yt-dlpk (Kotlin + Compose Desktop)

Kotlin + Compose Multiplatform で実装した `yt-dlp` デスクトップGUIです（Windows/macOS/Linux対応）。

言語別README:
- 英語: `README.md`
- 日本語: `README.ja.md`
- 韓国語: `README.ko.md`

## 現在の実装範囲（MVP+）

- URL入力 + Analyze
  - `yt-dlp --dump-single-json` でメタ情報取得
  - `yt-dlp -F` でフォーマット取得
- フォーマット選択UI
  - タブ: `動画+音声 / 動画のみ / 音声のみ`
  - テーブル風一覧: `Format | Resolution | Bitrate`
  - 解像度・ビットレート高い順で表示
  - 選択行はポイントカラーで強調
  - `動画+音声` のデフォルトは最高解像度優先
- ダウンロードオプション
  - 字幕: `--write-subs --write-auto-subs --sub-lang --convert-subs srt`
  - 音声抽出: `-x --audio-format`（ドロップダウン）
  - 出力先選択
  - ファイル名テンプレート
  - プレイリスト全体/単体切替
  - マージ出力形式（ドロップダウン）
- 進捗・操作
  - `--newline` ベースの進捗取得
  - キャンセル対応
  - Analyze中はProgress欄に状態文言表示
  - Analyze完了前はDownload不可
- ツール管理
  - アプリ内配置/システムPATHの両方を検知
  - 不足時は `yt-dlp` / `ffmpeg` を自動DL
  - `tool-sources.json` でURL差し替え可
  - Globalタブでバージョン表示・最新確認・更新
  - system管理ツールは案内ダイアログ表示
- UI/UX
  - プレイリスト時は先頭動画サムネをフォールバック表示
  - 初期/最小ウィンドウサイズ設定
  - テーマ: `SYSTEM / DARK / LIGHT`
  - 言語: `English / 日本語 / 한국어`（即時反映）

## アーキテクチャ

- UI層とドメイン/サービス層を分離
- `StateFlow` による状態管理
- Coroutineベースの非同期プロセス実行
- 主要サービス:
  - `FormatService`
  - `YtDlpCommandBuilder`
  - `YtDlpService`
  - `ToolManager`

## 実行

```bash
./gradlew run
```

## パッケージ

```bash
./gradlew packageDistributionForCurrentOS
```

ターゲット: `Dmg`, `Msi`, `Deb`, `Rpm`

## 手動テスト

1. `./gradlew run` で起動
2. URL入力して `Analyze`
3. メタ情報/サムネ/フォーマット一覧を確認
4. `動画+音声` で最高解像度がデフォルト選択されるか確認
5. タブ切替とフォーマット選択
6. ダウンロード開始し進捗確認
7. キャンセル動作確認
8. 字幕/音声抽出/出力形式オプション確認
9. Globalタブで最新確認・更新ボタン確認
10. 言語/テーマ切替の即時反映確認

## 既知の制限

- `yt-dlp -F` はサイトごとに出力差異があり、パーサはヒューリスティック
- ffmpeg配布アセット名が将来変更される可能性
- サムネURLが到達不能な場合は表示できない

## 主なファイル

- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `src/desktopMain/kotlin/com/ytdlpk/app/Main.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/ui/App.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/ui/AppViewModel.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/FormatService.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/YtDlpCommandBuilder.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/YtDlpService.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/ToolManager.kt`
- `src/desktopMain/resources/tool-sources.json`
