# Issue: JSONEncoder produces incorrect UTC offset for timezone-aware datetimes

## Observed behaviour

When serialising a timezone-aware `datetime` object to JSON using the application's built-in JSON encoder, the output timestamp always shows `+00:00` (UTC) as the timezone offset — regardless of what timezone the `datetime` object is actually in.

For example, given a `datetime` with a UTC+10 offset, the serialised JSON string still shows `+00:00` instead of `+10:00`.

**Minimal reproduction:**

```python
from datetime import datetime, timezone, timedelta

tz_sydney = timezone(timedelta(hours=10))
dt = datetime(2017, 1, 1, 12, 0, 0, tzinfo=tz_sydney)

# Serialise using the app's JSON encoder
import json
from your_app import app

with app.app_context():
    result = app.json.dumps(dt)
    print(result)
    # Actual:   "2017-01-01T12:00:00+00:00"   ← wrong offset
    # Expected: "2017-01-01T02:00:00+00:00"    ← converted to UTC, or
    #           "2017-01-01T12:00:00+10:00"    ← preserve original with correct offset
```

## Expected behaviour

Timezone-aware `datetime` objects should be encoded in UTC. If a `datetime` is aware (i.e., it has `tzinfo` set), the encoder should convert it to UTC before formatting, so the UTC offset in the output is always accurate.

Timezone-naive `datetime` objects (no `tzinfo`) should continue to be serialised as-is, as they are assumed to already represent UTC.

## Notes

- The bug is in the JSON serialisation layer, not in how datetimes are stored or passed around.
- The fix should not change the output format for timezone-naive `datetime` objects.
- Python's standard library provides utilities for converting between timezones on `datetime` objects.
