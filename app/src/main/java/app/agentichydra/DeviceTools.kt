package app.agentichydra

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class DeviceActionRequest(
    val tool: String,
    val arguments: String,
    val id: String = UUID.randomUUID().toString(),
    val permissionLaunched: Boolean = false,
)

/** Lets device actions read a file from the selected SAF project without a raw-path permission. */
internal interface ProjectFileSource {
    val directory: String

    /** Opens the file at a real-path-derived relative location, or null when it does not exist. */
    fun open(relativePath: String): java.io.InputStream?
}

object DeviceTools {
    val names =
        listOf(
            "device_info",
            "battery_info",
            "storage_info",
            "network_info",
            "display_info",
            "audio_info",
            "set_volume",
            "set_flashlight",
            "vibrate",
            "list_apps",
            "open_app",
            "open_url",
            "open_settings",
            "set_screen_brightness",
            "get_location",
            "set_alarm",
            "set_timer",
            "dial_number",
            "compose_email",
            "create_calendar_event",
            "copy_to_clipboard",
        )
    val readOnly =
        setOf("device_info", "battery_info", "storage_info", "network_info", "display_info", "audio_info", "list_apps")

    fun permissions(name: String) =
        when (name) {
            "get_location" -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> emptyList()
        }

    fun missingPermissions(
        context: Context,
        name: String,
    ) = permissions(name).filter {
        ContextCompat.checkSelfPermission(context, it) !=
            PackageManager.PERMISSION_GRANTED
    }

