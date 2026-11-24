package app.agentichydra

import android.app.Activity
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceToolsTest {
    @Test
    fun deviceStatusAndDraftActionsUseAndroidApisWithoutSendingOrCalling() =
        runBlocking {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            try {
                val info = JSONObject(DeviceTools.execute(activity, "device_info", JSONObject()).output)
                assertTrue(info.has("api_level"))
                assertFalse(info.has("serial"))
                DeviceTools.execute(activity, "dial_number", JSONObject().put("number", "+1 555 0100"))
                assertEquals(Intent.ACTION_DIAL, shadowOf(activity).nextStartedActivity.action)
                DeviceTools.execute(
                    activity,
                    "compose_email",
                    JSONObject().put("to", "demo@example.com").put("subject", "Draft").put("body", "Hello"),
                )
                assertEquals(Intent.ACTION_SENDTO, shadowOf(activity).nextStartedActivity.action)
                DeviceTools.execute(activity, "set_timer", JSONObject().put("seconds", 60))
                assertEquals(AlarmClock.ACTION_SET_TIMER, shadowOf(activity).nextStartedActivity.action)
                assertTrue(
                    runCatching {
                        DeviceTools.execute(
                            activity,
                            "dial_number",
                            JSONObject().put("number", "*#123#"),
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        DeviceTools.execute(
                            activity,
                            "open_url",
                            JSONObject().put("url", "intent://anything"),
                        )
                    }.isFailure,
                )
            } finally {
                controller.pause().stop().destroy()
            }
        }

    @Test
    fun openUrlHandlesDeepLinksAndLocalFilesSafely() =
        runBlocking {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            try {
                // App deep links open in the handler app.
                DeviceTools.execute(activity, "open_url", JSONObject().put("url", "youtube://watch?v=abc"))
                assertEquals("youtube", shadowOf(activity).nextStartedActivity.data!!.scheme)
                // A local file inside Hydra's folders is served read-only through the FileProvider.
                val file = java.io.File(activity.getExternalFilesDir(null)!!, "hello.md").apply { writeText("# hi") }
                val result =
                    JSONObject(
                        DeviceTools
                            .execute(
                                activity,
                                "open_url",
                                JSONObject().put(
                                    "url",
                                    "file://" + file.absolutePath,
                                ),
                            ).output,
                    )
                assertEquals("opened", result.getString("status"))
                val started = shadowOf(activity).nextStartedActivity
                assertEquals("content", started.data!!.scheme)
                assertEquals("app.agentichydra.fileprovider", started.data!!.authority)
                assertTrue(started.type!!.isNotBlank())
                assertEquals(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                // Unsafe or unusable targets stay blocked.
                assertTrue(
                    runCatching {
                        DeviceTools.execute(
                            activity,
                            "open_url",
                            JSONObject().put("url", "content://settings"),
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        DeviceTools.execute(
                            activity,
                            "open_url",
                            JSONObject().put("url", "file:///definitely/missing/file.txt"),
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        DeviceTools.execute(
                            activity,
                            "open_url",
                            JSONObject().put("url", "youtube://"),
                        )
                    }.isFailure,
                )
            } finally {
                controller.pause().stop().destroy()
            }
        }

    @Test
    fun toolCatalogHasUniqueNamesAndEverySchemaMatchesAnExecutableTool() {
        val schema = WorkTools.schema()
        val names =
            (0 until schema.length()).map {
                schema
                    .getJSONObject(
                        it,
                    ).getJSONObject("function")
                    .getString("name")
            }
        assertEquals(56, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals(WorkTools.names.toSet(), names.toSet())
    }

    @Test
    fun projectHtmlOpensWithAReadOnlyAndroidIntentTargetedAtChrome() = runBlocking {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val project = object : ProjectFileSource {
                override val directory = "/storage/emulated/0/project"
                override fun open(relativePath: String): java.io.InputStream? = null
                override fun documentUri(relativePath: String): android.net.Uri {
                    assertEquals("web/index.html", relativePath)
                    return android.net.Uri.parse("content://example.document/index.html")
                }
            }
            DeviceTools.execute(
                activity,
                "open_file",
                JSONObject().put("path", "web/index.html").put("package", "com.android.chrome"),
                project,
            )
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("com.android.chrome", intent.`package`)
            assertEquals("text/html", intent.type)
            assertEquals("content", intent.data?.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
