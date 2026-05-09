# MangoStrudel

Voice/text-driven live coding with Strudel + Gemini, as an Android app.

## Setup

1. Create a new **public or private** GitHub repo
2. Push this folder to it
3. GitHub Actions will automatically build the APK
4. Download the APK from the **Actions** tab → latest run → **MangoStrudel-debug** artifact
5. Install on your phone (enable "Install from unknown sources" if needed)

## On first launch

Enter your Gemini API key when prompted. Get a free one at https://aistudio.google.com

## Usage

Type a musical description and press Send. Strudel will play it.
Subsequent commands modify the running pattern.

## Architecture

- **Android WebView** loads a bundled build of the Strudel REPL from assets
- **Gemini API** translates natural language to Strudel code
- **`strudelMirror.setCode()` + `strudelMirror.evaluate()`** hot-reloads the pattern
- No manual play button needed — everything is automatic
