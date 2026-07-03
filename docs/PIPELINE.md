# Play Store Release Pipeline

A fully free CI/CD pipeline using **GitHub Actions** (free for public repos) + **Firebase App Distribution** (free tier) + **Google Play Console** ($25 one-time fee).

---

## Pipeline Overview

```
Three workflows, triggered by branch pushes / PRs:

┌─────────────────────────────────────────────────────────────────┐
│  test.yml                                                       │
│  Trigger: PR to beta/master, push to develop                    │
│  ┌─────────────┐                                                │
│  │  Unit tests │  ./gradlew testDebugUnitTest                   │
│  │  Lint check │  ./gradlew lintDebug                           │
│  └─────────────┘                                                │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  build-beta.yml                                                 │
│  Trigger: push to beta, tags v0.*                               │
│  ┌─────────────┐   ┌──────────────────┐   ┌──────────────────┐  │
│  │  Assemble   │ → │ Firebase Dist.   │ → │ GitHub Release   │  │
│  │  debug APK  │   │ (internal-testers)│   │ (if tagged)      │  │
│  └─────────────┘   └──────────────────┘   └──────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  build-release.yml                                              │
│  Trigger: push to master, tags v[1-9].*                         │
│  ┌─────────────┐   ┌──────────────────┐   ┌──────────────────┐  │
│  │  Assemble   │ → │ Google Play      │ → │ GitHub Release   │  │
│  │  signed AAB │   │ (internal track)  │   │ (if tagged)      │  │
│  │  + APK      │   └──────────────────┘   └──────────────────┘  │
│  └─────────────┘                                                │
└─────────────────────────────────────────────────────────────────┘
```

---

## Prerequisites

### 1. Generate a Keystore (one-time)

```bash
keytool -genkey -v -keystore keystore/meme_chat_upload.keystore \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <storepass> -keypass <keypass>
```

- Both `keystore/meme_chat_upload.keystore` and `keystore/meme_chat_upload.jks` are used locally (the `.jks` is a local dev copy with alias `meme_chat_upload`).
- Store passwords in a password manager.

### 2. Register in Google Play Console

1. Pay the $25 one-time registration fee
2. Create a new app (package: `fun.walawe.memechat`)
3. Go to **Release → Setup → App integrity**
4. Upload the public key certificate extracted from your keystore:
   ```bash
   keytool -export -rfc -alias upload \
     -keystore keystore/meme_chat_upload.keystore \
     -file keystore/upload_cert.pem
   ```
5. In **API Access**, create a **Google Play Developer API** service account
6. Download the JSON service account key

### 3. Set Up Firebase Project

1. Already exists: `meme-chat-ai` (from `google-services.json`)
2. Enable **Firebase App Distribution** in the Firebase Console
3. Add testers via tester groups (emails)

### 4. GitHub Repository Secrets

Add these to **GitHub → Settings → Secrets and variables → Actions**:

| Secret | Used by | Value |
|---|---|---|
| `KEYSTORE_FILE` | release | Base64-encoded keystore (`base64 -w0 keystore/meme_chat_upload.keystore`) |
| `KEYSTORE_PASSWORD` | release | Keystore password |
| `KEY_ALIAS` | release | `upload` |
| `KEY_PASSWORD` | release | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | release | Full JSON from Google Play Developer API service account |
| `PROD_HUGGINGFACE_API_KEY` | release | HuggingFace API key |
| `PROD_MCP_KEENABLE_API_KEY` | release | Keenable API key |
| `BETA_HUGGINGFACE_API_KEY` | beta | HuggingFace API key (can differ from prod) |
| `BETA_MCP_KEENABLE_API_KEY` | beta | Keenable API key (can differ from prod) |
| `FIREBASE_APP_ID` | beta | From `google-services.json` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | beta | Firebase service account JSON |

---

## Project Setup (already in place)

### 1. Signing config in `app/build.gradle.kts`

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../keystore/meme_chat_upload.keystore")
        storePassword = Secrets.get(project, "KEYSTORE_PASSWORD")
        keyAlias = Secrets.get(project, "KEY_ALIAS")
        keyPassword = Secrets.get(project, "KEY_PASSWORD")
    }
}
```

Secrets are resolved via `buildSrc/src/main/kotlin/Secrets.kt` in this order:
1. Environment variable
2. `secrets.properties` or `keystore.properties`
3. Gradle project property (`-P` flag)

### 2. Versioning via `Secrets.kt`

- `versionCode` = `appVersionCode` project prop → `GITHUB_RUN_NUMBER` env → git commit count
- `versionName` = `appVersionName` project prop → `APP_VERSION_NAME` env → `0.1.<commit count>`

Workflows pass these explicitly:
```bash
./gradlew bundleRelease assembleRelease \
  -PappVersionCode=${{ github.run_number }} \
  -PappVersionName=$APP_VERSION_NAME
