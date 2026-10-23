# 水平維持カメラ v1.7.0 — 削りすぎ監査 / コード健康診断

## 結論

v1.6.0で行った無駄処理・常時監視・重複処理の削減を、今回は逆方向から確認しました。

**中心機能を成立させる処理の削除は確認されませんでした。** 45°/360°水平維持、30/60fps、縦横固定、OpenGL補正、LUT、録画、音声、外部マイク、AF/AE/WB、マニュアル露出、MediaStore保存の経路は残っています。

一方で、最適化を強くしたことで「まれな条件で必要な状態確認や復旧性まで弱くなった」箇所がありました。v1.7.0では、その安全処理だけを戻し、常時監視や不要な重複処理は復活させていません。

Android SDK / 実機がこの環境にないため、APKの完全コンパイルと実機Camera2録画テストは未実施です。Java構造、XML、LUT、重複シグネチャ等の静的検査は実施しています。

## v1.6.0で削除した処理を再確認

v1.5.0→v1.6.0の差分を確認しました。削除された主要項目は次の通りです。

- 未使用だった `CameraController.isPortrait()` getter
- 未使用だった `CameraController.isHardwareStabilization()` getter
- MainActivity側で処理内容が空だった `onAutoLockState(...)`
- 未使用だった `VideoEncoder.getInputSurface()`
- センサーイベントごとに行っていたクロップ上限の二分探索
  - 削除ではなく、設定変更時のキャッシュ計算へ置換済み
- 30fps時の高頻度FPS監視
  - 60fpsの負荷監視は維持
- 水平補正OFF＋水準器OFF時のセンサー常時監視
  - 必要時には再登録されるため機能欠落なし
- Camera2 CaptureResultの永久監視
  - 設定変更後だけの有界監視へ置換

これらの削除によって撮影の中心経路が途切れている箇所はありません。

## 今回見つけて修正した「削減しすぎ / 最適化の副作用」

### 1. Camera2スレッド終了待ち削減後の古いキュー干渉

v1.6ではUIスレッドを止めないためCamera HandlerThreadのjoin待ちを削除しました。しかし、pause→resumeが非常に速い場合、旧HandlerThreadに残った設定更新タスクが新しいCamera2状態へ触れる余地がありました。

修正:
- AF操作、RepeatingRequest更新、CaptureSession再構成は「投稿時のHandlerと現在Handlerが同一か」を確認。
- 古いHandlerの処理は新セッションへ触れず終了。
- UIスレッドをブロックするjoinは復活させていません。

### 2. 古いRepeatingRequest更新を捨てた際の予約フラグ残留

古いHandlerの更新を無視する安全策だけを入れると、`repeatUpdatePending` がtrueのまま残り、以後の設定更新をすべて拒否する可能性がありました。

修正:
- stale taskを破棄する場合も必ずpendingフラグを解除。

### 3. CaptureResult監視30フレーム固定は短すぎる端末がある

AE/WB/ハードウェア手ブレ補正の反映が遅い端末では、30フレームで監視を切ると「実際には後から有効になったのに未確認」のままになる可能性がありました。

修正:
- 最大90フレームへ拡張。
- 要求した状態が揃えば即座に監視終了。
- 常時CaptureResult監視には戻していません。

### 4. AF LOCKタイムアウト後に内部ONが残る

永久監視防止のため追加したAFタイムアウトで、タイムアウト後も`afLock=true`が残るケースがありました。

修正:
- タイムアウト時はAF LOCKをOFFへ戻す。
- Camera2へAF CANCEL / 通常AF requestを戻す。
- AF START例外時も内部状態をOFFへ戻す。

### 5. MediaCodec「非対応」の永久キャッシュ

v1.6では能力走査を減らすため対応/非対応をキャッシュしていました。Codec一覧取得が一時的に失敗すると、そのモードをアプリ終了まで非対応として固定する恐れがありました。

修正:
- **対応結果だけキャッシュ**。
- 非対応結果は永久キャッシュしない。
- Codec一覧取得自体が例外になった場合、空一覧を永久保存しない。

### 6. CaptureSession再構成中に録画開始できる競合

解像度などSurface構成を変えた直後、旧Sessionが残る短い時間に録画ボタンを押すと、新しいエンコーダサイズと旧Camera2 Sessionが食い違う可能性がありました。

修正:
- `reconfiguring` 状態を追加。
- Session再構成中は `isPreviewActive()` をfalseにする。
- 録画開始はSession再構成完了後のみ許可。

### 7. 縦横変更の検証順序でGPUサイズが旧向きになる可能性

向き変更をCameraController側で先に検証するようにした結果、成功時の能力通知が同期的に返る端末/経路では、MainActivity側がまだ旧向きのまま出力サイズを計算する可能性がありました。

