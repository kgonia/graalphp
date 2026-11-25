# 113: Relative dates with a time of day, weekdays or "first day of" fail; hours aren't wall-clock

**State:** Fixed (branch `elementor-compat`)

**Symptom:** WooCommerce's Action Scheduler fails during `action_scheduler_init`:
```
PHP Fatal error:  Uncaught Error: Call to a member function getTimestamp() on false
  in wp-content/plugins/woocommerce/packages/action-scheduler/classes/ActionScheduler_RecurringActionScheduler.php:43
```
`(new DateTime())->modify('tomorrow 3am')` returned false. `strtotime` and `modify` only knew the keywords at the
start and numeric offsets. They failed on a time of day ("3am", "06:00 am", "15:04"), on next/last/previous/this
with a unit or a weekday, on weekdays, on "first/last day of", on "next week" and on a keyword after an offset.
WordPress and the five bootshare plugins pass 50 such literals, among them "tomorrow 3am", "last week monday",
"first day of next month" and "midnight -3 days".

The offsets were also wrong where they parsed:
- "+1 month" from January 31 gave February 28. timelib gives March 3: the day overflows into the next month.
- Hours and minutes were added as elapsed time. timelib adds them to the wall clock and resolves the offset at
  the end, so "+90 min" from 01:30 CEST on the autumn change day is 03:00 CET, not 02:00 CET.
- "ago" negated only the unit before it. timelib negates every relative field so far.
- An ambiguous wall time took the earlier offset. strtotime and `new DateTime()` take the later one.

**Fix:** `DateFunctions.Relative` parses the string token by token with timelib's rules (`parse_date.re`), then
applies the state as `timelib_update_ts` does:
- **Keywords.** "tomorrow" and "yesterday" set the relative day; they don't add to it. With "today" and
  "midnight" they reset the time of day. "noon" sets it.
- **Times of day.** A 12-hour time needs its meridian followed by a space or the end. A 24-hour time may be "24:00".
  A second time of day is an error.
- **Relative fields.** Numbers with units (several signs multiply), relative text (next, last, previous, this,
  first ... twelfth) with a unit or a weekday, and "next/last/previous/this week".
- **Weekdays.** `do_adjust_for_weekday`'s three behaviours, including "this week" and "sunday this week".
- **The rest.** "first/last day of" applies after the relative months. "ago" negates every relative field so far.
  Dates normalise as `timelib_do_normalize`.
- **The offset.** `localToEpoch` is `do_adjust_timezone`. A gap moves forward. An overlap takes the later
  offset, unless the base is a DateTime in DST (`DateTime::modify`), whose look two hours behind takes the DST
  side. The ISO path resolves its local time the same way.

`DateTime::modify` drops the microseconds when the modifier sets a time of day (`TIMELIB_HAVE_TIME`). It throws
`DateMalformedStringException` for a string it can't parse, as PHP 8.3 does, instead of returning false.
`date_modify()` warns with the same reason and returns false.

Test: `tests/smoke/strtotime-relative.php` checks 80 formats from three base dates in UTC and Europe/Warsaw, with
`strtotime` and `modify`. It also covers microseconds, `date_modify()` failing, and the spring and autumn
changes.

Not covered:
- Sub-second units (msec, usec), "weekday(s)", and dates combined with relative text ("2026-01-01 +1 day") are
  still errors.
- The exception's position and reason are timelib's only for a string that starts with a word ("position 0 (n):
  The timezone could not be found in the database").
- `new DateTime('+1 day')` still has zero microseconds, where PHP keeps the current ones.
