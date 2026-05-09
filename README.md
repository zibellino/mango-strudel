# MangoStrudel

Voice/text-driven live coding with Strudel + Gemini, as an Android app.

## Setup

1. Create a new GitHub repo and push this folder to it
2. GitHub Actions will automatically build the APK
3. Download the APK from the **Actions** tab → latest run → **MangoStrudel-debug** artifact
4. Install on your phone (enable "Install from unknown sources" if needed)

## On first launch

Enter your Gemini API key when prompted. Get a free one at https://aistudio.google.com

## Usage

Type a musical description and press Send. Strudel will play it.
Subsequent commands modify the running pattern live.

## Architecture

- **Android WebView** loads a bundled build of the Strudel REPL from assets
- **Gemini API** (gemini-3.1-flash-lite-preview) translates natural language to Strudel code
- **`strudelMirror.setCode()` + `strudelMirror.evaluate()`** hot-reloads the pattern
- Self-correcting: if Strudel throws an error, the code is sent back to Gemini for fixing

## License

GPL-3.0. See [LICENSE](LICENSE). This project incorporates [Strudel](https://strudel.cc), also GPL-3.0.

---

*The code in this repository was developed in collaboration with [Claude](https://claude.ai) by Anthropic.*