    fun schema(): List<JSONObject> {
        fun tool(
            name: String,
            description: String,
            required: List<String> = emptyList(),
            vararg properties: Pair<String, JSONObject>,
        ) = toolSchema(name, description, required, *properties)

        fun str(description: String) = stringSchema(description)
        return listOf(
            tool(
                "device_info",
                "Read device model, Android version, locale, time zone, and flashlight support. Does not expose device identifiers.",
            ),
            tool("battery_info", "Read current battery percentage, charging state, and temperature."),
            tool("storage_info", "Read total and available bytes on app storage, without reading personal files."),
            tool("network_info", "Read connection state and transport type, without Wi-Fi names or identifiers."),
            tool("display_info", "Read the current app display size, density, font scale, and orientation."),
            tool("audio_info", "Read media and alarm volume levels."),
            tool(
                "set_volume",
                "Change media or alarm volume. Requires tool approval according to the selected policy.",
                listOf("stream", "percent"),
                "stream" to str("Audio stream.").put("enum", JSONArray(listOf("media", "alarm"))),
                "percent" to integerSchema("Volume percentage.", 0, 100),
            ),
            tool(
                "set_flashlight",
                "Turn the phone flashlight on or off. Requires available hardware; controls torch mode without opening the camera or taking photos.",
                listOf("enabled"),
                "enabled" to booleanSchema("True to turn on; false to turn off."),
            ),
            tool(
                "vibrate",
                "Vibrate the device. Duration 1-2000 ms; optional delay before starting, repeat count, and gap between repeats.",
                listOf("milliseconds"),
                "milliseconds" to integerSchema("Vibration duration.", 1, 2000),
                "delay_ms" to integerSchema("Delay before the first vibration.", 0, 5000),
                "repeat" to integerSchema("Number of additional vibrations after the first.", 0, 10),
                "gap_ms" to integerSchema("Gap between repeated vibrations.", 0, 2000),
            ),
            tool(
                "list_apps",
                "List up to 100 launchable apps visible to Hydra, optionally filtered by label or package. No private app data.",
                emptyList(),
                "query" to str("Optional name or package filter, up to 100 characters."),
            ),
            tool(
                "open_app",
                "Open a launchable Android app by its exact package name. Use list_apps to discover names.",
                listOf("package"),
                "package" to str("Android package name, e.g. com.termux."),
            ),
            tool(
                "open_url",
                "Open an HTTP(S) page, a file:/// path stored in shared storage or Hydra's own folders, or an app deep link such as youtube:// or instagram:// in the app that handles it. This does not read browser tabs or history.",
                listOf("url"),
                "url" to str("URL without embedded credentials, a file:/// path, or an app deep link."),
            ),
            tool(
                "open_settings",
                "Open an Android settings page so the user can make a change. Does not silently change system settings.",
                listOf("page"),
                "page" to
                    str(
                        "Settings page.",
                    ).put(
                        "enum",
                        JSONArray(listOf("wifi", "bluetooth", "location", "sound", "display", "battery", "app")),
                    ),
            ),
            tool(
                "set_screen_brightness",
                "Change brightness only for the current Hydra task window. Does not change global system brightness.",
                emptyList(),
                "percent" to integerSchema("Window brightness, default 50.", 1, 100),
                "use_system_default" to booleanSchema("Reset the Hydra window to system brightness."),
            ),
            tool(
                "get_location",
                "Get one approximate foreground location with Android permission. Only use when the user requests location-dependent help. Times out after 15 seconds; no background tracking.",
            ),
            tool(
                "set_alarm",
                "Request an alarm through the installed clock app. Opens the clock UI; do not claim the user saved it.",
                listOf("hour", "minute"),
                "hour" to integerSchema("Local hour.", 0, 23),
                "minute" to integerSchema("Minute.", 0, 59),
                "label" to str("Optional alarm label, up to 200 characters."),
            ),
            tool(
                "set_timer",
                "Request a timer through the installed clock app. Opens the clock UI.",
                listOf("seconds"),
                "seconds" to integerSchema("Timer duration in seconds.", 1, 86400),
                "label" to str("Optional timer label, up to 200 characters."),
            ),
            tool(
                "dial_number",
                "Open the dialer with a phone number for the user to review. Does not place a call; no USSD codes.",
                listOf("number"),
                "number" to str("Phone number, digits with optional leading +, spaces, parentheses, and hyphens."),
            ),
            tool(
                "compose_email",
                "Open an email draft for user review. Never sends email automatically.",
                listOf("to", "subject", "body"),
                "to" to str("Recipient email address."),
                "subject" to str("Subject, up to 200 characters."),
                "body" to str("Draft body, up to 4,000 characters."),
            ),
            tool(
                "create_calendar_event",
                "Open an event draft in the installed calendar for user review; does not read calendars or guarantee a saved event.",
                listOf("title", "start_time", "end_time"),
                "title" to str("Event title, up to 200 characters."),
                "start_time" to str("ISO 8601 timestamp with offset, e.g. 2026-10-28T09:00:00+05:30."),
                "end_time" to str("ISO 8601 timestamp with offset, after start_time."),
                "description" to str("Optional description, up to 2,000 characters."),
            ),
            tool(
                "copy_to_clipboard",
                "Copy text to the clipboard. Does not read clipboard contents.",
                listOf("text"),
                "text" to str("Text to copy, maximum 8,000 characters."),
            ),
        )
    }

