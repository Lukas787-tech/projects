# ExpenseSplit

An offline-first Android app for tracking expenses, scanning receipts, splitting bills with other
people, and understanding where the money actually goes.

Everything personal stays on the device: receipts are read with on-device OCR, spending analysis and
the "ways to save" advice are computed locally, and the only network call the app makes by default
is for currency exchange rates.

---

## Build status — please read first

**The APK could not be produced in the environment this project was generated in.**

This session's network egress policy blocks `dl.google.com` (HTTP 403 on CONNECT).
`maven.google.com` redirects there, so both of the following are unreachable:

- the Android SDK and command-line tools,
- Google's Maven repository, which hosts the Android Gradle Plugin, AndroidX, Jetpack Compose,
  Room, Hilt's Android artifacts, CameraX and ML Kit.

Without those, no Android module can be configured, let alone compiled. Maven Central *is*
reachable, which is why the pure-Kotlin parts could still be verified — see
[What has been verified](#what-has-been-verified).

On any machine with normal access to `dl.google.com`, `./gradlew assembleDebug` is expected to work
from a clean checkout with no further setup.

**To get an APK without a local Android SDK**, use the bundled CI workflow
(`.github/workflows/expense-split-android.yml`). GitHub Actions runners can reach Google's Maven
repository, so on every push to `main` or a `claude/**` branch — or on demand via *Actions → 
ExpenseSplit Android → Run workflow* — it runs the unit tests, runs lint, assembles the debug APK
and uploads it as the `expensesplit-debug-apk` artifact. Test and lint reports are uploaded even
when a step fails, which is where to look for the first-build fixes described below.

---

## What has been verified

The domain layer contains no Android dependencies, so it was compiled and its tests executed
against Maven Central artifacts on JDK 21:

```
102 tests, 0 failures
```

Covering `Money`, `DateRange`, `SplitCalculator`, `SettlementOptimizer`, `ReceiptParser`,
`ItemNameNormalizer`, `AutoCategorizer`, `SpendingAnalyzer`, `TrendForecaster`, `BudgetEvaluator`,
`PriceIntelligence` and recurrence scheduling.

Two of those tests caught a real bug during development: `MILK 1L` and `milk 1 l` normalised to
different keys, which would have made price history silently miss matches across stores. Fixed in
`ItemNameNormalizer`.

Additionally checked by static analysis over the full source tree (121 Kotlin files). These target
the mistakes that stop a build outright — Room and Hilt both fail at annotation-processing time, so
a wrong column name or a missing binding is a hard failure rather than a warning:

- **Room** — every table and column referenced by a `@Query` exists on the entity it belongs to,
  and every projection POJO's fields are satisfied by an aliased output column (the classic
  "cannot figure out how to read this field from a cursor" error),
- **Hilt** — all 42 injection sites resolve against the 15 `@Provides` bindings and 37
  `@Inject` constructors; no missing binding,
- **Resources** — every `R.string` / `R.plurals` / `R.drawable` / `R.color` / `R.xml` / `R.style` /
  `R.mipmap` reference in Kotlin **and** XML resolves to a declared resource,
- **Localisation** — all seven locales define the same 370 keys with identical positional format
  specifiers (`%1$s`, `%2$d`, …); a mismatch there is a guaranteed runtime crash,
- **Imports** — every internal `com.expensesplit.app.*` import resolves to a declared symbol, and
  all 58 Material icon references have a matching import.

**Not verified:** the Android-specific code — Compose UI, Room's generated DAOs, Hilt's generated
components, WorkManager, CameraX and ML Kit integration — has never been through a compiler.
Expect to fix some import- and API-level mistakes on the first real build. `SearchQueryBuilderTest`
is written but was not run, because it needs `androidx.sqlite`.

---

## Requirements

| | |
|---|---|
| JDK | 17 or newer |
| Android SDK | compileSdk 34, minSdk 28, targetSdk 34 |
| Gradle | 8.9 (via the bundled wrapper) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.21 |

## Building

```bash
git clone <this-repo>
cd expense-split-android

# Point at your SDK (or open the project in Android Studio, which writes this for you)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # build and install onto a connected device
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android lint
```

### Release builds

Release builds are minified and shrunk. To sign one, create `keystore.properties` in the project
root — it is gitignored, and the build silently falls back to an unsigned release if absent:

