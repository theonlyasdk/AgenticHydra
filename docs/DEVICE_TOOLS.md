# Device tools and questions

Work has 48 tools: 19 project/web/shell tools, 21 device tools, seven time/scheduling tools, and `ask_user`. Select a model that supports OpenAI-compatible function calling. Device actions use the same approval policy as other tools. **Full access** skips Hydra's tool prompts but cannot grant Android permissions.

## Device tools

| Tool | Behavior |
| :-- | :-- |
| `device_info` | Model, Android version, locale, time zone, flashlight capability; no persistent device identifiers |
| `battery_info` | Percentage, charging state, and temperature |
| `storage_info` | Total and available space; does not read personal files |
| `network_info` | Connectivity, internet validation, and transport; no Wi-Fi names or network identifiers |
| `display_info` | Current app display size, density, and font scale |
| `audio_info` | Media and alarm volume levels |
| `set_volume` | Adjust media or alarm volume |
| `set_flashlight` | Turn the flashlight on/off without opening the camera or requesting camera capture access |
| `vibrate` | Vibrate for up to two seconds |
| `list_apps` | Search/list up to 100 visible launchable apps; no private app data |
| `open_app` | Open an app by its package name |
| `open_url` | Open an HTTP(S) URL in the browser |
| `open_settings` | Open Wi-Fi, Bluetooth, location, sound, display, battery, or Hydra settings |
| `set_screen_brightness` | Set brightness for the current Hydra task window, or restore the system default |
| `get_location` | One approximate foreground location, after permission; 15-second timeout, no tracking |
| `set_alarm` | Request an alarm through the installed clock UI |
| `set_timer` | Request a timer through the installed clock UI |
| `dial_number` | Open the dialer for review; never places a call or runs USSD codes |
| `compose_email` | Open an email draft for review; never sends automatically |
| `create_calendar_event` | Open a calendar event draft; no calendar reading |
| `copy_to_clipboard` | Write text to the clipboard; never reads it |

Open the running Work conversation to complete device actions. Hydra waits when its task UI is not in the foreground. Missing permissions use Android's own prompts, and denial is returned to the model as a failure. Unavailable hardware, missing app handlers, or disabled location produce explicit errors. The model must not claim an opened draft or settings screen proves the user completed an action.

Native device integration follows Android's [common intents](https://developer.android.com/guide/components/intents-common), [CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager), and [LocationManagerCompat](https://developer.android.com/reference/androidx/core/location/LocationManagerCompat) APIs. No accessibility service, contacts/SMS reading, background location, or camera capture is enabled by these tools.

Device results needed by the task are sent to your selected model provider, just like other tool results. Allow a device request only when it is relevant to what you asked the agent to do.

## Question wizard

The model can call `ask_user` with one to eight related questions. Supported formats are:

- **Single choice:** select one option.
- **Multiple choice:** select several options.
- **Text:** enter a response of up to 1,000 characters.
- **Yes/no:** choose an explicit Yes or No.

Choice questions may offer a custom answer. Optional questions can be skipped. Back and Next preserve earlier answers, and Send answers returns all responses together in request order. The agent pauses until submission or dismissal. Dismissing returns a cancellation result; stopping the task clears the pending wizard. Interrupted processes do not silently replay old requests.

Question answers are not tool approvals or Android permission grants. Approval policy and system permission checks still apply to any subsequent action.
