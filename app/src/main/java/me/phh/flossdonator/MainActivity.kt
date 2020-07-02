package me.phh.flossdonator

import android.app.usage.UsageStatsManager
import android.os.Bundle
import android.util.JsonReader
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem

import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONArray
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        }
    }

    override fun onResume() {
        super.onResume()

        thread {
            val usageManager = getSystemService(UsageStatsManager::class.java)

            val now = Date()
            val cal = Calendar.getInstance()
            cal.set(now.year, now.month, 0)

            val firstThisMonth = cal.time
            if(now.month == 0)
                cal.set(now.year-1, 11, 0)
            else
                cal.set(now.year, now.month-1, 0)
            val firstPreviousMonth = cal.time

            val stats = usageManager.queryAndAggregateUsageStats(firstPreviousMonth.time, firstPreviousMonth.time)
            resources.openRawResource(R.raw.pkgs).use {
                val jsonParser = JsonReader(it.bufferedReader())
                jsonParser.beginArray()
                while(jsonParser.hasNext()) {
                    var pkgId: String? = null

                    jsonParser.beginObject()
                    while(jsonParser.hasNext()) {
                        val attrName = jsonParser.nextName()
                        if(attrName == "pkgId") {
                            pkgId = jsonParser.nextString()
                        } else {
                            jsonParser.skipValue()
                        }
                    }
                    jsonParser.endObject()

                    if(pkgId == null) continue
                    if(!stats.containsKey(pkgId)) continue

                    Log.e("FLOSS-Donator", "$pkgId has been used ${stats[pkgId]!!.totalTimeInForeground}")
                }
                jsonParser.endArray()
            }
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
