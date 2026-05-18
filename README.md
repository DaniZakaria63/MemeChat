# My Zeta - 3D AI Assistant

**My Zeta** is State-of-the-Art author of his career path. This app runs the [MiniCPM](https://github.com/OpenBMB/MiniCPM) multimodal language model fully on-device. It leverages [llama.cpp](https://github.com/ggml-org/llama.cpp) under the hood via a JNI bridge. Downloads model weights on demand, and wires everything together into a single integrated app.

---

## Architecture Overview

```
My Zeta AI/
├── app/          # Entry point — integrates all modules into the running application
├── bridgelm/     # Core inference engine — MiniCPM via llama.cpp + JNI
├── modelpull/    # Model downloader — fetches all files dependency via HTTP
├── stt/          # Speech-to-text module via whisper.cpp
├── tts/          # Text-to-speech module 
└── constant/     # Shared configuration — URLs, keys, and build-time constants

```

The modules are intentionally separated by responsibility. The `app` module sits at the top of the dependency graph and assembles everything.


---

## Modules

### `BridgeLM` — On-Device Inference Engine

This is the core library module responsible for loading and running the MiniCPM multimodal model entirely on the device.

**Responsibilities:**
- Bridges into [llama.cpp](https://github.com/ggerganov/llama.cpp) via the **Java Native Interface (JNI)**, exposing a Kotlin/Java API over the native C++ inference engine.
- Loads the main GGUF model file and the associated **mmproj** (multimodal projector) file for vision-language tasks.
- Manages the llama.cpp context lifecycle. Including initialization, sampling, token generation, and teardown.
- Handles tokenization and decoding for both text and image inputs.
- Exposes a clean, suspendable API so inference can be called from coroutines without blocking the main thread.

**Key internals:**

| Layer | Technology |
|---|---|
| Inference runtime | llama.cpp (C++) |
| Android integration | JNI (`System.loadLibrary`) |
| Native build | CMake / Android NDK |
| API surface | Kotlin (suspend functions / Flow) |

> **Note:** 
> - The native `.so` libraries are compiled for ABI `arm64-v8a` only and bundled inside this module's AAR.
> - Sample specification from official llama.cpp at `LIB_REF_INFORMATION.md`

---

### `modelpull` — Model Downloader

This module handles everything related to fetching model files from a remote source before the inference engine can run.

**Responsibilities:**
- Downloads the **GGUF model weights** file (the main language model).
- Downloads the **mmproj** (multimodal projector) weights file required for image understanding.
- Reports **real-time download progress** so the UI can display a progress bar or percentage.
- Persists downloaded files to the app's internal storage in a predictable, versioned path.

**Key internals:**

| Concern | Technology                                     |
|---|------------------------------------------------|
| HTTP client | [OkHttp](https://square.github.io/okhttp/)     |
| REST abstraction | [Retrofit](https://square.github.io/retrofit/) |
| Progress tracking | Response `Retrofit` to `Worker Manager`        |

---

### `constant` — Shared Configuration

A configuration-only module that acts as the single source of truth for all compile-time and runtime constants shared across the other modules.

**Design principle:** This module depends on nothing and is depended on by everything. Keeping constants here prevents duplication and makes global changes a one-file edit.

---

### `app` — Application 

The top-level Android application module that wires all other modules together into the final installable APK.

**Responsibilities:**
- Integrate all modules based on their specific functionality.
- Composes the UI layer and manage state given by ViewModel.
- Listen to User specific event like cancellation or network error.
- Manages Android permissions, foreground service for long-running downloads, and lifecycle-aware cleanup of native resources.

---

## Important Notes!

### Prerequisites

- Android Studio Hedgehog or later
- Android NDK 27.2.12479018
- CMake 3.31.6

make sure to pull the llama.cpp submodule:
```bash
git submodule update --init --recursive
&&
sudo apt update && sudo apt install ninja-build
```

Don't forget to set the `ANDROID_NDK` environment variable to your NDK path
```bash

NDK_PATH=/home/dani/Android/Sdk/ndk/27.2.12479018

```

If you find following error about Vulkan SDK missing
```
cp vulkan.hpp \
  "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vulkan/"

#OR
  
cd /tmp
wget https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.275.tar.gz
tar xf v1.3.275.tar.gz

VULKAN_DIR="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vulkan"

# Copy everything from the Vulkan-Headers include/vulkan folder
cp -r Vulkan-Headers-1.3.275/include/vulkan/* "$VULKAN_DIR/"
```
Or if you find another error about SPIR-V header missing temporarily 
```bash
cd /tmp
git clone --depth 1 https://github.com/KhronosGroup/SPIRV-Headers.git

NDK_PATH=/home/dani/Android/Sdk/ndk/27.2.12479018
cp -r SPIRV-Headers/include/spirv \
   "$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/"
```

## Module Dependency Graph (Gradle)

```
// app/build.gradle
dependencies {
    implementation(project(":memelm"))
    implementation(project(":modelpull"))
    implementation(project(":constant"))
}

// memelm/build.gradle
dependencies {
    implementation(project(":constant"))
}

// modelpull/build.gradle
dependencies {
    implementation(project(":constant"))
}

// constant/build.gradle
```

---

## License

Distributed under the MIT License. See `LICENSE` for details.

llama.cpp is licensed under the MIT License. MiniCPM model weights are subject to their respective upstream license.