```properties
storeFile=../release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias expensesplit
./gradlew assembleRelease
```

---

## Architecture

MVVM over a single-module clean-ish layering. Unidirectional data flow: repositories expose
`Flow`, ViewModels fold those into an immutable `UiState`, Compose renders it and sends events back.

```
ui/          Compose screens, ViewModels, charts, design system
  ├ theme/         Material 3 palette, typography, semantic finance colours
  ├ components/    Reusable cards, form controls, formatters
  ├ charts/        Donut, bar and line charts drawn on Canvas
  ├ navigation/    Routes, bottom bar, NavHost
  └ screens/       One package per feature, each ViewModel + Screen

domain/      Pure Kotlin. No Android imports, fully unit-testable.
  ├ model/         Entities the app reasons about
  ├ split/         Split maths and debt simplification
  ├ analytics/     Reports, trends, budgets, the insight engine
  ├ ocr/           Receipt text parsing, item normalisation, auto-categorisation
  └ pricing/       Price history, duplicates, cheaper-elsewhere detection

data/        Persistence, network, export
  ├ local/         Room entities, DAOs, converters, seed data
  ├ remote/        Retrofit services and DTOs
  ├ repository/    Repositories and entity ↔ domain mappers
  ├ preferences/   DataStore-backed settings
  └ export/        CSV, PDF, encrypted JSON backup, sharing

notifications/  WorkManager jobs and the notification channels they post to
di/             Hilt modules
core/           Money arithmetic, date ranges, locale switching
```

### Design decisions worth knowing

**Money is never a `Double`.** Amounts are `Long` counts of minor units plus an ISO-4217 code.
Currency exponents come from `java.util.Currency`, so JPY (0 decimals) and KWD (3) behave correctly.
Splitting routes through `Money.splitEvenly` / `allocateByWeights`, which use the largest-remainder
method — a bill split three ways always adds back up to the exact total, to the cent.

**Foreign-currency expenses store their own rate.** Each expense keeps `amountMinor` in the currency
it was paid in *and* `baseAmountMinor` converted at the rate captured when it was saved. Historical
reports therefore don't silently change when today's exchange rate moves.

**Dates are epoch days, not timestamps.** A "date of expense" must not shift when the user flies
across a time zone.

**Debt simplification.** `SettlementOptimizer` nets everyone's position and greedily matches the
largest creditor against the largest debtor, producing at most *n−1* transfers instead of a web of
mutual IOUs.

**OCR is a suggestion, not a source of truth.** `ReceiptParser` reports a confidence score, leaves
anything it is unsure about `null`, and nothing reaches the database until the user confirms it on
the review screen. It handles US and European number formats, several date layouts, and scans
bottom-up for the total so a subtotal is never mistaken for it.

**Insights are rules, not a black box.** Every card on the Analytics screen traces back to one
arithmetic condition over the user's own data, and carries an estimated monthly saving so the list
can be ranked by what actually matters. No spending data leaves the device.

**Backups are password-encrypted, not keystore-encrypted.** A keystore-wrapped key never leaves the
device — which would make a backup impossible to restore on a new phone, exactly when a backup
matters most. `CryptoManager` uses PBKDF2-HMAC-SHA256 (210k iterations) with AES-256-GCM instead.

**Search is parameterised.** `SearchQueryBuilder` binds every value and escapes `LIKE` wildcards;
only the `ORDER BY` clause is chosen from a fixed set of literals.

---

## Features

**Expenses** — manual entry, receipt scanning, edit and delete, multi-currency with live conversion,
recurring rules (daily/weekly/monthly/yearly, with catch-up for entries missed while the app was
closed), photo attachments, notes.

**Bill splitting** — groups and members, four split methods (equal, percentage, custom amounts,
shares), live per-person amounts as you type, net balances, an optimal settlement plan, recorded
settlements, CSV and plain-text export.

**Analysis** — automatic categorisation from merchant and item names, weekly/monthly/yearly reports,
donut and bar breakdowns, least-squares trend projection with an honest confidence score, budgets
with near-limit and overspend alerts, and ranked money-saving advice.

