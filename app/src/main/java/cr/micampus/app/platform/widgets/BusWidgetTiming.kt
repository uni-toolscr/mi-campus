package cr.micampus.app.platform.widgets

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private val countdownWindow: Duration = Duration.ofMinutes(30)
val busWidgetDepartureGrace: Duration = Duration.ofMinutes(1)

/** The text state for the next visible bus departure. */
sealed interface BusCountdownPresentation {
    /** The departure is farther away than the live countdown window. */
    data object Scheduled : BusCountdownPresentation

    /** A whole, ceiling-rounded number of minutes until departure. */
    data class Minutes(val value: Int) : BusCountdownPresentation {
        init {
            require(value > 0)
        }
    }

    /** The departure time has arrived, but remains visible for its grace period. */
    data object DepartingNow : BusCountdownPresentation
}

/** A complete decision for the coordinator: schedule once, or do not schedule. */
sealed interface BusWidgetRefreshPlan {
    data class RefreshAt(val at: LocalDateTime) : BusWidgetRefreshPlan
    data object NoRefresh : BusWidgetRefreshPlan
}

/** Collapses per-widget plans into the one alarm the application should own. */
fun earliestBusWidgetRefresh(plans: Iterable<BusWidgetRefreshPlan>): BusWidgetRefreshPlan =
    plans.filterIsInstance<BusWidgetRefreshPlan.RefreshAt>()
        .minByOrNull(BusWidgetRefreshPlan.RefreshAt::at)
        ?: BusWidgetRefreshPlan.NoRefresh

/**
 * Returns the minute-only presentation for [departure], or null once its
 * [departureGrace] has ended so the caller can select the next bus.
 */
fun busCountdownPresentation(
    departure: LocalDateTime,
    now: LocalDateTime,
    departureGrace: Duration = busWidgetDepartureGrace,
): BusCountdownPresentation? {
    require(!departureGrace.isNegative)
    if (!now.isBefore(departure.plus(departureGrace))) return null

    if (!now.isBefore(departure)) return BusCountdownPresentation.DepartingNow

    val remaining = Duration.between(now, departure)
    if (remaining > countdownWindow) return BusCountdownPresentation.Scheduled

    val wholeMinutes = remaining.toMinutes()
    val roundedMinutes = if (remaining == Duration.ofMinutes(wholeMinutes)) wholeMinutes else wholeMinutes + 1
    return BusCountdownPresentation.Minutes(roundedMinutes.toInt())
}

/**
 * Plans the next local-only content transition for one visible departure.
 * The result deliberately contains no Android scheduling concerns, allowing a
 * coordinator to combine plans for every installed widget and schedule only
 * their earliest transition.
 */
fun busWidgetRefreshPlan(
    departure: LocalDateTime,
    now: LocalDateTime,
    departureGrace: Duration = busWidgetDepartureGrace,
): BusWidgetRefreshPlan {
    require(!departureGrace.isNegative)
    val expiresAt = departure.plus(departureGrace)
    if (!now.isBefore(expiresAt)) return BusWidgetRefreshPlan.NoRefresh

    val countdownStartsAt = departure.minus(countdownWindow)
    if (now.isBefore(countdownStartsAt)) return BusWidgetRefreshPlan.RefreshAt(countdownStartsAt)
    if (!now.isBefore(departure)) return BusWidgetRefreshPlan.RefreshAt(expiresAt)

    val nextMinuteBoundary = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
    return BusWidgetRefreshPlan.RefreshAt(minOf(nextMinuteBoundary, departure))
}
