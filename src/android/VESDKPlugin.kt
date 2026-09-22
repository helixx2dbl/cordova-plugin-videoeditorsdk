package com.photoeditorsdk.cordova

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import ly.img.android.IMGLY
import ly.img.android.VESDK
import ly.img.android.pesdk.VideoEditorSettingsList
import ly.img.android.pesdk.backend.model.EditorSDKResult
import ly.img.android.pesdk.backend.model.state.LoadSettings
import ly.img.android.pesdk.backend.model.state.manager.SettingsList
import ly.img.android.pesdk.backend.encoder.Encoder
import ly.img.android.pesdk.backend.model.state.VideoCompositionSettings
import ly.img.android.pesdk.kotlin_extension.continueWithExceptions
import ly.img.android.pesdk.ui.activity.EditorBuilder
import ly.img.android.pesdk.utils.MainThreadRunnable
import ly.img.android.pesdk.utils.SequenceRunnable
import ly.img.android.pesdk.utils.UriHelper
import ly.img.android.sdk.config.*
import ly.img.android.serializer._3.IMGLYFileReader
import ly.img.android.serializer._3.IMGLYFileWriter
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** VESDKPlugin */
class VESDKPlugin : CordovaPlugin() {

    companion object {
        // This number must be unique. It is public to allow client code to change it if the same value is used elsewhere.
        var EDITOR_RESULT_ID = 29065
    }

    /** The callback used for the plugin. */
    private var callback: CallbackContext? = null

    /** The currently used configuration. */
    private var currentConfig: Configuration? = null

    /** TSY fork: java.io.tmpdir as it was before we redirected it for the editor session. */
    private var previousTmpDir: String? = null

    override fun onStart() {
        IMGLY.initSDK(this.cordova.activity)
        IMGLY.authorize()
    }

    @Throws(JSONException::class)
    override fun execute(action: String, data: JSONArray, callbackContext: CallbackContext): Boolean {
        return if (action == "present") { // Extract image path
            val options = data.getJSONObject(0)
            val filepath = options.optString("path", "")
            val configuration = options.optString("configuration", "{}")
            val serialization = options.optString("serialization", null)

            val config: Map<String, Any> = Gson().fromJson(configuration, object : TypeToken<Map<String, Any>>() {}.type)
            present(filepath, config, serialization, callbackContext)
            true
        } else if (action == "presentComposition") {
            val options = data.getJSONObject(0)
            val videos = options.optJSONArray("videos")
            val size = options.optString("size", "")
            val configuration = options.optString("configuration", "{}")
            val serialization = options.optString("serialization", null)

            val videoClips = if (videos != null) {
                Array(videos.length()) { videos.getString(it) }
            } else {
                arrayOf<String>()
            }

            val gson = Gson()
            val config: Map<String, Any> = gson.fromJson(configuration, object : TypeToken<Map<String, Any>>() {}.type)
            val videoSize: Map<String, Any>? = gson.fromJson(size, object : TypeToken<Map<String, Any>?>() {}.type)
            presentComposition(videoClips, config, serialization, videoSize, callbackContext)
            true
        } else if (action == "unlockWithLicense") {
            val license = data[0].toString()
            unlockWithLicense(license)
            true
        } else {
            false
        }
    }

    /**
     * Unlocks the SDK with a stringified license.
     *
     * @param license The license as a *String*.
     */
    fun unlockWithLicense(license: String) {
        val jsonString = this.cordova.activity.assets.open(license).bufferedReader().use {
            it.readText()
        }

        VESDK.initSDKWithLicenseData(jsonString)
        IMGLY.authorize()
    }

    /**
     * Configures and presents the editor.
     *
     * @param filepath The video source as *String* which should be loaded into the editor.
     * @param config The *Configuration* to configure the editor with as if any.
     * @param serialization The serialization to load into the editor if any.
     * @param callbackContext The *CallbackContext* used to communicate with the plugin.
     */
    private fun present(
        filepath: String,
        config: Map<String, Any>,
        serialization: String?,
        callbackContext: CallbackContext
    ) {
        callback = callbackContext
        IMGLY.authorize()
        val configuration = ConfigLoader.readFrom(config)
        val settingsList = VideoEditorSettingsList(configuration.export?.serialization?.enabled == true)
        configuration.applyOn(settingsList)
        currentConfig = configuration

        settingsList.configure<LoadSettings> { loadSettings ->
            loadSettings.source = retrieveURI(filepath)
        }

        readSerialisation(settingsList, serialization)
        startEditor(settingsList)    
    }