修正:
- 能力通知から見えるMain側向きを先に仮更新。
- CameraControllerが非対応として拒否した場合だけ旧向きへロールバック。
- 内部状態とUI/GPU出力の食い違いを防止。

### 8. 水平基準を録画開始時に固定する設定と初回センサー値

録画開始が最初のセンサーイベントより早い場合、初期値0°を水平基準として固定する可能性がありました。

修正:
- 実センサーサンプル受信済みか記録。
- 未受信なら最初の実サンプルを録画基準にする。

### 9. 音声エンコーダ強制停止後の正常保存判定

AudioRecord/MediaCodecが停止しない端末で強制終了しても、停止スレッドが完了したか明確に確認できない経路がありました。

修正:
- AudioEncoderに停止完了状態を保持。
- ワーカーが終了できなかった場合は録画を正常保存扱いにしない。

### 10. onPause時のGL停止待ち余裕

映像/音声エンコーダ停止の最大待機とGL pause待機が同じ6秒だったため、境界条件でGL停止がEOS完了と競合する余地がありました。

修正:
- GL pause側だけ8秒へ延長。
- 通常停止の処理時間を無制限にはしていません。

## 機能経路の再トレース

### 起動 / 権限

`onResume` → GLSurfaceView再開 → SurfaceTexture生成 → `startCameraIfReady()` → CAMERA権限確認 → Camera2 open。

初回ガイドから CAMERA / RECORD_AUDIO / 必要端末のBLUETOOTH_CONNECT要求経路は維持されています。

### 水平維持

HorizonSensor → MainActivity `onRoll()` → 基準角/スムージング/45°制限 → CameraGLRendererへ補正角 → カメラ新規フレーム時にGPU描画。

- 45°: 維持
- 360°: 維持
- OFF: 維持
- 水準器のみ: センサーは動作するがGPU補正計算は不要分を通さない

### LUT

Camera2 SurfaceTexture → OpenGL → 3D LUT → PreviewとEncoder Surfaceの両方へ同じ処理。

- 内蔵5 LUT: 維持
- 外部3D `.cube`: 維持
- DOMAIN_MIN/MAX: 維持
- 外部LUT解析はUIスレッド外

### 録画

Camera2 → SurfaceTexture → OpenGL水平補正/LUT → VideoEncoder Surface → MediaCodec → MuxerController → MediaStore。

録画開始時にはCamera2 Sessionが安定済みか再確認します。

### 音声

AudioRecord → PCM処理（AGC/デジタル補正/低域カット）→ AAC MediaCodec → MuxerController。

外部マイク開始失敗時は自動入力へ1回だけフォールバックし、それも失敗した場合は映像のみへ切替える経路を維持しています。

### 録画停止 / 保存

音声停止と映像EOSを別経路で完了 → 両方を有界待機 → Muxer stop → MediaStore IS_PENDING解除。失敗時は未完成ファイルを削除。

### バックグラウンド / 復帰

onPause → センサー/温度監視停止 → 録画中なら停止完了を待つ → Camera2 close/旧Handler無効化 → GL pause。

onResume → GL再開 → SurfaceTexture再利用/再生成 → Camera2再open。

## 常時処理を戻していないもの

今回、安全性を戻すために「全部また常時監視」に戻したわけではありません。

- 水平センサー: 水平維持または水準器が必要な時だけ
- 温度: 録画中だけ
- FPS実測警告: 60fps録画中だけ
- Camera2 CaptureResult: 設定変更直後またはAF確定待ちだけ
- 音量メーター: 音声録画中だけ
- 録画時間ticker: 録画中だけ、1本
- LUT解析: ファイル選択時だけ

## 静的検査結果

- XML: 4ファイルすべてパース成功
- 内蔵LUT: 5ファイルすべて `CubeLut.parse()` 成功
  - size 16
  - 12288 float（16^3 × RGB）
- 重複メソッドシグネチャ: 検出なし
- 明らかな未参照privateメソッド: 検出なし
- TODO / FIXME / HACK / UnsupportedOperationException: ソース内0件
- Android SDKなしのraw `javac` 検査で、Androidクラス未解決以外の代表的な構文/重複ローカル変数エラーは検出なし

## 意図的に復活させていない機能

次は「削りすぎ」ではなく、以前の監査で品質保証が難しいため意図的に外した機能です。

- 120fps
- アクションモード
- 手ブレ補正の弱/中/強（OFF/ONへ整理）
- Java AudioRecord→AudioTrack方式のイヤホン低遅延モニター

これらを戻さなくても45°/360°水平維持カメラの中心機能は成立します。

## 残る実機依存事項

- 4K60 + 360° + LUTの処理性能
- メーカー独自Camera2実装差
- EIS/OISの実動作
- USB/Bluetoothマイクの最終ルーティング
- MediaCodecのベンダー固有挙動

これらはAndroid SDKでの実ビルドおよび複数実機での録画試験が必要です。