```

### 3. R8 / ProGuard keep rules

**`app/proguard-rules.pro`** — already configured with rules for:
- Hilt / Dagger
- JNI bridge classes (`fun.walawe.memelm.inference.*`)
- Kotlin serialization
- Firebase / Crashlytics NDK
- OkHttp / Retrofit
- Room

### 4. `.gitignore`

Already covers:
```
keystore/
*.keystore
*.jks
*.pem
secrets.properties
keystore.properties
local.properties
```

`google-services.json` is committed (required for CI builds — contains only public Firebase API keys).

---

## GitHub Actions Workflows

### `.github/workflows/test.yml` — Run on PR / push to develop

```yaml
name: Test

on:
  push:
    branches: [develop]
  pull_request:
    branches: [beta, master]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --scan

      - name: Run lint
        run: ./gradlew lintDebug
```

### `.github/workflows/build-beta.yml` — Beta deployment

```yaml
name: Build Beta

on:
  push:
    branches: [beta]
    tags: ["v0.*"]

jobs:
  beta:
    runs-on: ubuntu-latest
    permissions:
      contents: write

    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Set version
        run: |
          if [[ "${{ github.ref_type }}" == "tag" ]]; then
            VERSION="${{ github.ref_name }}"
            VERSION="${VERSION#v}"
            echo "APP_VERSION_NAME=$VERSION" >> $GITHUB_ENV
          else
            echo "APP_VERSION_NAME=0.0.${{ github.run_number }}" >> $GITHUB_ENV
          fi

      - name: Build debug APK
        run: |
          ./gradlew assembleDebug \
            -PappVersionCode=${{ github.run_number }} \
            -PappVersionName=$APP_VERSION_NAME
        env:
          HUGGINGFACE_API_KEY: ${{ secrets.BETA_HUGGINGFACE_API_KEY }}
          MCP_KEENABLE_API_KEY: ${{ secrets.BETA_MCP_KEENABLE_API_KEY }}

      - name: Upload to Firebase App Distribution
        uses: wzieba/Firebase-Distribution-Github-Action@v1
        with:
          appId: ${{ secrets.FIREBASE_APP_ID }}
          serviceCredentialsFileContent: ${{ secrets.FIREBASE_SERVICE_ACCOUNT_JSON }}
          groups: internal-testers
          file: app/build/outputs/apk/debug/app-debug.apk
          releaseNotes: |
            Version: $APP_VERSION_NAME
            Commit: ${{ github.sha }}
            Branch: ${{ github.ref_name }}

      - name: Create GitHub Release (tagged only)
        if: github.ref_type == 'tag'
        uses: softprops/action-gh-release@v2
        with:
          name: Beta ${{ env.APP_VERSION_NAME }}
          body: |
            ## Beta Release ${{ env.APP_VERSION_NAME }}
            **Commit:** ${{ github.sha }}
          files: app/build/outputs/apk/debug/app-debug.apk
          generate_release_notes: true
```

### `.github/workflows/build-release.yml` — Production release

```yaml
name: Build Release

on:
  push:
    branches: [master]
    tags: ["v[1-9].*"]

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write

    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Decode keystore
        run: |
          mkdir -p keystore
          echo "${{ secrets.KEYSTORE_FILE }}" | base64 -d > keystore/meme_chat_upload.keystore

      - name: Set version
        run: |
          if [[ "${{ github.ref_type }}" == "tag" ]]; then
            VERSION="${{ github.ref_name }}"
            VERSION="${VERSION#v}"
            echo "APP_VERSION_NAME=$VERSION" >> $GITHUB_ENV
          else
            echo "APP_VERSION_NAME=0.0.${{ github.run_number }}" >> $GITHUB_ENV
          fi

      - name: Build signed AAB and APK
        run: |
          ./gradlew bundleRelease assembleRelease \
            -PappVersionCode=${{ github.run_number }} \
            -PappVersionName=$APP_VERSION_NAME
        env:
          HUGGINGFACE_API_KEY: ${{ secrets.PROD_HUGGINGFACE_API_KEY }}
          MCP_KEENABLE_API_KEY: ${{ secrets.PROD_MCP_KEENABLE_API_KEY }}
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}

      - name: Upload to Google Play
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
          packageName: fun.walawe.memechat
          releaseFiles: app/build/outputs/bundle/release/app-release.aab
          track: internal
          status: completed
          whatsNewDirectory: whatsnew/

      - name: Create GitHub Release (tagged only)
        if: github.ref_type == 'tag'
        uses: softprops/action-gh-release@v2
        with:
          name: Release $APP_VERSION_NAME
          body: |
            ## Release $APP_VERSION_NAME
            **Commit:** ${{ github.sha }}
          files: app/build/outputs/apk/release/app-release.apk
          generate_release_notes: true
