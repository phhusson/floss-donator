package me.phh.flossdonator

import android.app.usage.UsageStatsManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.JsonReader
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

import kotlinx.android.synthetic.main.activity_main.*
import java.util.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
    }

    data class AppFlags(
        val additionalFeatures: Boolean = false,
        val reducedFeatures: Boolean = false,
        val externalContent: Boolean = false,
        val instantMessaging: Boolean = false,
        val usefulInBackground: Boolean = false)
    data class AppLocalInfo(
        val foregroundTime: Long = 0L,
        val installer: String = "")

    data class AppInfo(
        val flags: AppFlags,
        val localInfo: AppLocalInfo
    )


    fun refreshStats() {
        val usageManager = getSystemService(UsageStatsManager::class.java)!!

        val now = Date()

        val cal = Calendar.getInstance()
        cal.set(1900 + now.year, now.month, 0)

        val firstThisMonth = cal.time
        if(now.month == 0)
            cal.set(1900 + now.year-1, 11, 0)
        else
            cal.set(1900 + now.year, now.month-1, 0)
        val firstPreviousMonth = cal.time

        Log.e("FLOSS-Donator", "Asking for stats from ${firstPreviousMonth.time} to ${firstThisMonth.time}")

        val listOfApps = HashMap<String,AppInfo>()

        val stats = usageManager.queryAndAggregateUsageStats(firstPreviousMonth.time, firstThisMonth.time)
        resources.openRawResource(R.raw.pkgs).use {
            val jsonParser = JsonReader(it.bufferedReader())
            jsonParser.beginArray()
            while(jsonParser.hasNext()) {
                var pkgId: String? = null
                var flags = AppFlags()

                jsonParser.beginObject()
                while(jsonParser.hasNext()) {
                    val attrName = jsonParser.nextName()
                    if(attrName == "pkgId") {
                        pkgId = jsonParser.nextString()
                    } else if(attrName == "flags") {
                        jsonParser.beginObject()
                        while(jsonParser.hasNext()) {
                            val flagName = jsonParser.nextName()
                            val value = jsonParser.nextBoolean()
                            flags = when(flagName) {
                                "external_content" -> flags.copy(externalContent = value)
                                "instant_messaging" -> flags.copy(instantMessaging = value)
                                "reduced_features" -> flags.copy(reducedFeatures = value)
                                "additional_features" -> flags.copy(additionalFeatures = value)
                                "useful_in_background" -> flags.copy(usefulInBackground = value)
                                else -> flags
                            }
                        }
                        jsonParser.endObject()
                    } else {
                        jsonParser.skipValue()
                    }
                }
                jsonParser.endObject()

                if(pkgId == null) continue
                if(!stats.containsKey(pkgId)) continue

                val installer = try { packageManager.getInstallerPackageName(pkgId) } catch(e: Exception) { "" } ?: ""
                val localInfo = AppLocalInfo(
                    stats[pkgId]!!.totalTimeInForeground,
                    installer
                )
                listOfApps[pkgId] = AppInfo(flags, localInfo)

                Log.e("FLOSS-Donator", "$pkgId has been used ${stats[pkgId]!!.totalTimeInForeground}, installer is $installer")
            }
            jsonParser.endArray()
        }
        runOnUiThread {
            refreshScreen(listOfApps)
        }
    }

    fun refreshScreen(list: Map<String, AppInfo>) {
        val listWidget = findViewById<LinearLayout>(R.id.listOfApps)
        listWidget.removeAllViews()

        val nBackgrounds = list.count { it.value.flags.usefulInBackground }
        //Allocate somewhere between 10% and 30% for all the background apps
        val allocatedPercentToBackgrounds = if(nBackgrounds > 0) 10+20*(1-Math.exp( (1 - nBackgrounds )/5.0)) else 0.0

        Log.e("FLOSS-Donator", "Let's allocate $allocatedPercentToBackgrounds to $nBackgrounds apps")
        //We don't count apps that are useful in background, because they already got their share
        val totalForegroundTime =
            list
                .map { it.value }
                .filter { !it.flags.usefulInBackground }
                .map { it.localInfo.foregroundTime }
                .sum().toDouble()

        val computeScore = { app: String, info: AppInfo ->
            if(info.flags.usefulInBackground)
                allocatedPercentToBackgrounds/nBackgrounds
            else
                100.0 * info.localInfo.foregroundTime / totalForegroundTime
        }
        val appScores = list.toList().map { Triple(it.first, it.second, computeScore(it.first, it.second) )}.sortedBy { -it.third }

        for( (app,info) in appScores) {
            try {
                val percent = computeScore(app, info)
                val layout = layoutInflater.inflate(R.layout.app_row, listWidget, false)

                val iconWidget = layout.findViewById<ImageView>(R.id.app_icon)
                val textWidget = layout.findViewById<TextView>(R.id.app_name)
                val usageWidget = layout.findViewById<ProgressBar>(R.id.usage_percent)

                usageWidget.progress = percent.toInt()

                val icon = packageManager.getApplicationIcon(app)
                iconWidget.setImageDrawable(icon)

                val appPmInfo = packageManager.getApplicationInfo(app, 0)
                val appLabelId = appPmInfo.labelRes
                val appLabel =
                    if (appLabelId == 0) appPmInfo.nonLocalizedLabel else packageManager.getResourcesForApplication(
                        app
                    ).getString(appLabelId)

                textWidget.text = appLabel
                listWidget.addView(layout)
            } catch(e: Exception) {
                Log.d("FLOSS-Donator", "Failed adding info for $app", e)
            }
        }
    }

    val processingPackagesHandler = {
        val thread = HandlerThread("Processing Packages Handler")
        thread.start()
        Handler(thread.looper)
    }()

    override fun onResume() {
        super.onResume()

        /*
         * This code doesn't work, checkSelfPermission returns -1 when permission has been given?!?
        if(checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) != PackageManager.PERMISSION_GRANTED) {
            Log.e("FLOSS-Donator", "I'm missing package usage stats permission")
            startActivity(Intent("android.settings.USAGE_ACCESS_SETTINGS"))
            return
        } */

        processingPackagesHandler.post {
            refreshStats()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }
}