    @Suppress("DEPRECATION")
    internal suspend fun execute(
        activity: Activity,
        name: String,
        args: JSONObject,
        project: ProjectFileSource? = null,
    ): WorkToolResult {
        require(name in names) { "Unknown device tool." }
        check(
            missingPermissions(activity, name).isEmpty(),
        ) { "Android permission was not granted. Do not retry without the user's instruction." }
        val result =
            when (name) {
                "device_info" -> {
                    JSONObject()
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("model", Build.MODEL)
                        .put("android", Build.VERSION.RELEASE)
                        .put(
                            "api_level",
                            Build.VERSION.SDK_INT,
                        ).put("locale", Locale.getDefault().toLanguageTag())
                        .put("time_zone", TimeZone.getDefault().id)
                        .put(
                            "flashlight_available",
                            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH),
                        )
                }

                "battery_info" -> {
                    val battery =
                        activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                            ?: error("Battery status is unavailable.")
                    val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    JSONObject()
                        .put(
                            "percent",
                            if (level >= 0 && scale > 0) {
                                (level * 100.0 / scale).roundToInt()
                            } else {
                                JSONObject.NULL
                            },
                        ).put(
                            "charging",
                            battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
                                setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL),
                        ).put("temperature_c", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
                }

                "storage_info" -> {
                    StatFs(
                        activity.filesDir.absolutePath,
                    ).let { JSONObject().put("total_bytes", it.totalBytes).put("available_bytes", it.availableBytes) }
                }

                "network_info" -> {
                    val manager = activity.getSystemService(ConnectivityManager::class.java)
                    val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                    JSONObject()
                        .put("connected", caps != null)
                        .put(
                            "validated_internet",
                            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                        ).put(
                            "transports",
                            JSONArray(
                                listOf(
                                    NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                                    NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                                    NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                                    NetworkCapabilities.TRANSPORT_VPN to "vpn",
                                ).filter { caps?.hasTransport(it.first) == true }.map { it.second },
                            ),
                        )
                }

                "display_info" -> {
                    activity.resources.let {
                        JSONObject()
                            .put(
                                "width_dp",
                                it.configuration.screenWidthDp,
                            ).put(
                                "height_dp",
                                it.configuration.screenHeightDp,
                            ).put("density", it.displayMetrics.density)
                            .put("font_scale", it.configuration.fontScale)
                    }
                }

                "audio_info" -> {
                    val audio = activity.getSystemService(AudioManager::class.java)
                    JSONObject()
                        .put(
                            "media",
                            volume(audio, AudioManager.STREAM_MUSIC),
                        ).put("alarm", volume(audio, AudioManager.STREAM_ALARM))
                }

                "set_volume" -> {
                    val stream =
                        when (args.getString("stream")) {
                            "media" -> AudioManager.STREAM_MUSIC
                            "alarm" -> AudioManager.STREAM_ALARM
                            else -> error("Choose media or alarm.")
                        }
                    val percent = args.getInt("percent")
                    require(percent in 0..100) { "Use a percentage from 0 to 100." }
                    val audio = activity.getSystemService(AudioManager::class.java)
                    val min =
                        if (Build.VERSION.SDK_INT >=
                            28
                        ) {
                            audio.getStreamMinVolume(stream)
                        } else {
                            0
                        }
                    val value = min + ((audio.getStreamMaxVolume(stream) - min) * percent / 100f).roundToInt()
                    audio.setStreamVolume(stream, value, AudioManager.FLAG_SHOW_UI)
                    volume(audio, stream)
                }

                "set_flashlight" -> {
                    val manager = activity.getSystemService(CameraManager::class.java)
                    val cameras =
                        manager.cameraIdList.filter {
                            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ==
                                true
                        }
                    val camera =
                        cameras.firstOrNull {
                            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_BACK
                        } ?: cameras.firstOrNull() ?: error("No flashlight is available on this device.")
                    manager.setTorchMode(camera, args.getBoolean("enabled"))
                    JSONObject().put("flashlight_enabled", args.getBoolean("enabled"))
                }

                "vibrate" -> {
                    val duration = args.getInt("milliseconds")
                    require(duration in 1..2000) { "Use a duration from 1 to 2,000 milliseconds." }
                    val delay = args.optInt("delay_ms", 0).coerceIn(0, 5000)
                    val repeat = args.optInt("repeat", 0).coerceIn(0, 10)
                    val gap = args.optInt("gap_ms", 0).coerceIn(0, 2000)
                    val vibrator = activity.getSystemService(Vibrator::class.java)
                    check(vibrator.hasVibrator()) { "This device has no vibrator." }
                    val timings =
                        buildList {
                            add(delay.toLong())
                            repeat(repeat + 1) {
                                if (it > 0) add(gap.toLong())
                                add(duration.toLong())
                            }
                        }.toLongArray()
                    val effect = VibrationEffect.createWaveform(timings, -1)
                    vibrator.vibrate(effect)
                    JSONObject()
                        .put("vibrated_ms", duration)
                        .put("delay_ms", delay)
                        .put("repeat", repeat)
                        .put("gap_ms", gap)
                }

                "list_apps" -> {
                    val query = args.optString("query").also { require(it.length <= 100) }
                    val apps =
                        activity.packageManager
                            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                            .map {
                                it.loadLabel(activity.packageManager).toString() to it.activityInfo.packageName
                            }.distinctBy { it.second }
                            .sortedBy { it.first.lowercase() }
                            .filter { it.first.contains(query, true) || it.second.contains(query, true) }
                    JSONObject()
                        .put(
                            "apps",
                            JSONArray().apply {
                                apps
                                    .take(
                                        100,
                                    ).forEach { put(JSONObject().put("label", it.first).put("package", it.second)) }
                            },
                        ).put(
                            "truncated",
                            apps.size > 100,
                        )
                }

                "open_app" -> {
                    val pkg = args.getString("package")
                    require(
                        pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")),
                    ) { "Use an exact package name." }
                    launch(
                        activity,
                        activity.packageManager.getLaunchIntentForPackage(pkg)
                            ?: error("This app is not installed or has no visible launcher."),
                    )
                }

                "open_url" -> {
                    openUri(activity, args.getString("url"), project)
                }

                "open_settings" -> {
                    val action =
                        when (args.getString("page")) {
                            "wifi" -> Settings.ACTION_WIFI_SETTINGS
                            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                            "sound" -> Settings.ACTION_SOUND_SETTINGS
                            "display" -> Settings.ACTION_DISPLAY_SETTINGS
                            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                            "app" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            else -> error("Unsupported settings page.")
                        }
                    launch(
                        activity,
                        Intent(action).apply {
                            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                                data = Uri.parse("package:${activity.packageName}")
                            }
                        },
                    )
                }

