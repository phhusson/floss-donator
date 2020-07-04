package me.phh.flossdonator

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.JsonReader
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog

import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.math.exp

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
    }

    //POLICY: The default values here are part of the policy
    data class AppFlags(
        val additionalFeatures: Boolean = false,
        val reducedFeatures: Boolean = false,
        val viewerLevel: Int = 8,
        val messaging: Boolean = false,
        val usefulInBackground: Boolean = false,
        val decentralizedNetwork: Boolean = false)
    data class AppLocalInfo(
        val foregroundTime: Long = 0L,
        val installer: String = "")

    data class AppInfo(
        val flags: AppFlags,
        val localInfo: AppLocalInfo
    )

    fun exponentialCap(value: Double, cap: Double, tau: Double): Double {
        return cap * (1.0 - exp( - value / tau ))
    }

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
                            flags = when(flagName) {
                                "viewer_level" -> flags.copy(viewerLevel = jsonParser.nextInt())
                                "messaging" -> flags.copy(messaging = jsonParser.nextBoolean())
                                "reduced_features" -> flags.copy(reducedFeatures = jsonParser.nextBoolean())
                                "additional_features" -> flags.copy(additionalFeatures = jsonParser.nextBoolean())
                                "useful_in_background" -> flags.copy(usefulInBackground = jsonParser.nextBoolean())
                                "decentralized_network" -> flags.copy(decentralizedNetwork = jsonParser.nextBoolean())
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

        Log.e("FLOSS-Donator", "${exponentialCap(value = 0.0, tau = 4*3600*1000.0, cap = 4*3600*1000.0).toLong()}")
        Log.e("FLOSS-Donator", "${exponentialCap(value = 1.0, tau = 4*3600*1000.0, cap = 4*3600*1000.0).toLong()}")
        Log.e("FLOSS-Donator", "${exponentialCap(value = 100.0, tau = 4*3600*1000.0, cap = 4*3600*1000.0).toLong()}")
        Log.e("FLOSS-Donator", "${exponentialCap(value = 10000.0, tau = 4*3600*1000.0, cap = 4*3600*1000.0).toLong()}")

        val updateTimeWithFlags = { app: String, info: AppInfo ->
            val foregroundTime = info.localInfo.foregroundTime
            var newTime = foregroundTime

            // POLICY: If the app provides a paying version on PlayStore, and all the features aren't available in the FLOSS variant
            // Let's say that this app found their business model and doesn't need much donations, so reduce their equivalent time
            if(info.flags.reducedFeatures) {
                newTime /= 8
            }

            // POLICY: Messaging has a very huge networking effect, so smooth that out, by capping them to 4 hours a month
            // The aim is to improve Messaging diversity
            // Ignores reducedFeatures
            if(info.flags.messaging) {
                //cap = tau so that the initial slope is 1, to with small value, newTime ~=foregroundTime
                newTime = exponentialCap(value = foregroundTime*1.0, tau = 4*3600*1000.0, cap = 4*3600*1000.0).toLong()
            }

            //POLICY: Apps that doesn't provide any backend, and are "simply" showing third party content are capped to half an hour a month
            // Example: VLC is a media player, only roughly 1% of the time will the user actually interact with the app,
            // most of the time, the user will simply be watching their movie
            // Ignores IM and reduced features
            if(info.flags.viewerLevel != -1) {
                val cap = when(info.flags.viewerLevel) {
                    10 -> 30*1000L
                    9 -> 60*1000L
                    8 -> 2*60*1000L
                    7 -> 30*60*1000L
                    6 -> 60*60*1000L
                    5 -> 2 * 60 * 60 * 1000L
                    4 -> 4 * 60 * 60 * 1000L
                    3 -> 6 * 60 * 60 * 1000L
                    2 -> 10 * 60 * 60 * 1000L
                    1 -> 16 * 60 * 60 * 1000L
                    0 -> 24 * 60 * 60 * 1000L

                    else -> 24*3600*1000L
                }
                //cap = tau so that the initial slope is 1: with small values, newTime ~=foregroundTime
                newTime = exponentialCap(value = foregroundTime*1.0, tau = 1800*1000.0, cap = 1800*1000.0).toLong()
            }

            val upLocalInfo = info.localInfo.copy(foregroundTime = newTime)
            info.copy(localInfo = upLocalInfo)
        }

        val appsUpdatedTimes = list.map { Pair(it.key, updateTimeWithFlags(it.key, it.value)) }
        val totalForegroundTime =
            appsUpdatedTimes
                .map { it.second }
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

        val sp = getSharedPreferences("default", Context.MODE_PRIVATE)
        if(sp.getFloat("total_monthly_donation", -1.0f) == -1.0f) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_how_much, null)
            //Ask the user how much it thinks they'll donate
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Set") { _, _ ->
                    val valueWidget = dialogView.findViewById<EditText>(R.id.value).text.toString().toFloat()
                    sp.edit().putFloat("total_monthly_donation", valueWidget).apply()
                }
                .setNegativeButton("Later") { _, _ ->

                }
            dialog.show()
        }
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
