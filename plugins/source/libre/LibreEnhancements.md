# Libre Direct Plugin - Future Enhancements

This document tracks potential improvements and enhancements for the direct FreeStyle Libre 2/3 CGM integration.

---

## 1. Data Smoothing

### Current State
- TrendCalculator uses linear regression for trend arrow calculation (provides implicit smoothing for trends only)
- Raw glucose values from sensor are passed through without additional filtering
- Relies on Libre sensor's internal calibration/smoothing

### Potential Enhancements

#### Exponential Moving Average (EMA)
- Apply EMA filter to reduce noise while maintaining responsiveness
- Configurable smoothing factor (alpha) via preferences
- Typical alpha: 0.2-0.4 for CGM data

#### Savitzky-Golay Filter
- Polynomial smoothing that preserves peaks and valleys better than EMA
- Good for maintaining accuracy during rapid glucose changes
- Window size: 5-7 readings recommended

#### Outlier Rejection
- Detect and filter glucose spikes caused by sensor errors
- Use median absolute deviation (MAD) or IQR-based detection
- Option to interpolate rejected values or mark as unreliable

#### Configurable Options
- Add preference to enable/disable smoothing
- Allow user to select smoothing intensity (light/medium/aggressive)
- Show both raw and smoothed values in UI (optional)

---

## 2. Nightscout Upload Frequency

### Current State
- Event-driven uploads triggered by `persistenceLayer.insertCgmSourceData()`
- NSClient syncs automatically via DataSyncSelectorV3
- Uploads occur approximately every 1 minute (matching Libre reading frequency)
- No batching or throttling implemented

### Potential Enhancements

#### Configurable Upload Interval
- Add preference for minimum upload interval (1/5/15 minutes)
- Batch multiple readings for less frequent uploads
- Reduce battery/data usage for users who don't need real-time NS updates

#### Retry Logic for Failed Uploads
- Queue failed uploads for retry
- Exponential backoff for repeated failures
- Notification when upload backlog exceeds threshold

#### Offline Buffering
- Store readings locally when no network available
- Bulk upload when connectivity restored
- Configurable buffer size limit

#### Upload Status Indicator
- Show last successful upload time in UI
- Visual indicator for upload queue status
- Alert for prolonged upload failures

---

## 3. Missed Data Backfilling

### Current State
- Libre 2: Parses trend (16 readings, 1-min) and history (32 readings, 15-min)
- Libre 3: Real-time streaming only
- PersistenceLayer deduplicates by timestamp
- No explicit gap detection or recovery

### Potential Enhancements

#### Gap Detection
- Monitor for gaps in glucose data stream
- Detect disconnections lasting > N minutes
- Track expected vs received reading count

#### Automatic Backfill Request
- On reconnection, request full history from sensor
- Parse and insert historical readings with correct timestamps
- Mark backfilled readings distinctly (optional)

#### NFC-Based Full History Dump
- For Libre 2: Use NFC to read complete sensor memory
- Provides up to 8 hours of 15-minute history + 16 minutes of 1-minute data
- Useful after extended BLE disconnection
- Requires user action (phone tap to sensor)

#### Reconnection Strategy
- After disconnect, attempt immediate reconnection
- On successful reconnect, request all data since last reading
- Merge with existing data, avoiding duplicates

#### Gap Interpolation (Optional)
- For short gaps (< 15 minutes), optionally interpolate missing values
- Mark interpolated values with distinct quality flag
- Configurable: enable/disable, max gap to interpolate

---

## 4. Additional Enhancement Ideas

### Connection Reliability
- Implement connection quality scoring
- Adaptive scan/reconnect intervals based on signal quality
- Support for multiple bonded sensors (easy switching)

### Sensor Calibration
- Manual calibration entry support
- Calibration factor storage and application
- Integration with blood glucose meter readings

### Extended Alerts
- Predictive alerts (glucose trending toward high/low)
- Rate of change alerts (rapid rise/fall)
- Compression low detection (pressure on sensor)

### Data Export
- Export glucose data to CSV/JSON
- Share reports via email/cloud
- Integration with other health apps

### Debugging & Diagnostics
- Detailed BLE communication logging (toggleable)
- Sensor signal quality history
- Connection event timeline

---

## Implementation Priority

| Enhancement | Priority | Complexity | Impact |
|-------------|----------|------------|--------|
| Gap detection & backfill | High | Medium | High |
| Outlier rejection | High | Low | Medium |
| EMA smoothing | Medium | Low | Medium |
| Configurable upload interval | Medium | Low | Low |
| NFC history dump | Medium | High | High |
| Offline buffering | Low | Medium | Medium |
| Predictive alerts | Low | High | Medium |

---

## References

- xDrip+ Libre implementation: https://github.com/NightscoutFoundation/xDrip
- Juggluco app (Libre 3): https://juggluco.nl/
- Libre 2/3 protocol documentation: (reverse-engineered, see xDrip+ source)

---

*Last updated: January 2026*
