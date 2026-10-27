# Getting started

## Install and connect

Download `Agentic-Hydra-release.apk` from [Releases](https://github.com/theonlyasdk/AgenticHydra/releases/latest) and install it on Android 8.0 or newer. Future official releases can update that installation in place. Android cannot update an app signed by a different key.

In **User → Connections**, choose a provider and enter its details. The connection check makes a short authenticated model request. A successful check verifies that request; it cannot guarantee access to every model in the provider's catalog.

| Provider | Connection base | What you supply |
| :-- | :-- | :-- |
| NVIDIA NIM | `https://integrate.api.nvidia.com/v1` | NVIDIA API key and an accessible model |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | Gemini API key and an accessible model |
| Ollama | `http://YOUR_LAN_IP:11434/v1` | Reachable server with downloaded models |
| llama.cpp | `http://YOUR_LAN_IP:8080/v1` | Reachable server and its model alias |
| Custom | Your OpenAI-compatible `/v1` base | Endpoint, model ID, and key when required |

Your phone must be able to reach a local server. Use the server's LAN address, configure its listening interface and firewall as needed, and remember that `localhost` means the phone. Hydra permits HTTP for servers without credentials; use HTTPS when sending an API key.

Tap the model control in either composer to browse the catalog. Refresh it after installing a local model. If a server does not expose a catalog, choose **Enter model ID** and supply the exact identifier. Reasoning controls appear for recognized model families; unsupported settings use server defaults.

## Chat

Send a message and watch the answer stream. Scroll away from the bottom to read earlier content; the down button returns to the latest response. Completed answers offer Copy and Share, along with timing and token-rate information. Estimated token counts are marked when the provider does not supply them.

The history drawer opens saved chats. Long-press an item for rename, export, and delete actions. Hydra can name a new conversation using its selected model after the first successful response; manual names are preserved.

## Work

1. Open **Work → New task**.
2. Select a model that supports OpenAI-compatible function calling.
3. In **+ → Change directory**, choose a project in shared internal storage using Android's folder picker. Public web research can run without a project.
4. Describe the task. Inspect tool requests and choose **Allow once** or **Decline** when prompted.
5. Reopen the task from Work history to send follow-ups using the saved conversation and project context.

The **+** menu supports text/code attachments through Android's file picker and images through the media picker. Text files are limited to 128 KiB, images to 4 MiB, and a message to four attachments. Image support depends on the selected provider and model. PDF and video attachments are not supported.

The default approval policy asks before actions. Other policies can allow reads while asking for writes and commands, or allow the next task. One Work generation runs at a time, with up to 24 model turns per user message.

## Termux for shell and Git

Install Termux from [F-Droid](https://f-droid.org/packages/com.termux/) or the [official Termux releases](https://github.com/termux/termux-app/releases), then open it once. In Termux:

```sh
termux-setup-storage
mkdir -p ~/.termux
echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings
pkg install git
```

Accept the storage permission, then open **User → Termux setup → Grant command permission** in Hydra. Install any additional project tools in Termux, such as Python or Node.js.

Hydra uses Termux's [RUN_COMMAND interface](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) and receives stdout, stderr, and the process exit code. Commands start in the chosen project directory. Android shared storage cannot execute binaries directly; run scripts using their interpreter, such as `python script.py` or `bash script.sh`.

If a command fails, check that Termux has been opened, external apps are enabled, permissions are granted, and the required binary exists in Termux. Long-running commands have a three-minute callback timeout. Stop prevents new agent steps but does not necessarily terminate an already running Termux process.

## Privacy and storage

Keys are encrypted with Android Keystore. Histories are stored in app-private storage and Android backup is disabled. Hydra retains up to 100 Chat conversations and 40 Work conversations; export chats you want to keep separately.

Your configured provider receives the content needed for inference, including relevant messages, attachments, and tool results. Search queries go to public search services; fetched pages come from the requested hosts. Local history does not make remote inference offline.
