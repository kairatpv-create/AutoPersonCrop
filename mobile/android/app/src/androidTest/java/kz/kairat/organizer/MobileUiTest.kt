package kz.kairat.organizer

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Compose rendering and navigation, on synthetic emulator data only. */
@RunWith(AndroidJUnit4::class)
class MobileUiTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun prepareLocale(lang:String){
        instrumentation.runOnMainSync{
            context.getSystemService(android.app.LocaleManager::class.java).applicationLocales=android.os.LocaleList.forLanguageTags(AppStrings.localeTag(lang))
        }
        instrumentation.waitForIdleSync()
    }
    private fun waitFor(label:String):AccessibilityNodeInfo {
        val deadline=android.os.SystemClock.uptimeMillis()+15000
        while(android.os.SystemClock.uptimeMillis()<deadline){
            fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo?{
                if(node.text?.toString()?.equals(label,ignoreCase=true)==true&&node.isVisibleToUser)return node
                for(i in 0 until node.childCount)node.getChild(i)?.let{find(it)}?.let{return it}
                return null
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let{find(it)}?.let{return it}
            Thread.sleep(100)
        }
        val name="failure-"+label.replace(Regex("[^a-zA-Z0-9]"),"_").take(40)
        runCatching{screenshot(name)}
        val nodes=org.json.JSONArray()
        fun dump(node:AccessibilityNodeInfo,depth:Int){
            nodes.put(org.json.JSONObject().put("depth",depth).put("class",node.className?.toString())
                .put("text",node.text?.toString()).put("description",node.contentDescription?.toString())
                .put("visible",node.isVisibleToUser).put("clickable",node.isClickable))
            for(i in 0 until node.childCount)node.getChild(i)?.let{dump(it,depth+1)}
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let{dump(it,0)}
        File(context.getExternalFilesDir(null),"synthetic-ui").apply{mkdirs()}.resolve("$name.json").writeText(nodes.toString(2))
        error("Visible UI label missing: $label")
    }
    private fun click(label:String){
        var node:AccessibilityNodeInfo?=waitFor(label)
        while(node!=null){if(node.isClickable&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;node=node.parent}
        error("UI label could not be clicked: $label")
    }
    private fun screenshot(name:String){
        instrumentation.waitForIdleSync()
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null),"synthetic-ui").apply{mkdirs()}.resolve("$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    private fun localBackup(db:NoteDatabase,settings:SettingsStore):String =
        org.json.JSONObject(BackupManager.createBackup(db,settings)).let{it.remove("createdAt");it.toString()}

    @Test fun savedCloudSessionDoesNotSendDataOrExposeAccountControls(){
        check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1")
        AttachmentStore.initialize(context)
        val db=NoteDatabase(context);val settings=SettingsStore(context)
        val originalToken=settings.syncIdToken
        settings.syncIdToken="synthetic-disabled-cloud-token";settings.setPinProtectionEnabled(false)
        val credentials=File(context.noBackupFilesDir,"protected-settings/credentials.bin").readBytes()
        val requests=java.util.concurrent.atomic.AtomicInteger()
        CloudSync.testTransport={_,_,_,_->requests.incrementAndGet();throw java.io.IOException("Unexpected cloud request")}
        try{
            for(lang in listOf("ru","en")){
                settings.language=lang;settings.fontScale=.85f;settings.buttonFontScale=.85f;settings.darkTheme=false
                prepareLocale(lang)
                val before=localBackup(db,settings)
                val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                try{
                    click(AppStrings.t(lang,"settings"))
                    waitFor(AppStrings.t(lang,"save_backup"));waitFor(AppStrings.t(lang,"restore"))
                    waitFor(SyncSnapshot.message(lang,"Данные и приватность","Data and privacy"))
                    fun checkNodes(node:AccessibilityNodeInfo){
                        val forbidden=listOf("Аккаунт и синхронизация","Account and sync","Email","Архив","Archive")
                        assertFalse("Removed setting remains: ${node.text}",node.isVisibleToUser&&forbidden.any{it.equals(node.text?.toString(),ignoreCase=true)})
                        for(i in 0 until node.childCount)node.getChild(i)?.let{checkNodes(it)}
                    }
                    checkNodes(checkNotNull(instrumentation.uiAutomation.rootInActiveWindow))
                    screenshot("$lang-settings-local-only")
                    Thread.sleep(1000)
                    assertEquals("Opening the app must not contact the sync server",0,requests.get())
                    assertEquals(before,localBackup(db,settings))
                    assertArrayEquals(credentials,File(context.noBackupFilesDir,"protected-settings/credentials.bin").readBytes())
                }finally{instrumentation.runOnMainSync{activity.finish()}}
            }
        }finally{CloudSync.testTransport=null;settings.syncIdToken=originalToken;db.close()}
    }

    @Test fun zipBackupCanBeSharedAndBothSavedFormatsRemainReadable(){
        check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1")
        AttachmentStore.initialize(context)
        val db=NoteDatabase(context);val settings=SettingsStore(context)
        val files=listOf(File(context.cacheDir,"synthetic-share-backup.zip"),File(context.cacheDir,"synthetic-legacy-backup.json"))
        try{
            val json=BackupManager.createBackup(db,settings)
            val root=org.json.JSONObject(json)
            assertTrue(root.getJSONArray("notes").length()>0)
            assertTrue(root.getJSONArray("money").length()>0)
            assertTrue(root.getJSONArray("attachments").length()>0)
            val uris=files.map{androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",it)}
            BackupManager.writeArchiveToUri(context,uris[0],json)
            BackupManager.writeToUri(context,uris[1],json)
            assertEquals(json,BackupManager.readFromUri(context,uris[0]))
            assertEquals(json,BackupManager.readFromUri(context,uris[1]))
            val share=BackupSharing.intent(uris[0],"en")
            assertEquals(Intent.ACTION_SEND,share.action)
            assertEquals("application/zip",share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals(uris[0],share.clipData!!.getItemAt(0).uri)
            val stream=checkNotNull(share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            assertEquals(json,BackupManager.readFromUri(context,stream))
            assertFalse(share.hasExtra(Intent.EXTRA_EMAIL))
            assertEquals(json,BackupManager.createBackup(db,settings).let{current->
                org.json.JSONObject(current).put("createdAt",root.getLong("createdAt")).toString(2)
            })
        }finally{files.forEach{it.delete()};db.close()}
    }

    @Test fun pinLocksOnBackgroundAndKeepsTheCurrentScreen(){
        check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1")
        val settings=SettingsStore(context);settings.language="en";settings.setPin("2580");settings.setPinProtectionEnabled(false)
        prepareLocale("en")
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try{
            click(AppStrings.t("en","money"));waitFor(AppStrings.t("en","income"));settings.setPinProtectionEnabled(true)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use{fd->java.io.FileInputStream(fd.fileDescriptor).use{it.readBytes()}}
            Thread.sleep(500)
            instrumentation.runOnMainSync{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
            waitFor(AppStrings.t("en","unlock"))
            instrumentation.runOnMainSync{assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)}
            fun field(node:AccessibilityNodeInfo):AccessibilityNodeInfo?{
                if(node.className=="android.widget.EditText")return node
                for(i in 0 until node.childCount)node.getChild(i)?.let{field(it)}?.let{return it}
                return null
            }
            val edit=checkNotNull(field(checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)))
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"2580")}))
            click(AppStrings.t("en","confirm"));waitFor(AppStrings.t("en","income"))
        }finally{settings.setPinProtectionEnabled(false);instrumentation.runOnMainSync{activity.finish()}}
    }
    @Test fun russianEnglishNavigationAndLargeTextRender(){
        check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1")
        for((lang,dark,scale) in listOf(Triple("ru",false,1f),Triple("en",true,1.45f))){
            val settings=SettingsStore(context);settings.language=lang;settings.darkTheme=dark;settings.fontScale=scale;settings.buttonFontScale=scale
            prepareLocale(lang)
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try{
                waitFor(AppStrings.t(lang,"notes"));screenshot("$lang-notes")
                click(AppStrings.t(lang,"money"));waitFor(AppStrings.t(lang,"income"));screenshot("$lang-finances")
                click(AppStrings.t(lang,"projects"));waitFor("Synthetic upgrade project");screenshot("$lang-projects")
                click(AppStrings.t(lang,"settings"));waitFor(AppStrings.t(lang,"button_font"));screenshot("$lang-settings")
            }finally{instrumentation.runOnMainSync{activity.finish()}}
        }
    }
}