**Receipts** — stored images, item-level detail, full-text item search ("when did I last buy
coffee?"), repeat-purchase detection, per-item price history, and cheaper-elsewhere comparisons.

**Reports** — month-by-month recap, PDF export rendered with the platform's own `PdfDocument`
(no third-party PDF library), CSV export, year-to-date figures, budget performance, settlement
summary.

**Search** — keyword, date range, category, amount range, payment method, group, settled state,
receipt presence; plus quick "this week / month / year" chips.

**Elsewhere** — seven languages, dark mode, Material You dynamic colour, notifications for budgets,
bills, price drops and the monthly recap, and encrypted import/export.

---

## Screens

| Screen | What it does |
|---|---|
| Dashboard | Month total with trend, budgets, unsettled balances, top insights, recent expenses |
| Add Expense | Amount-first form, auto-categorisation, currency conversion preview, recurrence |
| Receipt Scanner | Camera with framing guide, on-device OCR, editable review before saving |
| Expense List | Grouped by date, quick range filters, category chips, sort, CSV export |
| Bills | Group list showing where you stand with each |
| Group Detail | Bills / Balances / Settle-up tabs, plus the split editor |
| Analytics | Period switcher, donut and bar charts, trend projection, budgets, all insights |
| Search | Two tabs — expenses and receipt line items — with the full filter panel |
| Monthly Recap | Browsable month by month, exportable as PDF or CSV |
| Settings | Language, currency, theme, notifications, backup, restore, privacy |
| Receipt Detail | Image, line items, savings found, links to price history |
| Price History | Chart of what an item has cost, cheapest and average, every observation |

### Usage guide

1. **First launch** seeds fourteen categories and picks a base currency from the device locale.
   Set your name in Settings if you plan to split bills — it is how you appear in groups.
2. **Add an expense** with the ＋ tab or the floating button. Enter an amount; everything else has a
   sensible default.
3. **Scan a receipt** from the Add screen or the dashboard's empty state. Frame the whole receipt,
   then check the total and date on the review sheet before saving — low-confidence scans say so.
4. **Split a bill**: Bills → ＋ → name the group and add people. Inside it, ＋ adds a bill; pick a
   split method and the per-person amounts update as you type. The *Settle up* tab shows the fewest
   transfers that clear everyone.
5. **Set a budget** from Analytics → Set budget. Alerts fire at 80% and again on overspend.
6. **Review the month** from Analytics → the summary icon, and export it as a PDF.
7. **Back up** from Settings → Create a backup. Turn on *Encrypt backups* first if the file will
   leave the device; you will need the passphrase to restore it.

Screenshots are not included: producing them requires building and running the app, which this
environment cannot do. `./gradlew installDebug` on a device or emulator is the fastest way to get
them.

---

## Optional integrations

### Local store price feed

Price comparison works out of the box using only the user's own receipt history. To also pull nearby
prices, point the build at an endpoint that returns
`data/remote/dto/StorePriceResponseDto`'s shape:

```properties
# gradle.properties, or -PstorePriceApiUrl=…
storePriceApiUrl=https://your-feed.example.com/api/
```

```
GET {base}/prices?q=milk&currency=USD&lat=…&lon=…&radius_km=15

{ "query": "milk", "currency": "USD",
  "offers": [ { "item_name": "Whole Milk 1L", "store_name": "Aldi",
                "price": 1.29, "observed_on": "2026-04-18", "distance_km": 1.2 } ] }
```

Left unset, `StorePriceApi` is not constructed and `PriceRepository` falls back to receipt history.
There is no universal free API for grocery prices, which is why this is a pluggable endpoint rather
than a hardcoded provider.

### Firebase cloud backup

Deliberately **not** wired up: adding the `google-services` plugin without a `google-services.json`
breaks the build for everyone who clones the repo, and the app is fully functional offline. The
Settings screen has the toggle and `BackupManager` already produces a single portable file. To
connect it:

1. Add the Firebase BOM and `firebase-storage-ktx` to `app/build.gradle.kts`.
2. Add the `com.google.gms.google-services` plugin and your `google-services.json`.
3. Upload the output of `BackupManager.exportToFile(...)` and download it back through
   `importFromUri(...)`. Encrypt it first — see `CryptoManager`.

---

## Testing

```bash
./gradlew testDebugUnitTest       # JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests (needs a device)
```

The domain tests run without the Android SDK. To reproduce the standalone verification described
above, copy `core/` (minus `AppLocales.kt`) and `domain/` into a plain Kotlin JVM project with
JUnit 4 and Truth, and supply a stub `R` object for the string constants the enums and insight
engine reference.

## Licence

Not specified. Add one before distributing.
