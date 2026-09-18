# MyFSL 架構

```
:app ──────────► :core:data ──────────► :core:domain
(畫面、導覽)      (資料庫、設定、備份)      (模型與計算規則，純 Kotlin)
```

箭頭是「可以使用」。反方向不行：規則不知道資料怎麼存，資料層不知道畫面長怎樣。

| 模組 | 類型 | 放什麼 | 不能放什麼 |
|---|---|---|---|
| `:core:domain` | 純 Kotlin（JVM） | `core.model`：帳戶、計畫、記帳、情境等資料模型<br>`core.domain`：所有計算規則（試算引擎、本週檢查、分期、循環利息、表單檢查、CSV 匯入）<br>`core.sample`：示意家庭資料 | Android、Room、DataStore、Compose。模組本身不是 Android 模組，引用了就編譯不過 |
| `:core:data` | Android library | `core.data`：`FinanceRepository`、`SettingsRepository`、完整備份、當機紀錄<br>`core.data.db`：Room entity、DAO、資料庫升級<br>`core.data.di`：Hilt 提供資料庫、DataStore、時間 | 畫面、ViewModel |
| `:app` | Android application | `ui.*`：各畫面的 Compose 與 ViewModel<br>`MainActivity`、`MyFSLApplication` | 計算規則（放 domain）、SQL（放 data） |

## 為什麼這樣切

- **規則可以直接在電腦上測**：`:core:domain` 不依賴 Android，199 項 JVM 測試裡 186 項在這裡，幾秒跑完。
- **換儲存方式不動規則**：資料庫結構改了，只改 `:core:data`。
- **不會意外外露實作**：例如 DataStore 的 `Preferences` 型別不會出現在畫面層；跨模組的 `internal` 也會在編譯時被擋下。

## 慣例

- 套件前綴 `tw.myfsl.app`，與 applicationId 相同。原始碼放在各模組的 `src/main/kotlin`。
- 新的計算規則：寫在 `:core:domain`，同時在 `src/test` 加測試，並在 `docs/SPEC.md` 加規則編號。
- 改資料表：提高 `AppDatabase` 版本、在 `Migrations.statements` 加 SQL；schema 檔在 `core/data/schemas`，要進版控。
- 版本號一律在 `gradle/libs.versions.toml`。`:core:data` 與 `:app` 裡的 `constraints` 讓各模組使用同一組 AndroidX 版本。

## 常用指令

```powershell
.\gradlew.bat :core:domain:test                 # 規則測試（最快）
.\gradlew.bat :core:data:testDebugUnitTest      # 資料層測試（備份格式、資料庫升級檢查）
.\gradlew.bat :app:assembleDebug                # 除錯版 APK
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1   # 正式版（見 RELEASE.md）
```
