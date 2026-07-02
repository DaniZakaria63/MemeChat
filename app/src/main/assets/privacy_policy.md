# Privacy Policy

**Last updated: July 2, 2026**

---

## Our Commitment to Your Privacy

Welcome to **Memelm** ("the App," "we," "us," or "our"). This Privacy Policy explains how we handle information when you use our application. We built Memelm around a fundamental principle: **your data belongs to you, and you alone.**

Unlike most chat applications available today, Memelm is designed to run entirely on your device. Every conversation, every image you share, every piece of context the app remembers — all of it stays physically on your phone. We do not operate servers that store your chats. We do not have access to your conversations. We do not sell, rent, or share your personal information with anyone.

Please read this policy carefully. By using Memelm, you agree to the practices described here.

---

## 1. Information We Collect — And What We Do Not Collect

### 1.1 No Account, No Profile, No Cloud

Memelm does not require you to create an account, sign in, or provide any personal identifying information such as your name, email address, phone number, or date of birth. There is no cloud sync, no user profile, and no backend server that maintains a copy of your data.

### 1.2 No Collection of Conversations or Messages

All chat conversations, messages, images, and any content you generate or share within the App are stored **exclusively on your device** in app-private storage:

- **Conversation history** is stored in a local Room database on your phone's internal storage.
- **Embedded memory vectors** (mathematical representations of text used for retrieval-augmented generation) are stored in a local FAISS vector index file on your device.
- **Images** you attach to conversations are resized and stored locally in app-private directories.

None of this data is transmitted to us or any third party for processing, analysis, or storage.

### 1.3 No Collection of Input for Model Training

The on-device language model processes your prompts and images **locally using your device's CPU**. Your inputs are never sent to a remote server for inference. We do not use your conversations to train, fine-tune, or improve any machine learning model.

### 1.4 Information That May Be Collected

The App uses certain third-party services that may collect limited information as described below. You can control most of these through your device settings and by choosing whether to enable certain features.

---

## 2. On-Device Processing — The Core of Memelm

### 2.1 Local Language Model Inference

Memelm downloads a large language model (LLM) file to your device on first launch. Once downloaded, all AI inference — including generating responses, analyzing images, and retrieving memory context — happens **entirely offline on your device**. No network connection is required for chat functionality after the initial model download.

### 2.2 On-Device Image Processing

When you attach an image to a conversation, the image is resized, compressed, and saved locally on your device. The image is then processed by the on-device multimodal model to generate a response. **Your images never leave your device** unless you explicitly choose to share them through other apps via Android's standard sharing mechanism.

### 2.3 On-Device Memory (RAG) System

Memelm includes a retrieval-augmented generation (RAG) memory system that helps the app recall relevant context from your past conversations. This system:

- Processes your messages into text chunks using on-device natural language processing
- Converts those chunks into vector embeddings using a separate on-device embedding model
- Stores both the text and vector representations in local storage on your device
- Searches this local memory when you ask a question to provide relevant context

**All of this happens locally.** Your messages are not sent to any external service for embedding, storage, or retrieval. The entire memory pipeline operates on your device and nowhere else.

---

## 3. Information You Voluntarily Provide

### 3.1 Images from Your Gallery

If you choose to attach images from your device's gallery, Memelm requests the `READ_MEDIA_IMAGES` permission (on Android 13+) to let you select photos. The images you select are:

- Resized to a maximum of 224×224 pixels
- Converted to WebP format at 80% quality
- Saved to the App's internal storage

**Your original gallery images are not modified or uploaded.** You can revoke this permission at any time through your device settings, and the App will continue to function without image attachment capability.

### 3.2 Web Search (Optional, User-Initiated)

Memelm offers an optional web search feature that you can enable or disable at any time from the chat interface. When you enable this feature and send a message:

- Your query text is sent to **Keenable AI** (api.keenable.ai), a third-party web search service
- Keenable processes your query and returns publicly available web search results
- These results are injected into your prompt context to help the model provide more informed responses

