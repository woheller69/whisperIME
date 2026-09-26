 ```
Google has announced that, starting in 2026/2027, all apps on certified Android devices
will require the developer to submit personal identity details directly to Google.
Since the developers of this app do not agree to this requirement, this app will no longer 
work on certified Android devices after that time.
```

## Donate
<pre>Send a coffee to 
woheller69@t-online.de 
<a href= "https://www.paypal.com/signin"><img  align="left" src="https://www.paypalobjects.com/webstatic/de_DE/i/de-pp-logo-150px.png"></a>

  
Or via this link (with fees)
<a href="https://www.paypal.com/donate?hosted_button_id=XVXQ54LBLZ4AA"><img  align="left" src="https://img.shields.io/badge/Donate%20with%20Debit%20or%20Credit%20Card-002991?style=plastic"></a></pre>
# Voice recognition based on Whisper

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="150"/> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width="150"/>

Whisper is an input method editor (IME) that leverages voice recognition technology based on the Whisper engine. 
It offers a seamless user experience, functioning both as a standalone application and an integrated IME that can be activated, e.g. via the microphone button in HeliBoard.
As a standalone app Whisper can also translate any supported language to English.

Besides providing an IME, whisper can also be selected as system-wide voice input (RecognitionService) and it supports calls via intent (RecognizerIntent.ACTION_RECOGNIZE_SPEECH).

## Initial Setup

Upon launching Whisper for the first time, the app will download the necessary Whisper models (~435 MB) from Hugging Face. 
Please note that this is the only instance where internet permission is required. 
Once the models are downloaded, voice recognition works entirely offline, ensuring your privacy and convenience.

Please note that for use as voice input (not as IME) there is a separate settings activity which can be accessed from Android settings 
(System > Languages > Speech > Voice Input). There you can activate the app as voice input and then click the settings button.
In settings you can then select the model for voice input.

If after installation you do not find Whisper as voice input or only see a limited list (hard-coded ones like Google/Samsung)
- enable USB debugging
- type adb shell settings put secure voice_recognition_service org.woheller69.whisper/com.whispertflite.WhisperRecognitionService

## Model Selection

Whisper offers two models to choose from: a compact English-only model that prioritizes speed and a more comprehensive multi-lingual model that, while much slower, 
supports a broader range of languages. Select your preferred model within the app, and it will be applied consistently across all uses, including when used as an IME.

## Using Whisper

To get the most out of Whisper, follow these simple tips:

- Tap the button to start and stop recording, or press and hold it while speaking. Automatic mode is available too
- Pause briefly before starting to speak
- Speak clearly, loudly, and at a moderate pace
- Recordings can be up to 5 minutes long. Whisper processes 30 s at a time, so longer recordings are transcribed in chunks

By following these guidelines, you'll be able to enjoy accurate and efficient voice recognition with Whisper.

## Floating microphone

A floating microphone button appears when you edit a text field in any app. Tap it to dictate and insert the text into that field, without switching keyboards. You can drag the button to either edge of the screen.

### Setup

1. Open Whisper and tap "Floating mic button". This opens the Accessibility settings.
2. Enable "Whisper floating mic".

On Android 13+ you may first have to allow "restricted settings" for the app (App info → ⋮ menu → "Allow restricted settings"). This is needed for apps that were not installed from an app store.

### The dictation panel

| Control | What it does |
|---|---|
| Wave button | Starts and stops recording. You can keep talking while earlier parts are still being transcribed: the parts are transcribed in order and the text is joined |
| ✓ | Stops recording if needed, waits for all pending parts, inserts the text and closes the panel |
| Cancel recording | Discards the part that is being recorded. Only shown while recording |
| Cancel transcription | Stops the running and the queued transcriptions. Only shown while transcribing |
| ✕ | Closes the panel and discards everything |
| Handle (top) | Drag to move the panel, the position is remembered |

"Cancel recording" and "Cancel transcription" keep the text that has already been transcribed.

### Privacy and permissions

Android lets apps use the microphone in the background and insert text into other apps only in a few cases. An accessibility service is the only reliable one. It is used only to:
- find out whether a text field has focus, so the button can be shown,
- insert the transcription into the focused field.

It does not read or store screen content. Audio is processed on the device and never leaves it.

- The button is hidden in password fields and nothing is inserted there.
- The text is inserted at the cursor by pasting it through the clipboard (marked as sensitive). Your previous clipboard content is restored right after. If an app refuses to paste, the field text is rewritten instead.
- If there is no text field to insert into, the text is left in the clipboard.

## Custom words

