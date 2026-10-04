package com.vibethroughcode.ftree.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.Occasion
import com.vibethroughcode.ftree.data.OccasionKind
import com.vibethroughcode.ftree.data.Occasions
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Coming up" on the home screen: the next few birthdays and remembered days, the same list and the
 * same words as the one in the app (`Occasions`, the `coming_up_*` strings).
 *
 * It reads the family from this phone and nothing else, so it adds no network use and no
 * permission. Tapping it opens the app. Nothing here is scheduled by the app: the system refreshes
 * it from `updatePeriodMillis`.
 */
class UpcomingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Reading every tree is a few small queries off the main thread; goAsync keeps the process
        // alive until they finish.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as FTreeApplication
                val coming = Occasions.upcoming(app.everyoneInAllTrees(), LocalDate.now()).take(ROWS.size)
                val views = render(context, coming)
                appWidgetIds.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun render(context: Context, coming: List<Occasion>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_upcoming)
        val locale = context.resources.configuration.locales[0]
        ROWS.forEachIndexed { index, row ->
            val occasion = coming.getOrNull(index)
            views.setViewVisibility(row.container, if (occasion == null) View.GONE else View.VISIBLE)
            if (occasion != null) {
                views.setTextViewText(row.name, occasion.person.name.orEmpty().trim())
                views.setTextViewText(row.detail, detail(context, occasion))
                views.setTextViewText(row.whenText, whenText(context, occasion, locale))
            }
        }
        views.setViewVisibility(R.id.widget_empty, if (coming.isEmpty()) View.VISIBLE else View.GONE)
        if (coming.isEmpty()) views.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_empty))
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return views
    }

    /** The same words as a row in "Coming up". */
    private fun detail(context: Context, occasion: Occasion): String {
        val years = occasion.years
        return when (occasion.kind) {
            OccasionKind.BIRTHDAY -> if (years != null) context.getString(R.string.coming_up_turns, years)
            else context.getString(R.string.coming_up_birthday)
            OccasionKind.BIRTH_REMEMBRANCE -> if (years != null) context.getString(R.string.coming_up_would_have_been, years)
            else context.getString(R.string.coming_up_birthday)
            OccasionKind.DEATH_ANNIVERSARY -> if (years != null) {
                context.resources.getQuantityString(R.plurals.coming_up_years_since, years, years)
            } else {
                context.getString(R.string.coming_up_death_day)
            }
        }
    }

    private fun whenText(context: Context, occasion: Occasion, locale: Locale): String = when (occasion.daysAway) {
        0 -> context.getString(R.string.coming_up_today)
        1 -> context.getString(R.string.coming_up_tomorrow)
        else -> occasion.date.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }

    private class Row(val container: Int, val name: Int, val detail: Int, val whenText: Int)

    private companion object {
        val ROWS = listOf(
            Row(R.id.row1, R.id.name1, R.id.detail1, R.id.when1),
            Row(R.id.row2, R.id.name2, R.id.detail2, R.id.when2),
            Row(R.id.row3, R.id.name3, R.id.detail3, R.id.when3),
        )
    }
}
