# Wrist Log

A one-page tracker for Noise smartwatch data. Each day you copy these numbers from NoiseFit into the form:

- sleep and deep sleep
- steps
- resting heart rate
- SpO2
- stress
- workouts (type, duration, average heart rate, calories)

The page shows 7-day tiles and 14-day charts against these targets: sleep 7–9 h, 8,000+ steps, and 4 workouts a week. It also has a history table and a tips section for the watch.

## Where it runs

This is a copy of the source for the Wrist Log page published on claude.ai. Saving days and the **Get my weekly read** button rely on that page's runtime (`window.claude.use("db")` and `window.claude.use("sample")`). If you open `index.html` directly in a browser, it shows only the labelled example data, and saving is turned off.

The days you've logged are stored in the published page's database under the `days` collection, one document per date. They're not in this repository.