                "set_screen_brightness" -> {
                    val reset = args.optBoolean("use_system_default")
                    val percent = args.optInt("percent", 50)
                    require(reset || percent in 1..100) { "Use a brightness from 1 to 100." }
                    activity.window.attributes =
                        activity.window.attributes.apply { screenBrightness = if (reset) -1f else percent / 100f }
                    JSONObject()
                        .put(
                            "scope",
                            "current Hydra window",
                        ).put("system_default", reset)
                        .put("percent", if (reset) JSONObject.NULL else percent)
                }

                "get_location" -> {
                    currentLocation(activity)
                }

                "set_alarm" -> {
                    val hour = args.getInt("hour")
                    val minute = args.getInt("minute")
                    require(hour in 0..23 && minute in 0..59)
                    launch(
                        activity,
                        Intent(
                            AlarmClock.ACTION_SET_ALARM,
                        ).putExtra(
                            AlarmClock.EXTRA_HOUR,
                            hour,
                        ).putExtra(
                            AlarmClock.EXTRA_MINUTES,
                            minute,
                        ).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label").take(200))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
                    )
                }

                "set_timer" -> {
                    val seconds = args.getInt("seconds")
                    require(seconds in 1..86400)
                    launch(
                        activity,
                        Intent(
                            AlarmClock.ACTION_SET_TIMER,
                        ).putExtra(
                            AlarmClock.EXTRA_LENGTH,
                            seconds,
                        ).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label").take(200))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
                    )
                }