**Important notes about web search:**
- This feature is **off by default** and must be explicitly enabled by you for each session
- Only the text of your current message is sent to Keenable — no conversation history, no images, no personal identifiers
- The search query is processed by Keenable under their own privacy policy
- You can disable web search at any time, and the App functions fully without it
- We do not log, store, or have access to your search queries

### 3.3 URL Content Fetching (Optional, User-Initiated)

When you use the web search feature, you may also choose to fetch the full content of a specific URL. This sends the URL to Keenable AI, which retrieves the publicly accessible content of that webpage and returns it to the App for context. This feature is also entirely optional and user-initiated.

---

## 4. Permissions We Request

Memelm requests the following permissions, each for a specific purpose:

| Permission | Purpose | Required? |
|---|---|---|
| `READ_MEDIA_IMAGES` | Let you select images from your gallery to attach to conversations | No — you can use the App without this |
| `FOREGROUND_SERVICE` | Download model files in the background without being interrupted | Yes — for initial model setup |
| `FOREGROUND_SERVICE_DATA_SYNC` | Declare the foreground service type for model downloads | Yes — for initial model setup |
| `POST_NOTIFICATIONS` | Show download progress notifications for model files | No — you can deny this |

You can grant or revoke any of these permissions through your device's Settings > Apps > Memelm > Permissions at any time.

---

## 5. Third-Party Services

Memelm integrates with the following third-party services. Each is described below with details on what data is shared and why.

### 5.1 Hugging Face (Model Hosting)

- **Purpose**: Hosting the GGUF model files (language model, vision projector, and embedding model) that the App downloads to function
- **Data shared**: Your IP address and the requested file URL when downloading model files
- **Control**: The download URLs are configurable; you could technically self-host the model files
- **Optional**: Once the models are downloaded, no further communication with Hugging Face occurs

### 5.2 Keenable AI (Web Search)

- **Purpose**: Providing web search and URL fetching capabilities when you explicitly enable the web search feature
- **Data shared**: The text of your current message query and/or the URL you choose to fetch
- **Data NOT shared**: Your conversation history, images, device identifiers, or personal information
- **Control**: This feature is off by default and can be disabled at any time

### 5.3 Firebase Remote Config

- **Purpose**: Dynamically serving configuration values such as model download URLs and feature flags
- **Data shared**: Standard Firebase Remote Config communication (may include instance IDs and app version information)
- **Control**: This operates automatically when the App starts; no personal data is transmitted through this channel

### 5.4 Firebase Analytics (Optional)

- **Purpose**: Understanding general usage patterns to improve the App's functionality and user experience
- **Data collected**: Anonymous usage statistics such as screen views, feature interactions, and crash-free sessions
- **Data NOT collected**: Your messages, conversation content, images, or any personal identifying information
- **Control**: You can opt out of analytics collection through your device settings or by using the "Limit Ad Tracking" / "Delete Advertising ID" options on your device

### 5.5 Firebase Crashlytics (Optional)

- **Purpose**: Monitoring and diagnosing application crashes to improve stability
- **Data collected**: Crash logs, device state at time of crash, app version, Android OS version, device model
- **Data NOT collected**: Your messages, conversation content, images, or any personal identifying information
- **Control**: Crashlytics operates automatically for crash reporting; you can disable it by opting out of analytics

---

## 6. Data Storage and Retention

### 6.1 Where Your Data Lives

All user-generated data — including conversations, messages, images, and memory vectors — is stored **exclusively on your device** in the following locations:

- **Room Database** (`memechat.db`): Stores conversations, messages, and text chunks in your app's internal database directory. This is not accessible to other apps without root access.
- **FAISS Vector Index** (`chunk_vector.faiss`): Stores vector embeddings for the RAG memory system in your app's internal files directory.
- **Model Files** (`ml_models/`): Stores downloaded GGUF model files in your app's internal files directory.
- **Images** (`images/`): Stores resized, compressed images attached to conversations in your app's internal files directory.

### 6.2 Data Retention

Data is retained on your device until you choose to delete it. You can:

- **Delete individual conversations**: Long-press a conversation in the sidebar and select delete. This removes the conversation, its messages, associated images, and the corresponding memory vectors.
- **Clear model cache**: Use the Settings screen to remove downloaded model files (note: this will require re-downloading to use the App).
- **Uninstall the App**: Uninstalling Memelm removes all app-private data, including conversations, images, model files, and memory vectors from your device.