    /**
     * Configures and presents the editor.
     *
     * @param videos The video sources as *List<String>* which should be loaded into the editor.
     * @param config The *Configuration* to configure the editor with as if any.
     * @param serialization The serialization to load into the editor if any.
     * @param size The size of the video composition.
     * @param callbackContext The *CallbackContext* used to communicate with the plugin.
     */
    private fun presentComposition(
        videos: Array<String>?,
        config: Map<String, Any>,
        serialization: String?,
        size: Map<String, Any>?,
        callbackContext: CallbackContext
    ) {
        callback = callbackContext
        IMGLY.authorize()

        // Set the video size as the default source.
        var source = resolveSize(size)

        val configuration = ConfigLoader.readFrom(config)
        val settingsList = VideoEditorSettingsList(configuration.export?.serialization?.enabled == true)
        configuration.applyOn(settingsList)
        currentConfig = configuration

        if (videos != null && videos.count() > 0) {
            if (source == null) {
                if (size != null) {
                    throw RuntimeException("Invalid video size: width and height must be greater than zero.")
                }
                source = retrieveURI(videos.first())
            }

            settingsList.configure<VideoCompositionSettings> { loadSettings ->
                videos.forEach {
                    val resolvedSource = retrieveURI(it)
                    loadSettings.addCompositionPart(VideoCompositionSettings.VideoPart(resolvedSource))
                }
            }
        } else {
            // If the source (= video size) is null we can not open the editor.
            if (source == null) {
                throw RuntimeException("The editor requires a valid size when initialized without a video.")
            }
        }

        settingsList.configure<LoadSettings> {
            it.source = source
        }

        readSerialisation(settingsList, serialization)
        startEditor(settingsList)
    }

    /**
     * Starts the editor.
     * @param settingsList The *VideoEditorSettingsList* used to configure the editor.
     */
    private fun startEditor(settingsList: VideoEditorSettingsList) {
        val currentActivity = cordova.activity ?: throw RuntimeException("Can't start the Editor because there is no current activity")
        cordova.setActivityResultCallback(this)
        redirectTmpDir()
        MainThreadRunnable {
            EditorBuilder(currentActivity)
                .setSettingsList(settingsList)
                .startActivityForResult(currentActivity, EDITOR_RESULT_ID, arrayOfNulls(0))
            settingsList.release()
        }()
    }

    // TSY fork: clips added with the + inside the editor are copied by the sdk via
    // File.createTempFile("uriCache", ".tmp"), which lands in java.io.tmpdir = cache/. android trims
    // cache/ under storage pressure, so during a long edit those copies vanished and the export failed
    // with "Couldn't load file". For the editor's lifetime we point tmpdir at a folder under files/,
    // which is never trimmed. android's createTempFile re-reads the property on every call, so
    // restoring it in onActivityResult bounds the redirect to exactly the editor session. The sdk marks
    // its copies deleteOnExit, which never fires on android, so we wipe the folder ourselves.
    private fun editorTmpDir(): File {
        return File(cordova.activity.filesDir, "vesdk_tmp")
    }

    private fun redirectTmpDir() {
        val dir = editorTmpDir()
        wipeTmpDir() // leftovers from a process kill mid-edit
        dir.mkdirs()
        previousTmpDir = System.getProperty("java.io.tmpdir")
        System.setProperty("java.io.tmpdir", dir.absolutePath)
    }

    private fun restoreTmpDir() {
        val previous = previousTmpDir
        if (previous != null) {
            System.setProperty("java.io.tmpdir", previous)
            previousTmpDir = null
        }
    }

    /** How many files the sdk copied into our tmp folder this session, and their total size. */
    private fun tmpDirStats(): Pair<Int, Long> {
        val files = editorTmpDir().listFiles() ?: return Pair(0, 0L)
        var count = 0
        var bytes = 0L
        for (file in files) {
            if (file.isFile) {
                count++
                bytes += file.length()
            }
        }
        return Pair(count, bytes)
    }

    private fun wipeTmpDir() {
        val files = editorTmpDir().listFiles() ?: return
        for (file in files) {
            if (file.isFile) {
                file.delete()
            }
        }
    }

    /**
     * Converts a string into a usable *Uri*.
     * @param source The source of the video as a *String*.
     * @return The converted source as a *Uri*.
     */
    private fun retrieveURI(source: String) : Uri {
        return if (source.startsWith("data:")) {
            UriHelper.createFromBase64String(source.substringAfter("base64,"))
        } else {
            val potentialFile = continueWithExceptions { File(source) }
            if (potentialFile?.exists() == true) {
                Uri.fromFile(potentialFile)
            } else {
                ConfigLoader.parseUri(source)
            }
        }
    }

