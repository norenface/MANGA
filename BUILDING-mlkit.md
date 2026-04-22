# ML Kit版 ビルド手順

## 前提条件
- Android Studio Hedgehog (2023.1.1) 以降
- JDK 17 以上
- Android SDK (compileSdk 34)
- インターネット接続 (Google Maven 必須)

## ビルド手順

```bash
# 1. リポジトリをクローン
git clone <repo-url>
cd MANGA

# 2. Android Studio で開く、または CLI でビルド
./gradlew assembleDebug

# APK の出力先
# app/build/outputs/apk/debug/app-debug.apk
```

## 初回起動時の自動処理
ML Kit は初回起動時に以下をダウンロードします：
- Korean OCR モデル: 約 7 MB
- KO→JA 翻訳モデル: 約 30 MB (各言語ペア)
Wi-Fi 環境での初回起動を推奨。

## 機能
| 機能 | 実装 |
|---|---|
| OCR | ML Kit Text Recognition (Korean) |
| 翻訳 | ML Kit Translation (on-device, KO→JA) |
| 速度 | 1〜2 秒/ページ |
| オフライン | モデルDL後は完全オフライン動作 |

## 依存ライブラリ (Google Maven 必須)
```
com.google.mlkit:text-recognition-korean:16.0.0
com.google.mlkit:translate:17.0.2
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0
androidx.recyclerview:recyclerview:1.3.2
androidx.constraintlayout:constraintlayout:2.1.4
com.android.tools.build:gradle:8.2.0  (AGP)
```