### 6.3 Backup

Memelm does not perform any cloud backup of your data. If you uninstall the App or wipe your device, your conversations and settings will be permanently lost unless you have manually backed up your device using Android's built-in backup system. If Android's app backup is enabled, your Room database may be included in system-level backups. We recommend reviewing Android's backup settings if you are concerned about this.

---

## 7. Data Security

Because your data never leaves your device, the primary security considerations are physical and OS-level:

- **App-private storage**: All data is stored in Android's app-private directory, which is sandboxed by the operating system and not accessible to other applications without root (superuser) privileges.
- **No network transmission**: Your conversations are never transmitted over the network, eliminating the risk of interception during transmission.
- **On-device processing**: All AI inference, image processing, and memory retrieval happen locally, so your data is never exposed to external processing systems.
- **Device-level security**: Your data is protected by whatever security measures you have enabled on your device — screen lock, encryption, Google Play Protect, and Android's permission system.

**A note of transparency**: The model files themselves (the GGUF weights) are not encrypted. This is standard practice for on-device models, as encryption would prevent the inference engine from loading them. These files contain generic model weights, not your personal data.

---

## 8. Children's Privacy

Memelm is not directed at children under the age of 13 (or the applicable age of consent in your jurisdiction). We do not knowingly collect personal information from children. Since the App does not collect personal information from any user — adult or child — this restriction exists primarily because the on-device language model may generate content that is not appropriate for children.

If you are a parent or guardian and believe your child has provided personal information through the App (which would be limited to the content of their conversations stored locally on your device), you can simply delete the conversations or uninstall the application to remove all such data.

---

## 9. Your Rights and Choices

Depending on your jurisdiction, you may have the following rights regarding your personal information:

- **Right to Access**: Since we do not collect or store your personal data on our servers, there is nothing for us to provide. All your data is on your device and fully accessible to you.
- **Right to Deletion**: You can delete your data at any time by deleting conversations within the App or uninstalling the application.
- **Right to Data Portability**: Your data is already stored on your device in a standard SQLite database format. You can access it through any SQLite viewer if your device allows file access.
- **Right to Object / Opt-Out**: You can opt out of Firebase Analytics by adjusting your device's advertising ID settings.

To exercise any of these rights, or if you have questions about this policy, please contact us using the information in Section 13.

---

## 10. Data Transfers

Because Memelm processes data entirely on your device, there is no routine transfer of your personal data across international borders. The limited data transfers that do occur (model downloads, web search queries when enabled) are described in Sections 3.2, 3.3, and 5. These are subject to the standard data processing practices of the respective third-party services.

---

## 11. Changes to This Privacy Policy

We may update this Privacy Policy from time to time to reflect changes in our practices, legal requirements, or the App's functionality. We will notify you of material changes by:

- Updating the "Last updated" date at the top of this policy
- Displaying a notice within the App after a significant update
- Providing the updated policy through the App's settings screen

We encourage you to review this policy periodically. Your continued use of Memelm after changes take effect constitutes your acceptance of the updated policy.

---

## 12. Governing Law

This Privacy Policy shall be governed by and construed in accordance with the laws of Indonesia, without regard to its conflict of law provisions. By using the App, you agree to submit to the personal jurisdiction of the courts located in Indonesia for any disputes arising out of or relating to this policy.

---

## 13. Contact Us

If you have any questions, concerns, or requests regarding this Privacy Policy or our data practices, please contact us:

**Developer**: Dani Zakaria
**Email**: dani.zakaria@proton.me
**Website**: https://danizakaria63.github.io

We will make every effort to address your concerns promptly and thoroughly.

---

## Summary

To put it simply: **Memelm runs entirely on your device. Your conversations, your images, and your memories stay on your phone. Period.**

We built this application because we believe powerful AI should not come at the cost of your privacy. Every design decision — from running models locally to keeping the RAG pipeline on-device to making web search opt-in — reflects our commitment to ensuring that your data remains yours, and yours alone.