    /**
     * Resolves a size for the video composition into a usable *Uri?*.
     * @param size The desired size of the video composition as a *Map<String, Any>?*.
     * @return The converted video size as a *Uri?*.
     */
    private fun resolveSize(size: Map<String, Any>?) : Uri? {
        val height = size?.get("height") as? Double ?: 0.0
        val width = size?.get("width") as? Double ?: 0.0
        if (height == 0.0 || width == 0.0) {
            return null
        }
        return LoadSettings.compositionSource(width.toInt(), height.toInt(), 60)
    }

    /**
     * Called when the editor has succeeded exporting the video.
     * @param intent The *Intent?*.
     */
    private fun success(intent: Intent?) {
        val data = try {
            intent?.let { EditorSDKResult(it) }
        } catch (e: EditorSDKResult.NotAnImglyResultException) {
            null
        } ?: return // If data is null the result is not from us.

        SequenceRunnable("Export Done") {
            val sourcePath = data.sourceUri
            val resultPath = data.resultUri

            val serializationConfig = currentConfig?.export?.serialization

            val serialization: Any? = if (serializationConfig?.enabled == true) {
                val settingsList = data.settingsList
                skipIfNotExists {
                    settingsList.let { settingsList ->
                        if (serializationConfig.embedSourceImage == true) {
                            Log.i("ImgLySdk", "EmbedSourceImage is currently not supported by the Android SDK")
                        }
                        when (serializationConfig.exportType) {
                            SerializationExportType.FILE_URL -> {
                                val uri = serializationConfig.filename?.let { 
                                    Uri.parse("$it.json")
                                } ?: Uri.fromFile(File.createTempFile("serialization-" + UUID.randomUUID().toString(), ".json"))
                                Encoder.createOutputStream(uri).use { outputStream ->
                                    IMGLYFileWriter(settingsList).writeJson(outputStream)
                                }
                                uri.toString()
                            }
                            SerializationExportType.OBJECT -> {
                                IMGLYFileWriter(settingsList).writeJsonAsString()
                            }
                        }
                    }
                } ?: run {
                    Log.i("ImgLySdk", "You need to include 'backend:serializer' Module, to use serialisation!")
                    null
                }
                settingsList.release()
            } else {
                null
            }
            val result = createResult(resultPath, sourcePath?.path != resultPath?.path, serialization)
            callback?.success(result)
            wipeTmpDir()

        }()
    }

    /**
     * Reads the serialization to restore a previous state in the editor.
     * @param settingsList The *SettingsList*.
     * @param serialization The serialization which holds the previous state.
     */
    private fun readSerialisation(settingsList: SettingsList, serialization: String?) {
        if (serialization != null) {
            skipIfNotExists {
                IMGLYFileReader(settingsList).also {
                    it.readJson(serialization, false)
                }
            }
        }
    }

    /**
     * Converts the editor result into a readable *JSONObject*.
     * @param video The output source of the video.
     * @param hasChanges Whether any export operations have been applied to the video.
     * @param serialization The serialization which stores the current state.
     * @return The converted *JSONObject*.
     */
    private fun createResult(video: Uri?, hasChanges: Boolean, serialization: Any?): JSONObject {
        val result = JSONObject()
        result.put("video", video)
        result.put("hasChanges", hasChanges)
        result.put("serialization", serialization)
        // TSY fork: diagnostics for the app's error report - clips added with the + inside the editor
        val stats = tmpDirStats()
        result.put("tmpClipCount", stats.first)
        result.put("tmpClipBytes", stats.second)
        return result
    }

    override fun onRestoreStateForActivityResult(state: Bundle?, callbackContext: CallbackContext) {
        this.callback = callbackContext
    }

    // TSY fork: data MUST be nullable. Cordova cancels a still-pending activity by calling
    // onActivityResult(requestCode, RESULT_CANCELED, null) from setActivityResultCallback(). With a
    // non-null Intent, kotlin's parameter check threw inside whichever plugin was starting the next
    // activity (the camera picker), and because that throw lands before cordova reassigns the
    // callback, every later pick failed the same way until the app was force-quit.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == EDITOR_RESULT_ID) {
            restoreTmpDir() // the editor is gone, so nothing else must land in our folder
            when (resultCode) {
                Activity.RESULT_OK -> success(data)
                Activity.RESULT_CANCELED -> {
                    // TSY fork: upstream returned a bare null here. A result with video = null still reads as
                    // a cancel to the app, and carries the tmp-clip diagnostics along.
                    callback?.success(createResult(null, false, null))
                    wipeTmpDir()
                }
                else -> {
                    callback?.error("Media error (code $resultCode)")
                    wipeTmpDir()
                }
            }
        }
    }
}