Whisper models sometimes misspell names and technical terms (e.g. "Helena" instead of "Elena", "cloud code" instead of "Claude Code"). The models cannot be given a word list, so the text is corrected after transcription. Under "Custom words…" you can add one entry per line:

| Entry | Effect |
|---|---|
| `Elena` | Words that sound alike are corrected to it (Helena → Elena). Case, accents, silent "h", d/t, b/p and doubled letters are ignored when comparing |
| `GPT` | Acronyms (all capitals) are matched the same way (GPD → GPT) |
| `cloud code = Claude Code` | Explicit replacement, for words that do not sound alike |
| `# note` | Lines starting with `#` are ignored |

Sound-alike matching is only used for entries with at least 5 letters, and for acronyms. Shorter entries only fix upper/lower case, so a name like "Ugo" cannot change unrelated words. Longer phrases win over shorter ones (`Claude Code` before `Claude`).

The corrections apply to the floating microphone, the keyboard, the app and the system voice input.

## Development

Debug builds use the application id `org.woheller69.whisper.dev`, so they can be installed next to the release app. Unit tests: `./gradlew :app:testDebugUnitTest`.

Known limitation: the native library of TensorFlow Lite 2.15 is not aligned to 16 kB pages. Newer, aligned LiteRT versions were measured to be about 5x slower for inference, so the app stays on TensorFlow Lite 2.15 for now.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="75">](https://f-droid.org/de/packages/org.woheller69.whisper/) [<img src="https://www.openapk.net/images/openapk-badge.png" height="75">]( https://www.openapk.net/whisper/org.woheller69.whisper/)


## Contribute

For translations use https://toolate.othing.xyz/projects/whisperime/

# License
This work is licensed under MIT license, © woheller69

- This app is based on the [Whisper-Android project](https://github.com/vilassn/whisper_android), published under MIT license
- It uses [OpenAI Whisper](https://github.com/openai/whisper) published under MIT license. Details on Whisper are found [here](https://arxiv.org/abs/2212.04356).
- It uses [Android VAD](https://github.com/gkonovalov/android-vad), which is published under MIT license
- It uses [Opencc4j](https://github.com/houbb/opencc4j), for Chinese conversions, published under Apache-2.0 license
- At first start it downloads the Whisper TFLite models from [Hugging Face](https://huggingface.co/DocWolle/whisper_tflite_models), which is published under MIT license

# OTHER APPS

| **RadarWeather** | **Gas Prices** | **Smart Eggtimer** |
|:---:|:---:|:--:|
| [<img src="https://github.com/woheller69/weather/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.weather/) | [<img src="https://github.com/woheller69/spritpreise/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.spritpreise/) | [<img src="https://github.com/woheller69/eggtimer/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.eggtimer/) |
| **Bubble** | **hEARtest** | **GPS Cockpit** |
| [<img src="https://github.com/woheller69/Level/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.level/) | [<img src="https://github.com/woheller69/audiometry/blob/new/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.audiometry/) | [<img src="https://github.com/woheller69/gpscockpit/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.gpscockpit/) |
| **Audio Analyzer** | **LavSeeker** | **TimeLapseCam** |
| [<img src="https://github.com/woheller69/audio-analyzer-for-android/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.audio_analyzer_for_android/) |[<img src="https://github.com/woheller69/lavatories/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.lavatories/) | [<img src="https://github.com/woheller69/TimeLapseCamera/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.TimeLapseCam/) |
| **Arity** | **Cirrus** | **solXpect** |
| [<img src="https://github.com/woheller69/arity/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.arity/) | [<img src="https://github.com/woheller69/omweather/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.omweather/) | [<img src="https://github.com/woheller69/solXpect/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.solxpect/) |
| **gptAssist** | **dumpSeeker** | **huggingAssist** |
| [<img src="https://github.com/woheller69/gptassist/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.gptassist/) | [<img src="https://github.com/woheller69/dumpseeker/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.dumpseeker/) | [<img src="https://github.com/woheller69/huggingassist/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.hugassist/) |
| **FREE Browser** | **whoBIRD** | **PeakOrama** |
| [<img src="https://github.com/woheller69/browser/blob/newmaster/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.browser/) | [<img src="https://github.com/woheller69/whoBIRD/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.whobird/) | [<img src="https://github.com/woheller69/PeakOrama/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.PeakOrama/) |
| **Whisper** | **Seamless** | **SherpaTTS** |
| [<img src="https://github.com/woheller69/whisperIME/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.whisper/) | [<img src="https://github.com/woheller69/seamless/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.seemless/) | [<img src="https://github.com/woheller69/ttsengine/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.ttsengine/) |