```

---

## Versioning Strategy

### Tag convention

```
v0.1.0        → Beta release (triggers build-beta.yml on tag push)
v1.0.0        → Production release (triggers build-release.yml on tag push)
v1.1.0-alpha  → Pre-release (tag format requires v[1-9].* for production)
```

### Version resolution (via `Secrets.kt`)

- **versionCode** — passed as `-PappVersionCode=${{ github.run_number }}` (GitHub run number)
- **versionName** — computed from tag name or `0.0.<run_number>` if not tagged

---

## App Bundles vs APKs

Google Play **requires** the Android App Bundle (`.aab`) format for new apps since August 2021.

The pipeline produces both:
- `.aab` → Google Play Store (signed, optimized for each device config)
- `.apk` → GitHub Release artifact

---

## Play Store Listing Assets

The `build-release.yml` workflow reads release notes from `whatsnew/en-US`.

All other Play Store listing assets are under `whatsnew/play/` for manual upload via Google Play Console:

```
whatsnew/
├── en-US                         ← Release notes (used by CI)
└── play/                         ← Manual upload only
    ├── listing/en-US/
    │   ├── title.txt
    │   ├── short-description.txt
    │   └── full-description.txt
    └── releases/
        └── whatsnew-en-US.txt
```

---

## Release Cadence

| Stage | Trigger | Who sees it | Gating |
|---|---|---|---|
| **PR tests** | PR to beta/master | — | Automatic |
| **Beta deploy** | Push to beta | Internal testers (Firebase) | Automatic |
| **Internal test** | Push to master | Google Play Internal track | Automatic |
| **Closed alpha** | Promote in Play Console | 20 trusted testers | Manual |
| **Closed beta** | Promote in Play Console | Up to 100 testers | Manual |
| **Production** | Promote in Play Console | All users | Manual (staged rollout) |

---

## End-to-End Release Flow

```
Feature work:
  ┌─ develop branch ──────────────────────────────┐
  │  git checkout -b feat/my-feature               │
  │  ... code, commit, push ...                    │
  │  PR → develop (tests run via test.yml)         │
  └────────────────────────────────────────────────┘
          │
          ▼
  ┌─ beta branch ─────────────────────────────────┐
  │  Merge develop → beta                          │
  │  GitHub Actions: build-beta.yml                │
  │    → Firebase App Distribution (testers)       │
  └────────────────────────────────────────────────┘
          │  QA approves
          ▼
  ┌─ master branch ───────────────────────────────┐
  │  PR beta → master (tests run via test.yml)     │
  │  Merge → triggers build-release.yml            │
  │    → Signed AAB to Google Play (internal)      │
  └────────────────────────────────────────────────┘
          │
          ▼
  ┌─ Promote in Play Console ─────────────────────┐
  │  internal → alpha → beta → production          │
  │  (manual, staged rollouts)                     │
  └────────────────────────────────────────────────┘
```

---

## Security Checklist

- [x] Keystore never committed to git
- [x] API keys in GitHub Secrets, not in source code
- [x] `google-services.json` kept (public Firebase API key only)
- [x] R8/ProGuard obfuscation enabled for release
- [x] Service account JSON never committed
- [ ] Play App Signing enabled (Google holds the production key)

---

## File Status

| File | Status |
|---|---|
| `app/proguard-rules.pro` | ✅ Configured with keep rules |
| `app/build.gradle.kts` | ✅ Signing + versioning via Secrets |
| `keystore/meme_chat_upload.keystore` | ✅ Generated (alias: `upload`) |
| `keystore/meme_chat_upload.jks` | ✅ Generated (alias: `meme_chat_upload`) |
| `keystore/upload_cert.pem` | ✅ Extracted public cert |
| `whatsnew/` | ✅ Release notes ready |
| `.github/workflows/test.yml` | ✅ Tests on PR to beta/master |
| `.github/workflows/build-beta.yml` | ✅ Firebase + GitHub release |
| `.github/workflows/build-release.yml` | ✅ Play Store + GitHub release |
| `keystore/` in `.gitignore` | ✅ Added |
| `secrets.properties` / `keystore.properties` | ✅ Gitignored |
| `gradle.properties` | ✅ Clean (no secrets) |
| `whatsnew/play/` listing assets | ✅ Created (for manual upload) |
| Firebase Crashlytics | ✅ Configured |
