package com.metrovalencia.widget
import com.metrovalencia.widget.R

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.RemoteViews
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Implementation of App Widget functionality.
 */
@JsonClass(generateAdapter = true)
data class StationDepartures(
    @Json(name = "station") val station: String,
    @Json(name = "departures") val departures: List<Departure>
)

@JsonClass(generateAdapter = true)
data class Departure(
    @Json(name = "line") val line: String,
    @Json(name = "direction") val direction: String,
    @Json(name = "time") val time: String
)


class MetrovalenciaWidget : AppWidgetProvider() {

    companion object {
        const val BUTTON_CLICK = "com.metrovalencia.widget.BUTTON_CLICK"
    }

    private val okhttpclient: OkHttpClient = OkHttpClient()
    private val moshi: Moshi = Moshi.Builder().build()
    private val departuresListAdapter: JsonAdapter<List<StationDepartures>> =
        moshi.adapter(Types.newParameterizedType(List::class.java, StationDepartures::class.java))

    private fun updateMetrovalenciaTimesService(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val remoteViews = createRemoteViews(context, appWidgetId)
        val metrovalenciaWidget = ComponentName(context, MetrovalenciaWidget::class.java)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val stations = listOf(
            // id, display name, name in response
            Triple("39", "Xàtiva", "Xàtiva"),
            Triple("30", "Colón", "Colón"),
            Triple("69", "Benimaclet", "Benimaclet"),
            Triple("37", "À. Guimerà", "Àngel Guimerà")
        )
        val stationDisplayNames = stations.map { it.second }
        val stationIds = stations.joinToString(",") { it.first }

        val textViewIds = (1..stations.size).map { index ->
            val textViewName = if (index == 1) "textView" else "textView$index"
            context.resources.getIdentifier(textViewName, "id", context.packageName)
        }.filter { it != 0 }

        val requestUrl = "https://metroapi.alexbadi.es/v2/departures/$stationIds"

        resetTextViews(textViewIds, stationDisplayNames, remoteViews, appWidgetManager, metrovalenciaWidget)

        if (powerManager.isPowerSaveMode && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            widgetSetErrorResponse(
                textViewIds,
                metrovalenciaWidget,
                appWidgetManager,
                "Please enable unrestricted battery usage",
                remoteViews
            )
        } else {
            val request: Request = Request.Builder()
                .url(requestUrl)
                .build()

            okhttpclient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    widgetSetErrorResponse(
                        textViewIds,
                        metrovalenciaWidget,
                        appWidgetManager,
                        e.message,
                        remoteViews
                    )
                    e.printStackTrace()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            widgetSetErrorResponse(
                                textViewIds,
                                metrovalenciaWidget,
                                appWidgetManager,
                                "Error accessing URL: " + response.code.toString(),
                                remoteViews
                            )
                            throw IOException("Unexpected code $response")
                        }

                        val departuresList = departuresListAdapter.fromJson(response.body!!.source())

                        if (departuresList != null) {
                            stations.forEachIndexed { index, stationInfo ->
                                val stationData = departuresList.find { it.station == stationInfo.third }
                                val textViewId = textViewIds[index]
                                val displayName = stationInfo.second

                                val text = if (stationData != null && stationData.departures.isNotEmpty()) {
                                    val departuresText = stationData.departures.take(2).joinToString(" | ") { "L${it.line} (${it.time})" }
                                    "$displayName: $departuresText"
                                } else {
                                    "$displayName: N/A"
                                }
                                remoteViews.setTextViewText(textViewId, text)
                            }
                        } else {
                            stations.forEachIndexed { index, stationInfo ->
                                remoteViews.setTextViewText(textViewIds[index], "${stationInfo.second}: Error parsing response")
                            }
                        }
                        appWidgetManager.updateAppWidget(metrovalenciaWidget, remoteViews)
                    }
                }
            })
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateMetrovalenciaTimesService(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateMetrovalenciaTimesService(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == BUTTON_CLICK) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val metrovalenciaWidget = ComponentName(context, MetrovalenciaWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(metrovalenciaWidget)

            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val vibrationEffect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
            val attributes = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()
            vibrator.vibrate(vibrationEffect, attributes)

            for (appWidgetId in appWidgetIds) {
                updateMetrovalenciaTimesService(context, appWidgetManager, appWidgetId)
            }
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }

    private fun createRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
        val remoteViews = RemoteViews(context.packageName, R.layout.metrovalencia_widget)
        val intent = Intent(context, MetrovalenciaWidget::class.java).apply {
            action = BUTTON_CLICK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        remoteViews.setOnClickPendingIntent(R.id.mainLayout, pendingIntent)
        return remoteViews
    }

    private fun widgetSetErrorResponse(
        textViewIds: List<Int>,
        metrovalenciaWidget: ComponentName,
        appWidgetManager: AppWidgetManager,
        message: String?,
        remoteViews: RemoteViews
    ) {
        remoteViews.setTextViewText(R.id.textView, message ?: "Error")
        textViewIds.drop(1).forEach {
            remoteViews.setViewVisibility(it, View.GONE)
        }
        appWidgetManager.updateAppWidget(metrovalenciaWidget, remoteViews)
    }

    private fun resetTextViews(
        textViewIds: List<Int>,
        stopPrefixes: List<String>,
        remoteViews: RemoteViews,
        appWidgetManager: AppWidgetManager,
        metrovalenciaWidget: ComponentName
    ) {
        textViewIds.forEachIndexed { index, textViewId ->
            remoteViews.setViewVisibility(textViewId, View.VISIBLE)
            remoteViews.setTextViewText(textViewId, stopPrefixes[index] + ": ...")
        }
        appWidgetManager.updateAppWidget(metrovalenciaWidget, remoteViews)
    }
}
