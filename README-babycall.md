# BabyCall (赤ちゃん専用ビデオ通話アプリ)

赤ちゃんが**勝手に通話を切ったり**、**他の人に電話をかけたりできない**ことを目的にした、
Android 向けのビデオ通話アプリです。マンガ翻訳アプリとは独立した Gradle モジュール
(`babycall`)として同じリポジトリに同居しています。

## 安全設計の要点

| 心配ごと | 対策 |
|---|---|
| 赤ちゃんが他の人に電話してしまう | 赤ちゃん端末にはダイヤルパッドも連絡先も一切ない。ペアリング時に登録した保護者端末からの着信にしか反応できない構造になっている。 |
| 赤ちゃんが勝手に通話を切ってしまう | 赤ちゃん側の通話画面に「終了」ボタンは表示されない。終了できるのは、画面隅の見えないポイントを3秒間長押しし、さらに保護者が設定したPINを入力した場合のみ。 |
| 赤ちゃんがアプリから抜けて他の操作をしてしまう | 通話中・待機中とも画面ピン留め(Lock Task / 画面固定)を有効にし、ホーム・戻る・タスク切替を無効化する。 |
| 保護者が呼びかけたときに赤ちゃん側の操作が必要 | 自動応答(デフォルトON)。着信すると赤ちゃん端末は何も操作せずにつながる。 |

これらはあくまで「乳幼児が偶発的に操作してしまう」ことを防ぐための設計であり、
悪意のある操作を完全に防ぐセキュリティ機構ではありません。

## 前提条件

- Android Studio / JDK 17 / Android SDK (compileSdk 34)
- Firebase プロジェクト(無料の Spark プランで動作します。サーバー運用は不要です)
- 保護者用スマホ1台 + 赤ちゃん用端末(古いスマホやタブレットで可)1台以上

## セットアップ手順

### 1. Firebase プロジェクトを作成する

1. https://console.firebase.google.com/ で新規プロジェクトを作成
2. 左メニュー「構築」→「Realtime Database」→ データベースを作成
   - ロケーションは任意、**テストモード**で開始してOK(後述のルールに置き換えます)
3. 左メニュー「構築」→「Authentication」→ 「Sign-in method」で **匿名(Anonymous)** を有効化
   - このアプリは会員登録なしで動くよう、匿名認証だけを使っています
4. 「プロジェクトの設定」(⚙️アイコン)→「全般」→ 「アプリを追加」→ Android を選択
   - パッケージ名に `com.babycall` を入力(重要:これと一致していないと動きません)
   - `google-services.json` のダウンロードは不要です(このアプリはダウンロードせずに
     画面に表示される値を直接使います)

### 2. Realtime Database のルールを設定する

Realtime Database の「ルール」タブで以下に置き換えて公開してください。

```json
{
  "rules": {
    "families": {
      ".read": "auth != null",
      ".write": "auth != null"
    },
    "pairing_codes": {
      ".read": "auth != null",
      ".write": "auth != null"
    }
  }
}
```

> **制限事項**: このアプリは家族ごとのユーザー登録を行わないため、上記ルールは
> 「匿名サインイン済みの誰か」であれば理論上どの家族のデータにも読み書きできます。
> familyId はランダムな推測困難な文字列、ペアリングコードは6桁・10分限定・使い切り
> ですが、厳密なアクセス制御ではありません。個人利用の範囲では十分ですが、より
> 強固にしたい場合は familyId ごとに Firebase Auth のカスタムクレームを持たせる、
> もしくはメール/パスワード認証を追加してルールを `auth.uid == families/$familyId/ownerUid`
> のように限定する変更を検討してください。

### 3. アプリに接続情報を設定する

`babycall/src/main/res/values/strings_firebase.xml` を開き、Firebase コンソールの
「プロジェクトの設定 → 全般 → マイアプリ」に表示される値で書き換えます。

```xml
<string name="firebase_api_key" translatable="false">実際のAPIキー</string>
<string name="firebase_application_id" translatable="false">1:xxxxxxxx:android:xxxxxxxx</string>
<string name="firebase_project_id" translatable="false">your-project-id</string>
<string name="firebase_database_url" translatable="false">https://your-project-default-rtdb.firebaseio.com</string>
```

`firebase_database_url` は Realtime Database のトップ画面に表示されている URL です
(リージョンによって `https://xxx-default-rtdb.asia-southeast1.firebasedatabase.app`
のような形式になることがあります)。

### 4. ビルドする

```bash
./gradlew :babycall:assembleDebug
```

`babycall/build/outputs/apk/debug/babycall-debug.apk` が生成されます。

## 使い方

1. **保護者の端末**にインストールし、「保護者として設定する」→ 名前とPINを入力
   → 作成すると6桁のペアリング番号が表示されます。
2. **赤ちゃんの端末**にインストールし、「赤ちゃんの端末として設定する」→
   1で表示された番号を入力して接続します。
3. 赤ちゃん端末側で **画面固定(Screen Pinning)** を有効にしておきます
   (下記「画面固定について」参照)。
4. 保護者アプリのホーム画面にある丸いボタンをタップすると、赤ちゃん端末が
   自動応答してビデオ通話が始まります。

## 画面固定(Screen Pinning)について

このアプリは Device Owner 権限なしでも動くよう、Android 標準の
`Activity.startLockTask()`(画面固定)機能でホーム/戻る操作をブロックしています。
機種によっては、**設定 → セキュリティ → 画面固定** を一度手動でONにする必要が
あります。有効化されていない場合、画面固定は静かに失敗し、通話自体は問題なく
できますが、赤ちゃんがホームボタン等でアプリを抜けられてしまう可能性があります。

より確実に「他のことを一切できない専用端末」にしたい場合は、その端末をこのアプリの
**Device Owner** として登録する方法があります(工場出荷状態の端末が必要、または
`adb shell dpm set-device-owner com.babycall/.call.BootReceiver` 相当のコマンドで
設定)。Device Owner化すると画面固定の確認ダイアログなしに常時キオスク化できますが、
その端末を他の用途に一切使えなくなる・設定を戻すには端末初期化が必要になる、という
制約もあるため、今回のコードには含めていません。必要であれば別途対応します。

## 既知の制限

- **TURNサーバー未設定**: シグナリングはFirebaseで行いますが、映像・音声そのものは
  P2P (WebRTC) です。STUNサーバーのみ設定済みのため、モバイル回線同士など
  NAT越えが難しい組み合わせでは接続できないことがあります。安定させたい場合は
  `WebRTCClient` の `turnServers` に無料/低価格のTURNサービス(例: metered.ca、
  Xirsys、Twilio NTS)の認証情報を渡してください。
- **バッテリー/通信量**: 赤ちゃん端末は常時サービスを起動して着信を待つため、
  充電しながらの設置を推奨します。
- **完全な誤操作防止ではありません**: 画面固定やPINは乳幼児の偶発的な操作を
  防ぐためのものです。
