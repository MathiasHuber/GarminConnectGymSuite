#!/usr/bin/env python3
"""Desktop companion for Garmin Connect Gym Suite, built on python-garminconnect.

    pip install -r tools/requirements.txt

    python tools/garmin_tokens.py login
        Log in (asks for email, password and MFA code if needed) and print the
        token JSON. Paste it into the app under "Import tokens" if the in-app
        sign-in is blocked.

    python tools/garmin_tokens.py export strength_sets.csv
        Download every strength training activity's sets into a CSV with the
        same columns as the app's CSV export, for analysis on a computer.

Tokens are cached in ~/.garminconnect so you only log in once.
"""

import csv
import getpass
import os
import sys

from garminconnect import Garmin

TOKENSTORE = os.path.expanduser("~/.garminconnect")


def client() -> Garmin:
    api = Garmin()
    try:
        api.login(TOKENSTORE)
        return api
    except Exception:
        pass
    email = input("Garmin email: ")
    password = getpass.getpass("Garmin password: ")
    api = Garmin(email, password, prompt_mfa=lambda: input("MFA code: "))
    api.login(TOKENSTORE)
    return api


def humanize(name: str) -> str:
    return " ".join(w.capitalize() for w in name.split("_") if w)


def export(path: str) -> None:
    api = client()
    rows = []
    start = 0
    while True:
        page = api.get_activities(start, 100, activitytype="strength_training")
        if not page:
            break
        for act in page:
            sets = api.get_activity_exercise_sets(act["activityId"]).get("exerciseSets") or []
            index = 0
            for s in sets:
                if s.get("setType") == "REST":
                    continue
                exercises = s.get("exercises") or []
                ex = max(exercises, key=lambda e: e.get("probability") or 0) if exercises else {}
                category = ex.get("category") or "UNKNOWN"
                name = ex.get("name")
                weight = s.get("weight")
                start_local = act.get("startTimeLocal") or ""
                rows.append({
                    "date": start_local[:10],
                    "start_time_local": start_local,
                    "activity_id": act["activityId"],
                    "activity_name": act.get("activityName"),
                    "set_index": index,
                    "exercise_category": category,
                    "exercise_name": name,
                    "exercise": humanize(name or category),
                    "reps": s.get("repetitionCount"),
                    # Garmin reports set weight in grams.
                    "weight_kg": weight / 1000 if weight is not None else None,
                    "duration_s": s.get("duration"),
                    "set_start_time": s.get("startTime"),
                })
                index += 1
            print(f"{start_local[:10]}  {act.get('activityName')}: {index} sets", file=sys.stderr)
        if len(page) < 100:
            break
        start += 100

    rows.sort(key=lambda r: (r["start_time_local"], r["set_index"]))
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()) if rows else ["date"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} sets to {path}", file=sys.stderr)


def main() -> None:
    if len(sys.argv) >= 2 and sys.argv[1] == "login":
        print(client().client.dumps())
    elif len(sys.argv) >= 3 and sys.argv[1] == "export":
        export(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