                "dial_number" -> {
                    val number = args.getString("number")
                    require(
                        number.matches(Regex("\\+?[0-9 ()-]{3,30}")),
                    ) { "Use a phone number without USSD or control codes." }
                    launch(activity, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
                }

                "compose_email" -> {
                    val to = args.getString("to")
                    val subject = args.getString("subject")
                    val body = args.getString("body")
                    val validAddress = to.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
                    require(
                        validAddress &&
                            to.length <= 254 &&
                            subject.length <= 200 &&
                            body.length <= 4000,
                    ) {
                        "Use one email address, a subject up to 200 characters, and a body up to 4,000 characters."
                    }
                    launch(
                        activity,
                        Intent(
                            Intent.ACTION_SENDTO,
                            Uri.fromParts("mailto", to, null),
                        ).putExtra(
                            Intent.EXTRA_EMAIL,
                            arrayOf(to),
                        ).putExtra(Intent.EXTRA_SUBJECT, subject)
                            .putExtra(Intent.EXTRA_TEXT, body),
                    )
                }

                "create_calendar_event" -> {
                    val start = OffsetDateTime.parse(args.getString("start_time")).toInstant().toEpochMilli()
                    val end = OffsetDateTime.parse(args.getString("end_time")).toInstant().toEpochMilli()
                    val title = args.getString("title")
                    val description = args.optString("description")
                    require(end > start && title.isNotBlank() && title.length <= 200 && description.length <= 2000)
                    launch(
                        activity,
                        Intent(
                            Intent.ACTION_INSERT,
                            CalendarContract.Events.CONTENT_URI,
                        ).putExtra(
                            CalendarContract.Events.TITLE,
                            title,
                        ).putExtra(
                            CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                            start,
                        ).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                            .putExtra(CalendarContract.Events.DESCRIPTION, description),
                    )
                }

                "copy_to_clipboard" -> {
                    val text = args.getString("text")
                    require(text.length <= 8000)
                    activity
                        .getSystemService(
                            ClipboardManager::class.java,
                        ).setPrimaryClip(ClipData.newPlainText("Hydra", text))
                    JSONObject().put("copied_characters", text.length)
                }

                else -> {
                    error("Unknown device tool.")
                }
            }
        return WorkToolResult(result.toString())
    }

    private fun volume(
        audio: AudioManager,
        stream: Int,
    ) = JSONObject().put("level", audio.getStreamVolume(stream)).put("maximum", audio.getStreamMaxVolume(stream))

    private fun launch(
        activity: Activity,
        intent: Intent,
    ): JSONObject {
        try {
            activity.startActivity(intent)
        } catch (
            _: android.content.ActivityNotFoundException,
        ) {
            error("No installed app can handle this action.")
        }
        return JSONObject()
            .put(
                "status",
                "opened",
            ).put("action", intent.action)
            .put("note", "Opened the system or app UI. Do not assume the user completed or saved the action.")
    }

    /** Schemes that would bypass Hydra's approval model or execute script in another app's context. */
    private val blockedSchemes = setOf("content", "intent", "javascript", "data", "blob", "about", "package")

    private fun openUri(
        activity: Activity,
        raw: String,
        project: ProjectFileSource?,
    ): JSONObject {
        require(raw.isNotBlank() && raw.length <= 2048) { "Use a URL of at most 2,048 characters." }
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase(Locale.US)
        when (scheme) {
            null, "" -> {
                error("Include the scheme, e.g. https://, file:/// or youtube://.")
            }

            "http", "https" -> {
                require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Use an HTTP(S) URL without credentials." }
            }

            "file" -> {
                return openFile(activity, uri, project, raw)
            }

            in blockedSchemes -> {
                error("Hydra cannot open $scheme:// links directly for safety.")
            }

            else -> {
                require(
                    !uri.host.isNullOrBlank() ||
                        uri.schemeSpecificPart
                            .orEmpty()
                            .trimStart('/')
                            .isNotBlank(),
                ) {
                    "That link is missing its destination."
                }
            }
        }
        return launch(activity, Intent(Intent.ACTION_VIEW, uri))
    }

    /**
     * Opens a local file for viewing: Hydra's own folders and readable shared-storage files
     * map straight to a FileProvider content URI; a selected project file that raw paths
     * cannot reach is copied out through the SAF grant first.
     */
    private fun openFile(
        activity: Activity,
        uri: Uri,
        project: ProjectFileSource?,
        raw: String,
    ): JSONObject {
        val rawPath =
            if (uri.authority != null && uri.authority!!.matches(Regex("^[A-Za-z]:.*"))) {
                "${uri.authority}${uri.path.orEmpty()}"
            } else {
                uri.path.orEmpty().ifEmpty { raw.removePrefix("file://").removePrefix("file:") }
            }
        val normalized =
            if (rawPath.startsWith("/") && rawPath.length > 2 && rawPath[2] == ':') {
                rawPath.substring(1)
            } else {
                rawPath
            }
        require(normalized.isNotBlank()) { "That file:/// link has no path." }
        val file = java.io.File(normalized)
        require(!file.path.contains('\u0000')) { "Use a normal file path." }
        val context = activity
        val authority = "${context.packageName}.fileprovider"

        fun contentFor(target: java.io.File): Uri =
            runCatching {
                androidx.core.content.FileProvider
                    .getUriForFile(context, authority, target)
            }.getOrElse {
                val externalRoot =
                    runCatching {
                        android.os.Environment
                            .getExternalStorageDirectory()
                            ?.canonicalPath
                            ?.trimEnd('/', '\\')
                    }.getOrNull().orEmpty()
                val targetPath = target.canonicalPath
                if (externalRoot.isNotEmpty() && targetPath.startsWith(externalRoot)) {
                    val rel = targetPath.removePrefix(externalRoot).trimStart('/', '\\').replace('\\', '/')
                    Uri.parse("content://$authority/shared-storage/$rel")
                } else if (target.isFile && target.canRead()) {
                    Uri.parse("content://$authority/shared-storage/${target.name}")
                } else {
                    error("Hydra can only open files inside shared storage or its own app folders.")
                }
            }
        val appDirs =
            listOfNotNull(
                context.filesDir,
                context.cacheDir,
                context.getExternalFilesDir(null),
                context.getExternalCacheDir(),
            )
        val projectRoot = project?.directory?.trimEnd('/').orEmpty()
        val contentUri =
            when {
                file.isFile && appDirs.any { file.canonicalPath.startsWith(it.canonicalPath) } -> {
                    contentFor(file)
                }

                file.isFile && file.canRead() -> {
                    contentFor(file)
                }

                project != null && projectRoot.isNotEmpty() && file.canonicalPath.startsWith("$projectRoot/") -> {
                    contentFor(copyFromProject(activity, project, file))
                }

                file.exists() -> {
                    error("Hydra doesn't have access to that file. Move it into the selected project folder.")
                }

                else -> {
                    error("That file does not exist. Create it with the project or shell tools first.")
                }
            }
        val mime =
            runCatching { context.contentResolver.getType(contentUri) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: (
                    android.webkit.MimeTypeMap
                        .getSingleton()
                        .getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
                )
        return launch(
            activity,
            Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun copyFromProject(
        activity: Activity,
        project: ProjectFileSource,
        file: java.io.File,
    ): java.io.File {
        val relative = file.canonicalPath.removePrefix(project.directory.trimEnd('/') + "/")
        val stream = project.open(relative) ?: error("That file does not exist in the selected project.")
        val stagedDir =
            java.io.File(activity.cacheDir, "opened").apply {
                listFiles()?.forEach { it.delete() }
                mkdirs()
            }
        val staged = java.io.File(stagedDir, file.name.ifBlank { "file" })
        stream.use { input ->
            staged.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 52_428_800L) { "That file is larger than 50 MB." }
                    output.write(buffer, 0, count)
                }
            }
        }
        require(staged.isFile && staged.length() > 0) { "Could not open that project file." }
        return staged
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context): JSONObject {
        val manager = context.getSystemService(LocationManager::class.java)
        check(
            LocationManagerCompat.isLocationEnabled(manager),
        ) { "Device location is turned off. Ask the user to enable it." }
        check(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            "Approximate network location is unavailable on this device."
        }
        val location =
            withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<android.location.Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        LocationManager.NETWORK_PROVIDER,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { value -> if (continuation.isActive) continuation.resume(value) }
                }
            } ?: error("No location fix was available within 15 seconds. Do not invent a location.")
        return JSONObject()
            .put(
                "latitude",
                location.latitude,
            ).put(
                "longitude",
                location.longitude,
            ).put("accuracy_meters", location.accuracy)
            .put("recorded_at", location.time)
            .put("approximate", true)
    }
}
