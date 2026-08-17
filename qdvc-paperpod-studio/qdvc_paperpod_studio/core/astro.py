"""Sunrise, sunset and moon phase.

Computed here rather than on the device, and with no dependencies: the formulae
are short, they need no network, and precomputing them means the Day screen shows
the sun times even when the tablet has not synced in a fortnight.

Accuracy is roughly a minute, which is well inside the range where nobody cares.
Source: the NOAA solar calculator equations.
"""

from __future__ import annotations

import datetime as dt
import math
from zoneinfo import ZoneInfo

MOON_PHASES = [
    "New moon", "Waxing crescent", "First quarter", "Waxing gibbous",
    "Full moon", "Waning gibbous", "Last quarter", "Waning crescent",
]


def sun_times(
    date: dt.date, latitude: float, longitude: float, tz: ZoneInfo
) -> tuple[str | None, str | None]:
    """Returns local (sunrise, sunset) as HH:MM, or (None, None) if the sun does
    not rise or set that day — which matters at high latitudes."""
    rise = _event(date, latitude, longitude, tz, rising=True)
    set_ = _event(date, latitude, longitude, tz, rising=False)
    return rise, set_


def _event(
    date: dt.date, latitude: float, longitude: float, tz: ZoneInfo, rising: bool
) -> str | None:
    n = _day_number(date)
    # Mean solar time. Eastern longitudes reach solar noon earlier in UT, so the
    # longitude is subtracted directly; the west-positive convention used in some
    # write-ups of this formula inverts the sign and quietly breaks everywhere
    # except the Greenwich meridian.
    j_star = n - longitude / 360.0
    m = (357.5291 + 0.98560028 * j_star) % 360
    c = 1.9148 * _sin(m) + 0.0200 * _sin(2 * m) + 0.0003 * _sin(3 * m)
    ecliptic_longitude = (m + c + 180 + 102.9372) % 360
    j_transit = 2451545.0 + j_star + 0.0053 * _sin(m) - 0.0069 * _sin(2 * ecliptic_longitude)
    declination = math.degrees(
        math.asin(_sin(ecliptic_longitude) * math.sin(math.radians(23.4397)))
    )

    # -0.83 degrees accounts for refraction and the solar disc.
    numerator = _sin(-0.83) - math.sin(math.radians(latitude)) * math.sin(math.radians(declination))
    denominator = math.cos(math.radians(latitude)) * math.cos(math.radians(declination))
    if denominator == 0:
        return None
    ratio = numerator / denominator
    if ratio < -1 or ratio > 1:
        return None  # polar day or polar night
    hour_angle = math.degrees(math.acos(ratio))

    julian = j_transit + (hour_angle / 360.0) * (1 if not rising else -1)
    moment = _from_julian(julian).astimezone(tz)
    return moment.strftime("%H:%M")


def moon_phase(date: dt.date) -> str:
    """Names the phase. A number would be precision nobody wants at breakfast."""
    known_new_moon = dt.date(2000, 1, 6)
    synodic = 29.530588853
    days = (date - known_new_moon).days
    position = (days % synodic) / synodic
    index = int((position * 8) + 0.5) % 8
    return MOON_PHASES[index]


def _sin(degrees: float) -> float:
    return math.sin(math.radians(degrees))


def _day_number(date: dt.date) -> float:
    """Days since 2000-01-01 12:00 TT.

    The NOAA formulation counts whole days from *noon*, so the Julian date at
    midnight has to be rounded up rather than used directly — without the ceiling
    every result lands exactly twelve hours out.
    """
    julian_midnight = date.toordinal() + 1721424.5
    return math.ceil(julian_midnight - 2451545.0 + 0.0008)


def _from_julian(julian: float) -> dt.datetime:
    unix_seconds = (julian - 2440587.5) * 86400.0
    return dt.datetime.fromtimestamp(unix_seconds, tz=dt.timezone.utc)
